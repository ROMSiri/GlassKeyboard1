package dev.glasskeyboard.ime;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings shared by the keyboard service and the settings screen. */
final class Prefs {
    static final String VIBRATE = "vibrate", SOUND = "sound", PREVIEW = "preview", BLUR = "blur",
            AUTO_CAPS = "auto_caps", DOUBLE_SPACE = "double_space", LANGUAGE = "language";

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
    String language() { return sp.getString(LANGUAGE, "ru"); }

    void setLanguage(String lang) { sp.edit().putString(LANGUAGE, lang).apply(); }
    void set(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }
    boolean get(String key, boolean def) { return sp.getBoolean(key, def); }
}
