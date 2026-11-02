package io.github.virresh.matvt.gui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.virresh.matvt.R;
import io.github.virresh.matvt.helper.CursorAppearance;
import io.github.virresh.matvt.helper.DensitySettings;
import io.github.virresh.matvt.helper.Helper;
import io.github.virresh.matvt.view.CursorDrawable;

public class GuiActivity extends AppCompatActivity {
    CountDownTimer repopulate;
    CheckBox cb_override;
    TextView gui_acc_perm, gui_acc_serv, gui_overlay_perm, gui_overlay_serv;

    EditText et_override;
    Button bt_override;
    LinearLayout boss_override;

    Spinner sp_mouse_icon;
    SeekBar dsbar_mouse_size;
    TextView cursor_size_label;
    ImageView cursor_preview;
    CheckBox density_enabled;
    EditText density_dpi, density_key;
    private boolean updatingDensityControls;
    private boolean updatingBossControls;
    private int capturedDensityKey = -1;

    public static int ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE = 701;
    public static int ACTION_ACCESSIBILITY_PERMISSION_REQUEST_CODE = 702;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gui);
        gui_acc_perm = findViewById(R.id.gui_acc_perm);
        gui_acc_serv = findViewById(R.id.gui_acc_serv);
        gui_overlay_perm = findViewById(R.id.gui_overlay_perm);
        gui_overlay_serv = findViewById(R.id.gui_overlay_serv);
        boss_override = findViewById(R.id.boss_override);
        bt_override = findViewById(R.id.bt_override);
        et_override = findViewById(R.id.et_override);
        et_override.setHint(getString(R.string.activation_key_hint, Helper.DEFAULT_BOSS_KEY));
        cb_override = findViewById(R.id.cb_override);
        sp_mouse_icon = findViewById(R.id.sp_mouse_icon);
        dsbar_mouse_size = findViewById(R.id.dsbar_mouse_size);
        dsbar_mouse_size.setMax(CursorAppearance.MAX_SIZE_STEP);
        dsbar_mouse_size.setKeyProgressIncrement(1);
        cursor_size_label = findViewById(R.id.cursor_size_label);
        cursor_preview = findViewById(R.id.cursor_preview);

        // render icon style dropdown
        IconStyleSpinnerAdapter iconStyleSpinnerAdapter = new IconStyleSpinnerAdapter(this, R.layout.spinner_icon_text_gui, R.id.textView, IconStyleSpinnerAdapter.getResourceList());
        sp_mouse_icon.setAdapter(iconStyleSpinnerAdapter);

        checkValues(iconStyleSpinnerAdapter);
        updateCursorPreview();
        showBossLayout(cb_override.isChecked());
        cb_override.setOnCheckedChangeListener((compoundButton, b) -> {
            if (updatingBossControls) return;
            int proposedKey = b ? Helper.getOverrideValue(this) : Helper.DEFAULT_BOSS_KEY;
            if (Helper.isDensityEnabled(this) && proposedKey == Helper.getDensityKey(this)) {
                Toast.makeText(this, R.string.density_key_conflict, Toast.LENGTH_LONG).show();
                updatingBossControls = true;
                cb_override.setChecked(Helper.isOverriding(this));
                updatingBossControls = false;
                return;
            }
            showBossLayout(b);
            Helper.setOverrideStatus(this, b);
        });

        bt_override.setOnClickListener(view -> {
            String dat = et_override.getText().toString();
            dat = dat.replaceAll("[^0-9]", "");
            int keyValue;
            try {
                keyValue = dat.isEmpty() ? Helper.DEFAULT_BOSS_KEY : Integer.parseInt(dat);
            } catch (NumberFormatException exception) {
                Toast.makeText(this, R.string.invalid_activation_key, Toast.LENGTH_SHORT).show();
                return;
            }
            if (cb_override.isChecked() && Helper.isDensityEnabled(this)
                    && keyValue == Helper.getDensityKey(this)) {
                Toast.makeText(this, R.string.density_key_conflict, Toast.LENGTH_LONG).show();
                return;
            }
            Helper.setOverrideStatus(this, cb_override.isChecked());
            Helper.setOverrideValue(this, keyValue);
            Toast.makeText(this, "New key is : "+keyValue, Toast.LENGTH_SHORT).show();
        });

        sp_mouse_icon.setOnItemSelectedListener(new OnItemSelectedListener() {
            // the listener is set after setting initial value to avoid echo if any
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int pos, long id) {
                Helper.setMouseIconPref(GuiActivity.this, iconStyleSpinnerAdapter.getItem(pos));
                updateCursorPreview();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });

        dsbar_mouse_size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Helper.setMouseSizePref(GuiActivity.this, progress);
                    updateCursorPreview();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        setupDensityControls();
        populateText();
        findViewById(R.id.gui_setup_perm).setOnClickListener(view -> askPermissions());
    }

    private void setupDensityControls() {
        density_enabled = findViewById(R.id.density_enabled);
        density_dpi = findViewById(R.id.density_dpi);
        density_key = findViewById(R.id.density_key);
        density_enabled.setChecked(Helper.isDensityEnabled(this));
        density_dpi.setText(String.valueOf(Helper.getCompactDpi(this)));
        int key = Helper.getDensityKey(this);
        if (key > 0) density_key.setText(String.valueOf(key));
        TextView range = findViewById(R.id.density_range);
        range.setText(getString(R.string.density_range, DensitySettings.MIN_DPI, Helper.factoryDpiHint() - 1));
        density_enabled.setOnCheckedChangeListener((button, checked) -> {
            if (updatingDensityControls) return;
            if (!checked) {
                Helper.setDensityEnabled(this, false);
            } else if (!saveDensitySettings()) {
                updatingDensityControls = true;
                density_enabled.setChecked(false);
                updatingDensityControls = false;
            }
        });
        findViewById(R.id.density_save).setOnClickListener(view -> {
            if (saveDensitySettings()) Toast.makeText(this, R.string.density_saved, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.density_detect).setOnClickListener(view -> {
            if (Helper.isMouseModeEnabled()) {
                Toast.makeText(this, R.string.density_stop_mouse, Toast.LENGTH_LONG).show();
                return;
            }
            capturedDensityKey = -1;
            Helper.setCapturingDensityKey(true);
            Toast.makeText(this, R.string.density_press_key, Toast.LENGTH_LONG).show();
        });
    }

    private boolean saveDensitySettings() {
        try {
            String dpiText = density_dpi.getText().toString().trim();
            String keyText = density_key.getText().toString().trim();
            int dpi = dpiText.isEmpty() ? DensitySettings.DEFAULT_DPI : Integer.parseInt(dpiText);
            int key = keyText.isEmpty() ? Helper.defaultDensityKey() : Integer.parseInt(keyText);
            if (!DensitySettings.isValidDpi(dpi, Helper.factoryDpiHint())) {
                density_dpi.setError(getString(R.string.density_range, DensitySettings.MIN_DPI,
                        Helper.factoryDpiHint() - 1));
                return false;
            }
            if ((density_enabled.isChecked() || key != 0)
                    && !DensitySettings.isKeyAllowed(key, Helper.getActivationKey(this))) {
                density_key.setError(getString(R.string.density_key_conflict));
                return false;
            }
            density_dpi.setError(null);
            density_key.setError(null);
            Helper.saveDensitySettings(this, density_enabled.isChecked(), dpi, key);
            return true;
        } catch (NumberFormatException exception) {
            Toast.makeText(this, R.string.invalid_activation_key, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (Helper.isCapturingDensityKey()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                capturedDensityKey = event.getKeyCode();
                if (capturedDensityKey != KeyEvent.KEYCODE_BACK) {
                    density_key.setText(String.valueOf(capturedDensityKey));
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == capturedDensityKey) {
                Helper.setCapturingDensityKey(false);
                capturedDensityKey = -1;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void checkValues(IconStyleSpinnerAdapter adapter) {
        Context ctx = getApplicationContext();
        String val = String.valueOf(Helper.getOverrideValue(ctx));
        et_override.setText(val);
        if (Helper.isOverriding(ctx)) {
            cb_override.setChecked(true);
        }
        String iconStyle = Helper.getMouseIconPref(ctx);
        sp_mouse_icon.setSelection(adapter.getPosition(iconStyle));

        int mouseSize = Helper.getMouseSizePref(ctx);
        dsbar_mouse_size.setProgress(mouseSize);
    }

    private void updateCursorPreview() {
        int sizeStep = Helper.getMouseSizePref(this);
        cursor_size_label.setText(getString(R.string.cursor_size_label, CursorAppearance.heightPx(sizeStep)));
        cursor_preview.setImageDrawable(new CursorDrawable(sizeStep, Helper.getMouseIconPref(this)));
    }

    private void showBossLayout(boolean status) {
        if (status) boss_override.setVisibility(View.VISIBLE);
        else boss_override.setVisibility(View.GONE);
    }


    private void askPermissions() {
        if (Helper.isOverlayDisabled(this)) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                        ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE);
            } catch (Exception unused) {
                Toast.makeText(this, "Overlay Permission Handler not Found", Toast.LENGTH_SHORT).show();
            }
        }
        if (!Helper.isOverlayDisabled(this) && Helper.isAccessibilityDisabled(this)) {
            checkAccPerms();
        }
    }

    private void checkAccPerms() {
        if (Helper.isAccessibilityDisabled(this))
            try {
                startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        ACTION_ACCESSIBILITY_PERMISSION_REQUEST_CODE);
            } catch (Exception exception) {
                Toast.makeText(this, "Acessibility Handler not Found", Toast.LENGTH_SHORT).show();
            }
    }

    public void populateText() {
        if (Helper.isOverlayDisabled(this))  gui_overlay_perm.setText(R.string.perm_overlay_denied);
        else gui_overlay_perm.setText(R.string.perm_overlay_allowed);

        if (Helper.isAccessibilityDisabled(this)) {
            gui_acc_perm.setText(R.string.perm_acc_denied);
            gui_acc_serv.setText(R.string.serv_acc_denied);
            gui_overlay_serv.setText(R.string.serv_overlay_denied); }
        else gui_acc_perm.setText(R.string.perm_acc_allowed);

        if (Helper.isAccessibilityDisabled(this) && Helper.isOverlayDisabled(this)) {
            gui_acc_perm.setText(R.string.perm_acc_denied);
            gui_acc_serv.setText(R.string.serv_acc_denied);
            gui_overlay_perm.setText(R.string.perm_overlay_denied);
            gui_overlay_serv.setText(R.string.serv_overlay_denied);
        }

        if (!Helper.isAccessibilityDisabled(this) && !Helper.isOverlayDisabled(this)) {
            gui_acc_perm.setText(R.string.perm_acc_allowed);
            gui_acc_serv.setText(R.string.serv_acc_allowed);
            gui_overlay_perm.setText(R.string.perm_overlay_allowed);
            gui_overlay_serv.setText(R.string.serv_overlay_allowed);
            findViewById(R.id.gui_setup_perm).setVisibility(View.GONE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE)
            if (Helper.isOverlayDisabled(this)) {
                Toast.makeText(this, "Overlay Permissions Denied", Toast.LENGTH_SHORT).show();
            } else checkAccPerms();
        if (requestCode == ACTION_ACCESSIBILITY_PERMISSION_REQUEST_CODE)
            if (Helper.isAccessibilityDisabled(this)) {
                Toast.makeText(this, "Accessibility Services not running", Toast.LENGTH_SHORT).show();
            }
    }

    @Override
    protected void onResume() {
        super.onResume();
        //Checking services status
        checkServiceStatus();

    }

    private void checkServiceStatus() {
        if (repopulate != null) repopulate.cancel();
        //checking for changed every 2 sec
        repopulate = new CountDownTimer(2000, 2000) {
            @Override
            public void onTick(long l) { }
            @Override
            public void onFinish() {
                populateText();
                repopulate.start(); //restarting the timer
            }
        };
        repopulate.start();
    }

    @Override
    protected void onPause() {
        Helper.setCapturingDensityKey(false);
        capturedDensityKey = -1;
        if (repopulate != null) repopulate.cancel();
        super.onPause();
    }
}
