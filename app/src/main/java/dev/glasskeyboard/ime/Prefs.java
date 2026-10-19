package dev.glasskeyboard.ime;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings shared by the keyboard service and the settings screens. */
final class Prefs {
    // appearance & feedback
    static final String VIBRATE = "vibrate", SOUND = "sound", PREVIEW = "preview", BLUR = "blur", APP_THEME = "app_theme",
            OPACITY = "panel_opacity", BOTTOM_OFFSET = "bottom_offset", KEY_HEIGHT = "key_height",
            ONE_HANDED = "one_handed", LANGUAGE = "language", BLUR_STATUS = "blur_status",
            USER_DICT_REV = "user_dict_rev";
    // text correction
    static final String BLOCK_OFFENSIVE = "block_offensive", AUTOCORRECT = "autocorrect",
            MORE_AUTOCORRECT = "more_autocorrect", AUTOCORRECT_SHORTCUTS = "autocorrect_shortcuts",
            AUTOCORRECT_THRESHOLD = "autocorrect_threshold", BACKSPACE_REVERTS = "backspace_reverts_autocorrect",
            AUTO_CAPS = "auto_caps", DOUBLE_SPACE = "double_space",
            AUTOSPACE_PUNCTUATION = "autospace_after_punctuation", AUTOSPACE_SUGGESTION = "autospace_after_suggestion",
            AUTOSPACE_BEFORE_GESTURE = "autospace_before_gesture", AUTOSPACE_AFTER_GESTURE = "autospace_after_gesture",
            SHIFT_REMOVES_AUTOSPACE = "shift_removes_autospace",
            SUGGESTIONS = "suggestions", ALWAYS_SUGGESTIONS = "always_show_suggestions",
            ALWAYS_SUGGESTIONS_EXCEPT_WEB = "always_show_suggestions_except_web",
            CENTER_SUGGESTION = "center_suggestion_on_space", FIVE_CHIPS = "five_suggestion_chips",
            SUGGEST_EMOJIS = "suggest_emojis", INLINE_EMOJI = "inline_emoji_search",
            LEARN = "learn_words", PREDICTIONS = "predictions",
            SUGGEST_PUNCTUATION = "suggest_punctuation", PUNCTUATION_LIST = "punctuation_list",
            SUGGEST_CLIPBOARD = "suggest_clipboard", USE_CONTACTS = "use_contacts", USE_APPS = "use_apps",
            ADD_TO_SYSTEM_DICT = "add_to_system_dictionary", SPELLCHECK_SUGGEST = "spellcheck_suggest",
            INCOGNITO = "incognito";
    // toolbar
    static final String TOOLBAR_MODE = "toolbar_mode", TOOLBAR_HIDING_GLOBAL = "toolbar_hiding_global",
            TOOLBAR_SWIPE_DOWN = "toolbar_swipe_down_to_hide", TOOLBAR_KEYS = "toolbar_keys",
            PINNED_KEYS = "pinned_toolbar_keys", PERSISTENT_KEY = "persistent_toolbar_key",
            TOOLBAR_CUSTOM_CODES = "toolbar_custom_key_codes", TOOLBAR_RTL = "variable_toolbar_direction";
    static final String MODE_EXPANDABLE = "EXPANDABLE", MODE_KEYS = "TOOLBAR_KEYS",
            MODE_STRIP = "SUGGESTION_STRIP", MODE_HIDDEN = "HIDDEN";
    // gesture typing
    static final String GESTURE = "gesture_input", GESTURE_TRAIL = "gesture_preview_trail",
            GESTURE_FLOATING = "gesture_floating_preview", GESTURE_FLOATING_DYNAMIC = "gesture_floating_preview_dynamic",
            GESTURE_SPACE_AWARE = "gesture_space_aware", GESTURE_COOLDOWN = "gesture_fast_typing_cooldown",
            GESTURE_TRAIL_FADE = "gesture_trail_fadeout";

    static final String DEFAULT_PUNCTUATION = "! ? , . : ; ( ) \" - @";

