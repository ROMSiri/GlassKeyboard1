package dev.glasskeyboard.ime;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings shared by the keyboard service and the settings screen. */
final class Prefs {
    static final String VIBRATE = "vibrate", SOUND = "sound", PREVIEW = "preview", BLUR = "blur",
            AUTO_CAPS = "auto_caps", DOUBLE_SPACE = "double_space", LANGUAGE = "language",
            OPACITY = "panel_opacity", SUGGESTIONS = "suggestions", AUTOCORRECT = "autocorrect",
            PREDICTIONS = "predictions", LEARN = "learn_words", BOTTOM_OFFSET = "bottom_offset", BLUR_STATUS = "blur_status";

    final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("glass_keyboard", Context.MODE_PRIVATE);
    }

    boolean vibrate() { return sp.getBoolean(VIBRATE, true); }
    boolean sound() { return sp.getBoolean(SOUND, false); }
    boolean preview() { return sp.getBoolean(PREVIEW, true); }
    boolean blur() { return sp.getBoolean(BLUR, true); }
    boolean autoCaps() { return sp.getBoolean(AUTO_CAPS, true); }
    boolean doubleSpace() { return sp.getBoolean(DOUBLE_SPACE, true); }
    boolean suggestions() { return sp.getBoolean(SUGGESTIONS, true); }
    boolean autocorrect() { return sp.getBoolean(AUTOCORRECT, true); }
    boolean predictions() { return sp.getBoolean(PREDICTIONS, true); }
    boolean learnWords() { return sp.getBoolean(LEARN, true); }
    String language() { return sp.getString(LANGUAGE, "ru"); }
    /** Panel tint opacity, percent (ColorOS default is 80). */
    int opacity() { return sp.getInt(OPACITY, 90); }
    /** Extra space under the bottom row, dp. */
    int bottomOffset() { return sp.getInt(BOTTOM_OFFSET, 14); }
    String blurStatus() { return sp.getString(BLUR_STATUS, ""); }

    void setInt(String key, int value) { sp.edit().putInt(key, value).apply(); }
    void setBlurStatus(String status) { sp.edit().putString(BLUR_STATUS, status).apply(); }

    void setLanguage(String lang) { sp.edit().putString(LANGUAGE, lang).apply(); }
    void set(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }
    boolean get(String key, boolean def) { return sp.getBoolean(key, def); }
}
