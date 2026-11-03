#!/usr/bin/env python3
"""Manual-release tooling. Uses only the standard library, Android build tools and gh."""
import base64
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

APP_ID = "io.github.virresh.matvt"
DRAFT_MARKER = "<!-- matvt-manual-release -->"
SIGNING_SECRETS = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")


def run(args, private=False):
    result = subprocess.run([str(arg) for arg in args], text=True, capture_output=True)
    if result.returncode:
        message = "APK signing/verification failed; check the signing secrets" if private else result.stderr.strip()
        raise RuntimeError(message or f"{args[0]} failed")
    return result.stdout


def validate(branch, tag, code):
    if not branch or branch.startswith("refs/"):
        raise ValueError("Use a branch name such as phone-port, without refs/heads/")
    run(["git", "check-ref-format", "refs/heads/" + branch])
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?", tag):
        raise ValueError("Tag must look like v1.1.0 or v1.1.0-rc.1")
    run(["git", "check-ref-format", "refs/tags/" + tag])
    if not re.fullmatch(r"[0-9]+", str(code)) or not 1 <= int(code) <= 2100000000:
        raise ValueError("versionCode must be an integer between 1 and 2100000000")
    return {"source_branch": branch, "tag": tag, "version_name": tag[1:], "version_code": int(code)}


def inputs():
    return validate(os.environ["SOURCE_BRANCH"], os.environ["RELEASE_TAG"], os.environ["VERSION_CODE"])


def checked_sha(value):
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Expected a full source commit SHA")
    return value


def sha256(path):
    with Path(path).open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def check_signing():
    missing = [name for name in SIGNING_SECRETS if not os.environ.get(name)]
    if missing:
        raise ValueError("Missing repository signing secrets: " + ", ".join(missing))


def stage(source, destination):
    meta = inputs()
    meta["source_sha"] = checked_sha(os.environ["SOURCE_SHA"])
    if run(["git", "-C", source, "rev-parse", "HEAD"]).strip() != meta["source_sha"]:
        raise ValueError("Source checkout changed during the build")
    run(["git", "-C", source, "diff", "--exit-code", "HEAD", "--"])
    meta["workflow_sha"] = checked_sha(os.environ["GITHUB_SHA"])
    meta["repository"] = os.environ["GITHUB_REPOSITORY"]
    meta["run_url"] = f"https://github.com/{meta['repository']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    build_dir = Path(source) / "app/build/outputs/apk/release"
    produced = json.loads((build_dir / "output-metadata.json").read_text())
    if produced.get("applicationId") != APP_ID or len(produced.get("elements", [])) != 1:
        raise ValueError("Expected one release APK for " + APP_ID)
    apk = produced["elements"][0]
    if apk.get("versionCode") != meta["version_code"] or apk.get("versionName") != meta["version_name"]:
        raise ValueError("Built APK version does not match the requested release")
    name = apk["outputFile"]
    if Path(name).name != name or not name.endswith(".apk"):
        raise ValueError("Unexpected APK output path")
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(build_dir / name, destination / "app-unsigned.apk")
    meta["application_id"] = APP_ID
    meta["unsigned_apk_sha256"] = sha256(destination / "app-unsigned.apk")
    write_json(destination / "build-info.json", meta)
    print(f"Staged {meta['tag']} ({meta['version_code']}) from {meta['source_sha']}")


def read_metadata(directory):
    meta = json.loads((Path(directory) / "build-info.json").read_text())
    expected = validate(meta["source_branch"], meta["tag"], str(meta["version_code"]))
    if any(meta[key] != value for key, value in expected.items()):
        raise ValueError("Inconsistent release metadata")
    checked_sha(meta["source_sha"])
    if meta["source_sha"] != os.environ["EXPECTED_SOURCE_SHA"] or meta["application_id"] != APP_ID:
        raise ValueError("Artifact does not match the selected source commit/application")
    return meta