    final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("glass_keyboard", Context.MODE_PRIVATE);
    }

    boolean get(String key, boolean def) { return sp.getBoolean(key, def); }
    int getInt(String key, int def) { return sp.getInt(key, def); }
    String getString(String key, String def) { return sp.getString(key, def); }
    void set(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }
    void setInt(String key, int value) { sp.edit().putInt(key, value).apply(); }
    void setString(String key, String value) { sp.edit().putString(key, value).apply(); }

    // appearance & feedback
    boolean vibrate() { return get(VIBRATE, true); }
    boolean sound() { return get(SOUND, false); }
    boolean preview() { return get(PREVIEW, true); }
    boolean blur() { return get(BLUR, true); }
    boolean appTheme() { return get(APP_THEME, true); }
    /** Theme the user picked for one app with the toolbar button: TRUE dark, FALSE light, null auto. */
    Boolean themeFor(String pkg) {
        if (pkg == null) return null;
        String v = getString("theme_app_" + pkg, null);
        return v == null ? null : "dark".equals(v);
    }
    void setThemeFor(String pkg, boolean dark) { if (pkg != null) setString("theme_app_" + pkg, dark ? "dark" : "light"); }
    int opacity() { return getInt(OPACITY, 90); }
    int bottomOffset() { return getInt(BOTTOM_OFFSET, 14); }
    int keyHeight() { return getInt(KEY_HEIGHT, 100); }          // percent
    int oneHanded() { return getInt(ONE_HANDED, 0); }             // 0 off, 1 right, 2 left
    String language() { return getString(LANGUAGE, "ru"); }
    void setLanguage(String lang) { setString(LANGUAGE, lang); }
    String blurStatus() { return getString(BLUR_STATUS, ""); }
    void setBlurStatus(String status) { setString(BLUR_STATUS, status); }

    // text correction
    boolean blockOffensive() { return get(BLOCK_OFFENSIVE, true); }
    boolean autocorrect() { return get(AUTOCORRECT, true); }
    boolean moreAutocorrect() { return get(MORE_AUTOCORRECT, true); }
    boolean autocorrectShortcuts() { return get(AUTOCORRECT_SHORTCUTS, true); }
    int autocorrectThreshold() { return getInt(AUTOCORRECT_THRESHOLD, 0); }
    boolean backspaceReverts() { return get(BACKSPACE_REVERTS, true); }
    boolean autoCaps() { return get(AUTO_CAPS, true); }
    boolean doubleSpace() { return get(DOUBLE_SPACE, true); }
    boolean autospacePunctuation() { return get(AUTOSPACE_PUNCTUATION, false); }
    boolean autospaceSuggestion() { return get(AUTOSPACE_SUGGESTION, true); }
    boolean autospaceBeforeGesture() { return get(AUTOSPACE_BEFORE_GESTURE, true); }
    boolean autospaceAfterGesture() { return get(AUTOSPACE_AFTER_GESTURE, true); }
    boolean shiftRemovesAutospace() { return get(SHIFT_REMOVES_AUTOSPACE, false); }
    boolean suggestions() { return get(SUGGESTIONS, true); }
    boolean alwaysSuggestions() { return get(ALWAYS_SUGGESTIONS, false); }
    boolean alwaysSuggestionsExceptWeb() { return get(ALWAYS_SUGGESTIONS_EXCEPT_WEB, true); }
    boolean centerSuggestion() { return get(CENTER_SUGGESTION, false); }
    boolean fiveChips() { return get(FIVE_CHIPS, false); }
    boolean suggestEmojis() { return get(SUGGEST_EMOJIS, true); }
    boolean inlineEmoji() { return get(INLINE_EMOJI, true); }
    boolean learnWords() { return get(LEARN, true); }
    boolean predictions() { return get(PREDICTIONS, true); }
    boolean suggestPunctuation() { return get(SUGGEST_PUNCTUATION, false); }
    String punctuationList() { return getString(PUNCTUATION_LIST, DEFAULT_PUNCTUATION); }
    boolean suggestClipboard() { return get(SUGGEST_CLIPBOARD, true); }
    boolean useContacts() { return get(USE_CONTACTS, false); }
    boolean useApps() { return get(USE_APPS, false); }
    boolean addToSystemDict() { return get(ADD_TO_SYSTEM_DICT, false); }
    boolean spellcheckSuggest() { return get(SPELLCHECK_SUGGEST, true); }
    boolean incognito() { return get(INCOGNITO, false); }

    // toolbar
    String toolbarMode() { return getString(TOOLBAR_MODE, MODE_EXPANDABLE); }
    boolean toolbarSwipeDown() { return get(TOOLBAR_SWIPE_DOWN, false); }
    String toolbarKeys() { return getString(TOOLBAR_KEYS, ToolKey.defaultPref()); }
    String pinnedKeys() { return getString(PINNED_KEYS, ""); }
    String persistentKey() { return getString(PERSISTENT_KEY, ToolKey.DEFAULT_PERSISTENT.name()); }

    // gesture typing
    boolean gesture() { return get(GESTURE, true); }
    boolean gestureTrail() { return get(GESTURE_TRAIL, true); }
    boolean gestureFloating() { return get(GESTURE_FLOATING, true); }
    boolean gestureFloatingDynamic() { return get(GESTURE_FLOATING_DYNAMIC, true); }
    boolean gestureSpaceAware() { return get(GESTURE_SPACE_AWARE, false); }
    int gestureCooldown() { return getInt(GESTURE_COOLDOWN, 500); }
    int gestureTrailFade() { return getInt(GESTURE_TRAIL_FADE, 800); }

    Suggest.Options suggestOptions() {
        Suggest.Options o = new Suggest.Options();
        o.autocorrect = autocorrect();
        o.threshold = autocorrectThreshold();
        o.blockOffensive = blockOffensive();
        o.shortcuts = autocorrectShortcuts();
        o.chips = fiveChips() ? 5 : 3;
        o.emojis = suggestEmojis();
        return o;
    }
}
