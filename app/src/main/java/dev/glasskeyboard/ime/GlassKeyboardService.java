package dev.glasskeyboard.ime;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The input method: wires {@link KeyboardView} to the editor, keeps the blur bound and runs
 * word suggestions (composing text, autocorrect on space with undo by backspace, next-word
 * prediction, learning from typed words).
 */
public final class GlassKeyboardService extends InputMethodService implements KeyboardView.Actions {
    private static final String TAG = "GlassKeyboard";
    private static final String SEPARATORS_AUTOCORRECT = " .,!?;:)\"»\n";

    private Prefs prefs;
    private KeyboardView view;
    private PanelBlur blur;
    private long lastSpaceAt;

    // ---- suggestions
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Map<String, WordDictionary> dictionaries = new HashMap<>();
    private final Map<String, UserDictionary> userDictionaries = new HashMap<>();
    private final Suggest suggest = new Suggest();
    private String suggestLayoutLang;
    private boolean wordMode;                     // suggestions/composing enabled for this field
    private boolean learn;
    private final StringBuilder composing = new StringBuilder();
    private Suggest.Result current;               // result shown for the composing word
    private String[] predictions;                 // next-word slots shown after a space
    private String prevWord;                      // last committed word, for predictions / learning
    // Undo of the last autocorrect: backspace right after restores the typed word.
    private String undoTyped, undoCorrected, undoSeparator;
    // A suggestion pick adds a space; punctuation typed next replaces it ("word ," → "word, ").
    private boolean spaceFromPick;

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        blur = new PanelBlur((mode, detail) -> {
            String name = mode == PanelBlur.Mode.OPLUS ? "системный материал ColorOS"
                    : mode == PanelBlur.Mode.WINDOW ? "размытие окна Android" : "без размытия";
            prefs.setBlurStatus(detail == null || detail.isEmpty() ? name : name + " (" + detail + ")");
            if (view != null) view.onBlurModeChanged(mode, detail);
        });
        ensureDictionary(prefs.language());
    }

    @Override public View onCreateInputView() {
        view = new KeyboardView(this, prefs, this);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { bindBlur(); }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> blur.refresh());
        return view;
    }

    private void bindBlur() {
        if (view == null || getWindow() == null) return;
        Window window = getWindow().getWindow();
        if (window == null) return;
        blur.setOpacity(prefs.opacity());
        blur.setEnabled(prefs.blur());
        blur.bind(window, view, view.isDark(), view.panelRadiusPx());
        view.onBlurModeChanged(blur.mode(), ""); // a recreated view starts without the current mode
    }

    @Override public boolean onEvaluateFullscreenMode() {
        return false; // keep the glass panel in landscape too
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (view == null) return;
        view.resetState();
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        view.setSymbolsMode(cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME);
        boolean noAction = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        view.setEnterAction(noAction ? EditorInfo.IME_ACTION_UNSPECIFIED : info.imeOptions & EditorInfo.IME_MASK_ACTION);
        lastSpaceAt = 0;

        boolean sensitive = isSensitive(info);
        wordMode = prefs.suggestions() && cls == InputType.TYPE_CLASS_TEXT && !sensitive;
        learn = wordMode && prefs.learnWords()
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        composing.setLength(0);
        current = null;
        predictions = null;
        prevWord = null;
        clearUndo();
        spaceFromPick = false;
        if (wordMode) ensureDictionary(view.language());
        showStrip();

        view.post(this::bindBlur);
        updateShift();
    }

    private static boolean isSensitive(EditorInfo info) {
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI;
    }

    @Override public void onWindowShown() {
        super.onWindowShown();
        // The surface exists only now; re-submit the material after the first frame.
        if (view != null) view.post(() -> { bindBlur(); blur.refresh(); });
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        finishComposing();
        super.onFinishInputView(finishingInput);
        if (view != null) view.resetState();
        saveUserDictionaries();
    }

    @Override public void onFinishInput() {
        finishComposing();
        super.onFinishInput();
    }

    @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (composing.length() > 0 && (candidatesEnd < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // The user moved the cursor away from the word being typed.
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            composing.setLength(0);
            current = null;
            predictions = null;
            prevWord = null;
            clearUndo();
            showStrip();
        } else if (composing.length() == 0 && newSelStart != newSelEnd && predictions != null) {
            predictions = null; // text selected: tools are more useful than predictions
            showStrip();
        }
        updateShift();
    }

    @Override public void onDestroy() {
        if (blur != null) blur.release();
        saveUserDictionaries();
        loader.shutdown();
        super.onDestroy();
    }

    private void updateShift() {
        if (view == null || !prefs.autoCaps()) return;
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return;
        if (composing.length() > 0) return; // mid-word: shift was already consumed
        int type = info.inputType;
        if ((type & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) { view.setAutoShift(false); return; }
        int variation = type & InputType.TYPE_MASK_VARIATION;
        boolean plain = variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_URI
                && variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_FILTER;
        if (!plain) { view.setAutoShift(false); return; }
        view.setAutoShift(ic.getCursorCapsMode(type | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0);
    }

    // ------------------------------------------------------------------ dictionaries

    private void ensureDictionary(String lang) {
        if (lang == null || dictionaries.containsKey(lang)) return;
        dictionaries.put(lang, null); // loading
        loader.execute(() -> {
            WordDictionary dict = null;
            UserDictionary user = UserDictionary.load(this, lang);
            try {
                long t = SystemClock.uptimeMillis();
                dict = WordDictionary.load(this, lang);
                Log.i(TAG, "dictionary " + lang + ": " + dict.size() + " words, "
                        + (SystemClock.uptimeMillis() - t) + " ms");
            } catch (Throwable error) {
                Log.w(TAG, "dictionary " + lang + " failed", error);
            }
            WordDictionary loaded = dict;
            main.post(() -> {
                dictionaries.put(lang, loaded);
                userDictionaries.put(lang, user);
                if (composing.length() > 0) updateSuggestions();
            });
        });
    }

    private String lang() { return view != null ? view.language() : prefs.language(); }
    private WordDictionary dict() { return dictionaries.get(lang()); }
    private UserDictionary user() { return userDictionaries.get(lang()); }

    private void saveUserDictionaries() {
        for (UserDictionary u : userDictionaries.values()) {
            if (u == null) continue;
            loader.execute(u::save);
        }
    }

    private void learnWord(String word) {
        UserDictionary u = user();
        if (!learn || u == null || word == null || word.isEmpty()) return;
        u.learn(prevWord, word);
    }

    // ------------------------------------------------------------------ strip

    private void updateSuggestions() {
        if (composing.length() == 0) { current = null; showStrip(); return; }
        String lang = lang();
        if (!lang.equals(suggestLayoutLang)) {
            suggest.setLayout("en".equals(lang) ? Layouts.english() : Layouts.russian());
            suggestLayoutLang = lang;
        }
        current = suggest.suggest(composing.toString(), dict(), user(), prefs.autocorrect());
        showStrip();
    }

    private void showStrip() {
        if (view == null) return;
        if (!wordMode) { view.setSuggestions(null, -1, false); return; }
        if (current != null) view.setSuggestions(current.slots, current.literalSlot, current.autocorrect);
        else if (predictions != null) view.setSuggestions(predictions, -1, false);
        else view.setSuggestions(null, -1, false);
    }

    private void showPredictions() {
        predictions = wordMode && prefs.predictions() && prevWord != null
                ? suggest.predict(prevWord, dict(), user()) : null;
        showStrip();
    }

    private void clearUndo() { undoTyped = undoCorrected = undoSeparator = null; }

    private static boolean isWordChar(String text, boolean midWord) {
        if (text.codePointCount(0, text.length()) != 1) return false;
        int cp = text.codePointAt(0);
        if (Character.isLetter(cp)) return true;
        // apostrophe / hyphen only inside a word ("don't", "кто-то")
        return midWord && (cp == '\'' || cp == '’' || cp == '-');
    }

    /** Commits the composing word as typed (no autocorrect). */
    private void finishComposing() {
        if (composing.length() == 0) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.finishComposingText();
        learnWord(composing.toString());
        prevWord = composing.toString();
        composing.setLength(0);
        current = null;
    }

    // ------------------------------------------------------------------ KeyboardView.Actions

    @Override public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long now = SystemClock.uptimeMillis();

        if (wordMode && isWordChar(text, composing.length() > 0)) {
            clearUndo();
            spaceFromPick = false;
            predictions = null;
            composing.append(text);
            ic.setComposingText(composing, 1);
            lastSpaceAt = 0;
            updateSuggestions();
            return;
        }

        ic.beginBatchEdit();
        try {
            if (composing.length() > 0) {
                String typed = composing.toString();
                String finalWord = typed;
                boolean autocorrectHere = SEPARATORS_AUTOCORRECT.contains(text) && current != null
                        && current.autocorrect && current.slots[1] != null;
                if (autocorrectHere) finalWord = current.slots[1];
                ic.commitText(finalWord, 1);
                learnWord(finalWord);
                prevWord = finalWord;
                composing.setLength(0);
                current = null;
                if (!finalWord.equals(typed)) {
                    undoTyped = typed; undoCorrected = finalWord; undoSeparator = text;
                } else clearUndo();
            } else {
                clearUndo();
                // "word␣" from a suggestion pick followed by punctuation → "word, "
                if (spaceFromPick && text.length() == 1 && ".,!?;:".contains(text)) {
                    CharSequence before = ic.getTextBeforeCursor(1, 0);
                    if (before != null && before.length() == 1 && before.charAt(0) == ' ') {
                        ic.deleteSurroundingText(1, 0);
                        ic.commitText(text + " ", 1);
                        spaceFromPick = false;
                        lastSpaceAt = 0;
                        predictions = null;
                        showStrip();
                        return;
                    }
                }
            }
            spaceFromPick = false;

            if (" ".equals(text)) {
                if (prefs.doubleSpace() && now - lastSpaceAt < 600) {
                    CharSequence before = ic.getTextBeforeCursor(2, 0);
                    if (before != null && before.length() == 2 && before.charAt(1) == ' '
                            && Character.isLetterOrDigit(before.charAt(0))) {
                        ic.deleteSurroundingText(1, 0);
                        ic.commitText(". ", 1);
                        lastSpaceAt = 0;
                        prevWord = null;
                        clearUndo();
                        predictions = null;
                        showStrip();
                        return;
                    }
                }
                lastSpaceAt = now;
                ic.commitText(text, 1);
                showPredictions();
            } else {
                lastSpaceAt = 0;
                ic.commitText(text, 1);
                // sentence punctuation breaks the word chain
                if (!"'’-".contains(text)) prevWord = null;
                predictions = null;
                showStrip();
            }
        } finally {
            ic.endBatchEdit();
        }
    }

    @Override public void onDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastSpaceAt = 0;
        spaceFromPick = false;

        // Backspace right after an autocorrect: bring back exactly what was typed.
        if (undoCorrected != null) {
            CharSequence before = ic.getTextBeforeCursor(undoCorrected.length() + undoSeparator.length(), 0);
            if (before != null && before.toString().equals(undoCorrected + undoSeparator)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(before.length(), 0);
                composing.setLength(0);
                composing.append(undoTyped);
                ic.setComposingText(composing, 1);
                ic.endBatchEdit();
                UserDictionary u = user();
                if (learn && u != null) { u.learn(null, undoTyped); u.learn(null, undoTyped); } // keep it next time
                clearUndo();
                current = null;
                predictions = null;
                showStrip(); // show tools, not the rejected correction again
                return;
            }
            clearUndo();
        }

        if (composing.length() > 0) {
            int cut = composing.offsetByCodePoints(composing.length(), -1);
            composing.setLength(cut);
            if (composing.length() == 0) ic.commitText("", 1);
            else ic.setComposingText(composing, 1);
            updateSuggestions();
            return;
        }

        predictions = null;
        prevWord = null;
        showStrip();
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) ic.commitText("", 1);
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
    }

    @Override public void onSuggestion(int slot) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String word = null;
        if (current != null) word = current.slots[slot];
        else if (predictions != null) word = predictions[slot];
        if (word == null) return;
        ic.beginBatchEdit();
        ic.commitText(word, 1);   // replaces the composing text, if any
        ic.commitText(" ", 1);
        ic.endBatchEdit();
        if (current != null && current.literalSlot == slot) {
            UserDictionary u = user(); // the user insists on this spelling
            if (learn && u != null) u.learn(null, word);
        }
        learnWord(word);
        prevWord = word;
        composing.setLength(0);
        current = null;
        clearUndo();
        spaceFromPick = true;
        lastSpaceAt = 0;
        showPredictions();
    }

    @Override public void onEnter() {
        finishComposing();
        clearUndo();
        predictions = null;
        prevWord = null;
        showStrip();
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return;
        lastSpaceAt = 0;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    @Override public void onCursor(int delta) {
        finishComposing();
        clearUndo();
        predictions = null;
        showStrip();
        int code = delta < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        for (int i = 0; i < Math.abs(delta); i++) sendDownUpKeyEvents(code);
    }

    @Override public void onHide() { requestHideSelf(0); }

    @Override public void onPaste() {
        finishComposing();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.performContextMenuAction(android.R.id.paste);
    }

    @Override public void onSettings() {
        requestHideSelf(0);
        Intent intent = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override public void onLanguageChanged(String lang) {
        finishComposing();
        prefs.setLanguage(lang);
        prevWord = null;
        predictions = null;
        if (wordMode) ensureDictionary(lang);
        showStrip();
    }

    @Override public void onPickInputMethod() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showInputMethodPicker();
    }
}
