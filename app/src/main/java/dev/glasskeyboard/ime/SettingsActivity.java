package dev.glasskeyboard.ime;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Setup steps, a few toggles and a test field. Built in code: no extra libraries. */
public final class SettingsActivity extends Activity {
    private TextView status, blurStatus;
    private Prefs prefs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad + dp(24), pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView about = new TextView(this);
        about.setText(R.string.settings_about);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        about.setPadding(0, dp(8), 0, dp(16));
        root.addView(about);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status);

        Button enable = new Button(this);
        enable.setText(R.string.step_enable);
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable, matchWrap());

        Button choose = new Button(this);
        choose.setText(R.string.step_choose);
        choose.setOnClickListener(v -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showInputMethodPicker();
        });
        root.addView(choose, matchWrap());

        TextView options = new TextView(this);
        options.setText(R.string.settings_options);
        options.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        options.setAllCaps(true);
        options.setPadding(0, dp(24), 0, dp(4));
        root.addView(options);

        addSwitch(root, R.string.opt_blur, Prefs.BLUR, true);
        addSwitch(root, R.string.opt_preview, Prefs.PREVIEW, true);
        addSwitch(root, R.string.opt_vibrate, Prefs.VIBRATE, true);
        addSwitch(root, R.string.opt_sound, Prefs.SOUND, false);
        addSwitch(root, R.string.opt_auto_caps, Prefs.AUTO_CAPS, true);
        addSwitch(root, R.string.opt_double_space, Prefs.DOUBLE_SPACE, true);

        addSlider(root, R.string.opt_opacity, Prefs.OPACITY, prefs.opacity(), 50, 100, "%");
        addSlider(root, R.string.opt_bottom, Prefs.BOTTOM_OFFSET, prefs.bottomOffset(), 0, 48, " dp");

        blurStatus = new TextView(this);
        blurStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        blurStatus.setPadding(0, dp(12), 0, 0);
        root.addView(blurStatus);

        TextView blurNote = new TextView(this);
        blurNote.setText(R.string.blur_note);
        blurNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        blurNote.setAlpha(.7f);
        blurNote.setPadding(0, dp(8), 0, dp(20));
        root.addView(blurNote);

        EditText test = new EditText(this);
        test.setHint(R.string.test_hint);
        test.setMinLines(3);
        test.setGravity(Gravity.TOP | Gravity.START);
        root.addView(test, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateStatus(); // after the input method picker closes
    }

    private void updateStatus() {
        boolean enabled = false;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            for (InputMethodInfo info : imm.getEnabledInputMethodList())
                if (getPackageName().equals(info.getPackageName())) enabled = true;
        }
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");
        String mode = prefs.blurStatus();
        blurStatus.setText(getString(R.string.blur_status, mode.isEmpty() ? getString(R.string.blur_unknown) : mode));
        status.setText(selected ? R.string.status_ready : enabled ? R.string.status_enabled : R.string.status_disabled);
    }

    private void addSwitch(LinearLayout root, int label, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.get(key, def));
        s.setPadding(0, dp(10), 0, dp(10));
        s.setOnCheckedChangeListener((b, checked) -> prefs.set(key, checked));
        root.addView(s, matchWrap());
    }

    private void addSlider(LinearLayout root, int label, String key, int value, int min, int max, String unit) {
        TextView title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setPadding(0, dp(12), 0, 0);
        String name = getString(label);
        title.setText(name + ": " + value + unit);
        root.addView(title);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                title.setText(name + ": " + (progress + min) + unit);
                if (fromUser) prefs.setInt(key, progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        root.addView(bar, matchWrap());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
