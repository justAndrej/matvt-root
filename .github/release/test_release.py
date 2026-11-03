import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.meta = {"tag": "v1.1.0", "source_sha": "a" * 40}

    def test_version_and_branch_validation(self):
        value = release.validate("feature/density", "v1.1.0-rc.1", "102")
        self.assertEqual(value["version_name"], "1.1.0-rc.1")
        self.assertEqual(value["version_code"], 102)
        for branch, tag, code in [("../bad", "v1.1.0", "102"),
                                  ("phone-port", "v1.1.0\nmalicious", "102"),
                                  ("phone-port", "v1.1.0", "0"),
                                  ("phone-port", "v1.1.0", "2147483647")]:
            with self.assertRaises((ValueError, RuntimeError)):
                release.validate(branch, tag, code)

    def test_published_release_is_never_overwritten(self):
        with patch.object(release, "api", return_value={"draft": False, "body": release.DRAFT_MARKER}) as api:
            with self.assertRaises(ValueError):
                release.check_destination("owner/repo", self.meta)
            self.assertEqual(api.call_count, 1)

    def test_unrelated_draft_is_not_overwritten(self):
        with patch.object(release, "api", return_value={"draft": True, "body": "Hand-written notes"}):
            with self.assertRaises(ValueError):
                release.check_destination("owner/repo", self.meta)

    def test_tag_cannot_move_to_another_commit(self):
        responses = [None, [], {"object": {"type": "commit", "sha": "b" * 40}}]
        with patch.object(release, "api", side_effect=responses):
            with self.assertRaises(ValueError):
                release.check_destination("owner/repo", self.meta)

    def test_matching_own_draft_can_be_resumed(self):
        draft = {"draft": True, "body": release.DRAFT_MARKER, "target_commitish": "a" * 40}
        ref = {"object": {"type": "commit", "sha": "a" * 40}}
        with patch.object(release, "api", side_effect=[draft, ref]):
            self.assertEqual(release.check_destination("owner/repo", self.meta), (draft, ref))

    def test_annotated_tag_is_checked_against_its_commit(self):
        ref = {"object": {"type": "tag", "sha": "c" * 40}}
        with patch.object(release, "api", side_effect=[None, [], ref, {"object": {"type": "commit", "sha": "a" * 40}}]):
            self.assertEqual(release.check_destination("owner/repo", self.meta), (None, ref))

    def test_uncreated_tag_and_release_are_allowed(self):
        with patch.object(release, "api", side_effect=[None, [], None]):
            self.assertEqual(release.check_destination("owner/repo", self.meta), (None, None))

    def test_draft_lookup_falls_back_to_release_listing(self):
        draft = {"tag_name": "v1.1.0", "draft": True, "body": release.DRAFT_MARKER}
        with patch.object(release, "api", side_effect=[None, [draft]]):
            self.assertEqual(release.find_release("owner/repo", "v1.1.0"), draft)

    def publication_fixture(self, directory):
        apk = directory / "MATVT-v1.1.0.apk"
        apk.write_bytes(b"test APK")
        meta = {**self.meta, "source_branch": "phone-port", "version_name": "1.1.0", "version_code": 102,
                "application_id": release.APP_ID, "repository": "owner/repo", "apk_file": apk.name,
                "apk_sha256": release.sha256(apk), "certificate_sha256": "d" * 64,
                "run_url": "https://github.com/owner/repo/actions/runs/1"}
        (directory / "build-info.json").write_text(json.dumps(meta))
        (directory / "SHA256SUMS").write_text("test checksums")
        return [{"name": p.name, "size": p.stat().st_size, "state": "uploaded",
                 "digest": "sha256:" + release.sha256(p)} for p in directory.iterdir()]

    def test_publication_waits_until_all_assets_are_verified(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            assets = self.publication_fixture(directory)
            responses = [{}, {"id": 1}, {"assets": assets},
                         {"draft": False, "html_url": "https://github.com/owner/repo/releases/tag/v1.1.0"}]
            with patch.dict(os.environ, {"GH_REPO": "owner/repo", "EXPECTED_SOURCE_SHA": "a" * 40,
                                         "PRERELEASE": "false", "GITHUB_STEP_SUMMARY": ""}), \
                    patch.object(release, "check_destination", return_value=(None, None)), \
                    patch.object(release, "run", return_value=""), \
                    patch.object(release, "api", side_effect=responses) as api, \
                    contextlib.redirect_stdout(io.StringIO()):
                release.publish(directory)
                self.assertTrue(api.call_args_list[1].args[2]["draft"])
                self.assertEqual(api.call_args_list[2].args, ("repos/owner/repo/releases/1",))
                self.assertFalse(api.call_args_list[3].args[2]["draft"])

    def test_missing_assets_leave_the_release_as_draft(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            self.publication_fixture(directory)
            with patch.dict(os.environ, {"GH_REPO": "owner/repo", "EXPECTED_SOURCE_SHA": "a" * 40,
                                         "PRERELEASE": "false"}), \
                    patch.object(release, "check_destination", return_value=(None, None)), \
                    patch.object(release, "run", return_value=""), \
                    patch.object(release, "api", side_effect=[{}, {"id": 1}, {"assets": []}]) as api:
                with self.assertRaises(ValueError):
                    release.publish(directory)
                self.assertEqual(api.call_count, 3)


if __name__ == "__main__":
    unittest.main()