def sign(source, destination):
    check_signing()
    meta = read_metadata(source)
    unsigned = Path(source) / "app-unsigned.apk"
    if sha256(unsigned) != meta["unsigned_apk_sha256"]:
        raise ValueError("Unsigned APK checksum mismatch")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/30.0.3"
    badging = run([tools / "aapt", "dump", "badging", unsigned])
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging, re.M)
    if not package or package.groups() != (APP_ID, str(meta["version_code"]), meta["version_name"]):
        raise ValueError("APK manifest does not match release metadata")
    if "application-debuggable" in badging:
        raise ValueError("Expected a release build, not a debuggable APK")
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    apk = destination / f"MATVT-{meta['tag']}.apk"
    with tempfile.TemporaryDirectory(prefix="matvt-sign-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        keystore = Path(temporary) / "signing.keystore"
        try:
            encoded = "".join(os.environ["ANDROID_KEYSTORE_BASE64"].split())
            keystore.write_bytes(base64.b64decode(encoded, validate=True))
        except ValueError as exception:
            raise ValueError("ANDROID_KEYSTORE_BASE64 is not a valid base64 keystore") from exception
        keystore.chmod(0o600)
        aligned = Path(temporary) / "aligned.apk"
        run([tools / "zipalign", "-f", "4", unsigned, aligned])
        run([tools / "apksigner", "sign", "--ks", keystore,
             "--ks-key-alias", os.environ["ANDROID_KEY_ALIAS"],
             "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
             "--v4-signing-enabled", "false", "--out", apk, aligned], private=True)
        certificate = run([tools / "apksigner", "verify", "--verbose", "--print-certs", apk], private=True)
        run([tools / "zipalign", "-c", "4", apk])
    fingerprint = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", certificate)
    if not fingerprint:
        raise ValueError("Could not verify the signing certificate fingerprint")
    meta["certificate_sha256"] = fingerprint.group(1).lower()
    meta["apk_file"] = apk.name
    meta["apk_sha256"] = sha256(apk)
    write_json(destination / "build-info.json", meta)
    files = [apk, destination / "build-info.json"]
    (destination / "SHA256SUMS").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in files))
    print(f"Signed and verified {apk.name}; certificate SHA-256: {meta['certificate_sha256']}")


def api(path, method="GET", data=None, missing=False):
    args = ["gh", "api", "--method", method, path]
    if data is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(data) if data is not None else None,
                            text=True, capture_output=True)
    if result.returncode:
        if missing and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(result.stderr.strip() or "GitHub API request failed")
    return json.loads(result.stdout) if result.stdout.strip() else None


def find_release(repo, tag):
    release = api(f"repos/{repo}/releases/tags/{tag}", missing=True)
    if release:
        return release
    # The by-tag endpoint can omit drafts. Authenticated release listing includes them.
    page = 1
    while True:
        releases = api(f"repos/{repo}/releases?per_page=100&page={page}")
        for candidate in releases:
            if candidate["tag_name"] == tag:
                return candidate
        if len(releases) < 100:
            return None
        page += 1


def check_destination(repo, meta):
    tag = meta["tag"]
    release = find_release(repo, tag)
    if release and (not release["draft"] or DRAFT_MARKER not in (release.get("body") or "")):
        raise ValueError("This tag already has a published release or an unrelated draft; use a new tag")
    ref = api(f"repos/{repo}/git/ref/tags/{tag}", missing=True)
    if ref:
        obj = ref["object"]
        for _ in range(10):
            if obj["type"] != "tag":
                break
            obj = api(f"repos/{repo}/git/tags/{obj['sha']}")["object"]
        if obj["type"] != "commit" or obj["sha"] != meta["source_sha"]:
            raise ValueError("Existing tag points to a different commit; it will not be moved")
    elif release and release.get("target_commitish") != meta["source_sha"]:
        raise ValueError("Existing draft targets another commit")
    return release, ref


def publish(directory):
    directory = Path(directory)
    meta = read_metadata(directory)
    repo = os.environ["GH_REPO"]
    if meta["repository"] != repo:
        raise ValueError("Artifact belongs to a different repository")
    apk = directory / f"MATVT-{meta['tag']}.apk"
    if meta.get("apk_file") != apk.name or sha256(apk) != meta["apk_sha256"]:
        raise ValueError("Signed APK checksum/filename mismatch")
    files = [apk, directory / "build-info.json", directory / "SHA256SUMS"]
    prerelease = os.environ["PRERELEASE"].lower() == "true"
    release, ref = check_destination(repo, meta)
    if ref is None:
        api(f"repos/{repo}/git/refs", "POST", {"ref": "refs/tags/" + meta["tag"], "sha": meta["source_sha"]})
    body = (f"{DRAFT_MARKER}\n## MATVT {meta['version_name']}\n\n"
            f"**Root is required** for the phone version. Grant root to MATVT itself.\n\n"
            f"- Source branch: <code>{html.escape(meta['source_branch'])}</code>\n"
            f"- Source commit: [{meta['source_sha']}](https://github.com/{repo}/commit/{meta['source_sha']})\n"
            f"- Android versionCode: `{meta['version_code']}`\n"
            f"- [Build run]({meta['run_url']})\n"
            f"- Signing certificate SHA-256: `{meta['certificate_sha256']}`\n\n"
            "The release APK is signed with the stored Android Studio key. "
            "`build-info.json` records the source/tooling revisions; `SHA256SUMS` verifies the attached files.\n")
    payload = {"tag_name": meta["tag"], "target_commitish": meta["source_sha"],
               "name": "MATVT " + meta["version_name"], "body": body, "draft": True, "prerelease": prerelease}
    if release is None:
        release = api(f"repos/{repo}/releases", "POST", payload)
    else:
        release = api(f"repos/{repo}/releases/{release['id']}", "PATCH", payload)
    # Only our own matching draft can reach this point; published releases are never overwritten.
    run(["gh", "release", "upload", meta["tag"], *files, "--clobber"])
    uploaded = api(f"repos/{repo}/releases/{release['id']}")
    assets = {asset["name"]: asset for asset in uploaded["assets"]}
    for path in files:
        asset = assets.get(path.name)
        if not asset or asset["state"] != "uploaded" or asset["size"] != path.stat().st_size:
            raise ValueError("Release asset missing/incomplete: " + path.name)
        if asset.get("digest") and asset["digest"] != "sha256:" + sha256(path):
            raise ValueError("Release asset checksum mismatch: " + path.name)
    published = api(f"repos/{repo}/releases/{release['id']}", "PATCH",
                    {"draft": False, "prerelease": prerelease, "make_latest": "false" if prerelease else "true"})
    if published["draft"]:
        raise ValueError("GitHub did not publish the release")
    print(published["html_url"])
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as stream:
            stream.write(f"## Published [MATVT {meta['version_name']}]({published['html_url']})\n\n"
                         f"Source: `{meta['source_sha']}` · versionCode: `{meta['version_code']}`\n")


def main():
    command = sys.argv[1]
    if command == "validate":
        meta = inputs()
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            stream.write(f"version_name={meta['version_name']}\nversion_code={meta['version_code']}\n")
        print(f"Release {meta['tag']} from {meta['source_branch']}, versionCode {meta['version_code']}")
    elif command == "check-signing":
        check_signing()
        print("Signing secrets are configured")
    elif command == "stage":
        stage(*sys.argv[2:4])
    elif command == "sign":
        sign(*sys.argv[2:4])
    elif command == "publish":
        publish(sys.argv[2])
    else:
        raise ValueError("Unknown command: " + command)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError) as exception:
        print(f"Release failed: {exception}", file=sys.stderr)
        sys.exit(1)
