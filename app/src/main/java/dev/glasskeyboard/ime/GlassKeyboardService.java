package dev.glasskeyboard.ime;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The input method: wires {@link KeyboardView} to the editor, keeps the blur bound and runs
 * word suggestions, autocorrect, gesture typing and the toolbar actions.
 */
public final class GlassKeyboardService extends InputMethodService implements KeyboardView.Actions {
    private static final String TAG = "GlassKeyboard";
    private static final String SEPARATORS_AUTOCORRECT = " .,!?;:)\"»\n";
    private static final String SPACE_AFTER = ".,!?;:";

    private Prefs prefs;
    private KeyboardView view;
    private PanelBlur blur;
    private long lastSpaceAt;

    // ---- dictionaries
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Map<String, WordDictionary> dictionaries = new HashMap<>();
    private final Map<String, UserDictionary> userDictionaries = new HashMap<>();
    private final Map<String, EmojiDictionary> emojiDictionaries = new HashMap<>();
    private ExtraWords extraWords;
    private String extraWordsKey;
    private int userDictRev = -1;
    private final Suggest suggest = new Suggest();
    private final GestureDecoder gestures = new GestureDecoder();
    private String suggestLayoutLang;
    private Suggest.Options options = new Suggest.Options();

    // ---- typing state
    private boolean wordMode;                     // suggestions/composing enabled for this field
    private boolean learn;
    private boolean autocorrectHere;              // autocorrect allowed in this field
    private final StringBuilder composing = new StringBuilder();
    private boolean gestureWord;                  // composing word came from a gesture
    private boolean pendingGestureSpace;          // put a space before the next word
    private Suggest.Result current;               // result shown for the composing word
    private String[] gestureSlots;                // alternatives for a gesture word
    private String[] predictions;                 // next-word slots shown after a space
    private String[] idleSlots;                   // punctuation / clipboard when nothing else
    private boolean idleIsClipboard;
    private String clipText;
    private long clipShownFor;
    private String prevWord;                      // last committed word, for predictions / learning
    private String undoTyped, undoCorrected, undoSeparator;
    private boolean autoSpaceInserted;            // the last char is a space we added ourselves
    private String recorrectWord;                 // word under the cursor being re-corrected
    private int recorrectBefore, recorrectAfter;
    private long lastEditAt;
    private ClipboardManager clipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = this::onClipChanged;

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
        clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);
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

        options = prefs.suggestOptions();
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        boolean noSuggestionsFlag = (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0;
        boolean web = variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
        boolean forced = prefs.alwaysSuggestions() && !(web && prefs.alwaysSuggestionsExceptWeb());
        wordMode = prefs.suggestions() && cls == InputType.TYPE_CLASS_TEXT && !isSensitive(info)
                && (!noSuggestionsFlag || forced);
        learn = wordMode && prefs.learnWords() && !prefs.incognito()
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        boolean fieldWantsCorrection = (info.inputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0;
        autocorrectHere = wordMode && options.autocorrect && (prefs.moreAutocorrect() || fieldWantsCorrection);
        options.autocorrect = autocorrectHere;

        resetTyping();
        if (wordMode) {
            ensureDictionary(view.language());
            reloadUserDictionariesIfChanged();
            ensureExtraWords();
        }
        view.setToolState(ToolKey.INCOGNITO, prefs.incognito());
        view.setToolState(ToolKey.AUTOCORRECT, prefs.autocorrect());
        view.setToolState(ToolKey.ONE_HANDED, prefs.oneHanded() != 0);
        checkClipboard();
        showStrip();

        view.post(this::bindBlur);
        updateShift();
    }

    private void resetTyping() {
        composing.setLength(0);
        gestureWord = false;
        pendingGestureSpace = false;
        current = null;
        gestureSlots = null;
        predictions = null;
        prevWord = null;
        recorrectWord = null;
        autoSpaceInserted = false;
        clearUndo();
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
        boolean ours = SystemClock.uptimeMillis() - lastEditAt < 300;
        if (composing.length() > 0 && (candidatesEnd < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // The user moved the cursor away from the word being typed.
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            composing.setLength(0);
            gestureWord = false;
            current = null;
            gestureSlots = null;
            predictions = null;
            prevWord = null;
            clearUndo();
            showStrip();
        } else if (composing.length() == 0 && !ours) {
            autoSpaceInserted = false;
            pendingGestureSpace = false;
            clearUndo();
            if (newSelStart != newSelEnd) {
                predictions = null;
                recorrectWord = null;
                showStrip();
            } else if (oldSelStart != newSelStart) {
                predictions = null;
                prevWord = null;
                tryRecorrect();
            }
        }
        updateShift();
    }

    @Override public void onDestroy() {
        if (blur != null) blur.release();
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipListener);
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
            EmojiDictionary emoji = EmojiDictionary.load(this, lang);
            try {
                long t = SystemClock.uptimeMillis();
                dict = DictionaryCache.get(this, lang);
                Log.i(TAG, "dictionary " + lang + ": " + dict.size() + " words, "
                        + (SystemClock.uptimeMillis() - t) + " ms");
            } catch (Throwable error) {
                Log.w(TAG, "dictionary " + lang + " failed", error);
            }
            WordDictionary loaded = dict;
            main.post(() -> {
                dictionaries.put(lang, loaded);
                userDictionaries.put(lang, user);
                emojiDictionaries.put(lang, emoji);
                if (composing.length() > 0) updateSuggestions();
            });
        });
    }

    private void reloadUserDictionariesIfChanged() {
        int rev = prefs.getInt(Prefs.USER_DICT_REV, 0);
        if (rev == userDictRev) return;
        boolean first = userDictRev == -1;
        userDictRev = rev;
        if (first) return;
        // edited in settings: drop in-memory copies (unsaved learning merges on next load)
        for (String lang : new ArrayList<>(userDictionaries.keySet())) {
            loader.execute(() -> {
                UserDictionary u = UserDictionary.load(this, lang);
                main.post(() -> userDictionaries.put(lang, u));
            });
        }
    }

    private void ensureExtraWords() {
        boolean contacts = prefs.useContacts(), apps = prefs.useApps();
        String key = contacts + "/" + apps + "/" + prefs.getInt(Prefs.USER_DICT_REV, 0);
        if (key.equals(extraWordsKey)) return;
        extraWordsKey = key;
        loader.execute(() -> {
            ExtraWords w = ExtraWords.load(this, contacts, apps);
            main.post(() -> extraWords = w);
        });
    }

    private String lang() { return view != null ? view.language() : prefs.language(); }
    private WordDictionary dict() { return dictionaries.get(lang()); }
    private UserDictionary user() { return userDictionaries.get(lang()); }
    private EmojiDictionary emoji() { return emojiDictionaries.get(lang()); }

    private void saveUserDictionaries() {
        for (UserDictionary u : userDictionaries.values()) {
            if (u == null) continue;
            loader.execute(u::save);
        }
    }

    private void learnWord(String word) {
        UserDictionary u = user();
        if (!learn || u == null || word == null || word.isEmpty() || word.startsWith(":")) return;
        boolean wasKnown = u.count(word.toLowerCase()) > 0;
        u.learn(prevWord, word);
        if (prefs.addToSystemDict() && !wasKnown && u.count(word.toLowerCase()) >= 2) {
            WordDictionary d = dict();
            if (d == null || d.frequencyOf(word.toLowerCase()) < 0)
                loader.execute(() -> ExtraWords.addToSystem(this, word, lang()));
        }
    }

    // ------------------------------------------------------------------ strip

    private void ensureSuggestLayout() {
        String lang = lang();
        if (!lang.equals(suggestLayoutLang)) {
            suggest.setLayout("en".equals(lang) ? Layouts.english() : Layouts.russian());
            suggestLayoutLang = lang;
        }
    }

    private void updateSuggestions() {
        recorrectWord = null;
        if (composing.length() == 0) { current = null; showStrip(); return; }
        if (gestureWord) { showStrip(); return; }
        String typed = composing.toString();
        if (typed.startsWith(":")) {
            current = null;
            String[] e = Suggest.emojiSlots(emoji(), typed.substring(1), options.chips);
            gestureSlots = e; // shown like alternatives, tapping inserts the emoji
            showStrip();
            return;
        }
        ensureSuggestLayout();
        current = suggest.suggest(typed, dict(), user(), extraWords, emoji(), options);
        showStrip();
    }

    private void showStrip() {
        if (view == null) return;
        if (!wordMode) { view.setSuggestions(idleSlots(), -1, false); return; }
        if (gestureSlots != null) view.setSuggestions(gestureSlots, -1, false);
        else if (current != null) view.setSuggestions(current.slots, current.literalSlot, current.autocorrect);
        else if (predictions != null) view.setSuggestions(predictions, -1, false);
        else view.setSuggestions(idleSlots(), -1, false);
    }

    /** Clipboard text or punctuation when there is nothing else to show. */
    private String[] idleSlots() {
        idleIsClipboard = false;
        idleSlots = null;
        if (composing.length() > 0) return null;
        if (clipText != null && prefs.suggestClipboard()) {
            idleIsClipboard = true;
            String shown = clipText.replace('\n', ' ');
            if (shown.length() > 40) shown = shown.substring(0, 40) + "…";
            idleSlots = new String[options.chips];
            idleSlots[options.chips / 2] = "📋 " + shown;
            return idleSlots;
        }
        if (prefs.suggestPunctuation()) {
            List<String> marks = new ArrayList<>();
            for (String m : prefs.punctuationList().trim().split("\\s+")) if (!m.isEmpty()) marks.add(m);
            if (!marks.isEmpty()) {
                int n = Math.max(options.chips, Math.min(marks.size(), 7));
                idleSlots = new String[n];
                for (int i = 0; i < n && i < marks.size(); i++) idleSlots[i] = marks.get(i);
                return idleSlots;
            }
        }
        return null;
    }

    private void showPredictions() {
        predictions = wordMode && prefs.predictions() && prevWord != null
                ? suggest.predict(prevWord, dict(), user(), options.chips) : null;
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
        if (!composing.toString().startsWith(":")) {
            learnWord(composing.toString());
            prevWord = composing.toString();
        }
        composing.setLength(0);
        gestureWord = false;
        gestureSlots = null;
        current = null;
    }

    private void markEdit() { lastEditAt = SystemClock.uptimeMillis(); }

    // ------------------------------------------------------------------ clipboard suggestion

    private void onClipChanged() { checkClipboard(); showStrip(); }

    private void checkClipboard() {
        clipText = null;
        if (clipboard == null || !prefs.suggestClipboard()) return;
        try {
            ClipData clip = clipboard.getPrimaryClip();
            ClipDescription d = clipboard.getPrimaryClipDescription();
            if (clip == null || clip.getItemCount() == 0 || d == null) return;
            long ts = Build.VERSION.SDK_INT >= 26 ? d.getTimestamp() : 0;
            if (ts != 0 && System.currentTimeMillis() - ts > 60_000) return; // only fresh copies
            if (ts != 0 && ts == clipShownFor) return;                      // already used
            CharSequence t = clip.getItemAt(0).coerceToText(this);
            if (t == null || t.toString().trim().isEmpty()) return;
            clipText = t.toString();
            clipShownFor = ts;
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ re-correction

    /** Cursor placed in or right after a word: offer alternatives to replace it. */
    private void tryRecorrect() {
        recorrectWord = null;
        if (view == null) return;
        if (!wordMode || !prefs.spellcheckSuggest()) { showStrip(); return; }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(40, 0), after = ic.getTextAfterCursor(40, 0);
        if (before == null || after == null) { showStrip(); return; }
        int b = before.length(), a = 0;
        while (b > 0 && isWordLetter(before.charAt(b - 1))) b--;
        while (a < after.length() && isWordLetter(after.charAt(a))) a++;
        String word = before.subSequence(b, before.length()).toString() + after.subSequence(0, a);
        if (word.length() < 2 || !Character.isLetter(word.charAt(0))) { showStrip(); return; }
        ensureSuggestLayout();
        Suggest.Options o = prefs.suggestOptions();
        o.autocorrect = false;
        Suggest.Result r = suggest.suggest(word, dict(), user(), extraWords, null, o);
        if (r.literalSlot >= 0) { r.slots[r.literalSlot] = null; r.literalSlot = -1; }
        recorrectWord = word;
        recorrectBefore = before.length() - b;
        recorrectAfter = a;
        view.setSuggestions(r.slots, -1, false);
        current = null;
        predictions = r.slots;
    }

    private static boolean isWordLetter(char c) { return Character.isLetter(c) || c == '\'' || c == '’'; }

    // ------------------------------------------------------------------ KeyboardView.Actions

    @Override public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        markEdit();
        long now = SystemClock.uptimeMillis();
        recorrectWord = null;

        // gesture word followed by more typing: it is final now
        if (gestureWord && composing.length() > 0) {
            boolean word = isWordChar(text, false);
            finishComposing();
            if (word && prefs.autospaceAfterGesture()) ic.commitText(" ", 1);
        }
        pendingGestureSpace = false;

        boolean emojiSearch = wordMode && prefs.inlineEmoji() && emoji() != null;
        if (emojiSearch && ":".equals(text) && composing.length() == 0) {
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            if (before == null || before.length() == 0 || Character.isWhitespace(before.charAt(0))) {
                composing.append(':');
                ic.setComposingText(composing.toString(), 1);
                updateSuggestions();
                return;
            }
        }
        boolean emojiQuery = composing.length() > 0 && composing.charAt(0) == ':';

        if (wordMode && isWordChar(text, composing.length() > 0 && !emojiQuery)) {
            clearUndo();
            // auto space after punctuation: "Привет,мир" → "Привет, мир"
            if (composing.length() == 0 && prefs.autospacePunctuation()) {
                CharSequence before = ic.getTextBeforeCursor(1, 0);
                if (before != null && before.length() == 1 && SPACE_AFTER.indexOf(before.charAt(0)) >= 0)
                    ic.commitText(" ", 1);
            }
            autoSpaceInserted = false;
            predictions = null;
            composing.append(text);
            ic.setComposingText(composing.toString(), 1);
            lastSpaceAt = 0;
            updateSuggestions();
            return;
        }

        ic.beginBatchEdit();
        try {
            if (composing.length() > 0) {
                String typed = composing.toString();
                String finalWord = typed;
                if (!emojiQuery && SEPARATORS_AUTOCORRECT.contains(text) && current != null) {
                    String center = current.centerWord();
                    if (current.autocorrect && center != null) finalWord = center;
                    else if (prefs.centerSuggestion() && center != null && current.literalSlot != current.center())
                        finalWord = center;
                }
                ic.commitText(finalWord, 1);
                if (!emojiQuery) {
                    learnWord(finalWord);
                    prevWord = finalWord;
                }
                composing.setLength(0);
                current = null;
                gestureSlots = null;
                if (!finalWord.equals(typed) && prefs.backspaceReverts()) {
                    undoTyped = typed; undoCorrected = finalWord; undoSeparator = text;
                } else clearUndo();
            } else {
                clearUndo();
                // "word␣" from a suggestion pick followed by punctuation → "word, "
                if (autoSpaceInserted && text.length() == 1 && SPACE_AFTER.contains(text)) {
                    CharSequence before = ic.getTextBeforeCursor(1, 0);
                    if (before != null && before.length() == 1 && before.charAt(0) == ' ') {
                        ic.deleteSurroundingText(1, 0);
                        ic.commitText(text + " ", 1);
                        autoSpaceInserted = true;
                        lastSpaceAt = 0;
                        predictions = null;
                        showStrip();
                        return;
                    }
                }
            }
            autoSpaceInserted = false;

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
        markEdit();
        lastSpaceAt = 0;
        autoSpaceInserted = false;
        pendingGestureSpace = false;
        recorrectWord = null;

        // Backspace right after an autocorrect: bring back exactly what was typed.
        if (undoCorrected != null) {
            CharSequence before = ic.getTextBeforeCursor(undoCorrected.length() + undoSeparator.length(), 0);
            if (before != null && before.toString().equals(undoCorrected + undoSeparator)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(before.length(), 0);
                composing.setLength(0);
                composing.append(undoTyped);
                ic.setComposingText(composing.toString(), 1);
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
            if (gestureWord) {
                // a gesture word goes away as a whole
                composing.setLength(0);
                gestureWord = false;
                gestureSlots = null;
                ic.commitText("", 1);
                current = null;
                showStrip();
                return;
            }
            int cut = composing.offsetByCodePoints(composing.length(), -1);
            composing.setLength(cut);
            if (composing.length() == 0) ic.commitText("", 1);
            else ic.setComposingText(composing.toString(), 1);
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
        markEdit();
        String[] shown = gestureSlots != null ? gestureSlots : current != null ? current.slots
                : predictions != null ? predictions : idleSlots;
        if (shown == null || slot >= shown.length || shown[slot] == null) return;
        String word = shown[slot];

        // clipboard / punctuation chips
        if (shown == idleSlots && composing.length() == 0) {
            if (idleIsClipboard) {
                ic.commitText(clipText, 1);
                clipText = null;
            } else {
                CharSequence before = ic.getTextBeforeCursor(1, 0);
                ic.beginBatchEdit();
                if (SPACE_AFTER.contains(word) && before != null && before.length() == 1 && before.charAt(0) == ' ')
                    ic.deleteSurroundingText(1, 0);
                ic.commitText(SPACE_AFTER.contains(word) ? word + " " : word, 1);
                ic.endBatchEdit();
            }
            showStrip();
            return;
        }
        // re-correction of the word under the cursor
        if (recorrectWord != null && composing.length() == 0) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(recorrectBefore, recorrectAfter);
            ic.commitText(word, 1);
            ic.endBatchEdit();
            learnWord(word);
            recorrectWord = null;
            predictions = null;
            showStrip();
            return;
        }
        boolean emojiQuery = composing.length() > 0 && composing.charAt(0) == ':';
        boolean isEmoji = !word.isEmpty() && !Character.isLetterOrDigit(word.codePointAt(0));
        ic.beginBatchEdit();
        ic.commitText(word, 1);   // replaces the composing text, if any
        boolean space = !emojiQuery && prefs.autospaceSuggestion();
        if (space) ic.commitText(" ", 1);
        ic.endBatchEdit();
        if (current != null && current.literalSlot == slot) {
            UserDictionary u = user(); // the user insists on this spelling
            if (learn && u != null) u.learn(null, word);
        }
        if (!isEmoji) { learnWord(word); prevWord = word; }
        composing.setLength(0);
        gestureWord = false;
        gestureSlots = null;
        current = null;
        clearUndo();
        autoSpaceInserted = space;
        lastSpaceAt = 0;
        if (space) showPredictions(); else { predictions = null; showStrip(); }
    }

    @Override public void onShift() {
        // "Shift removes autospace": the space we added after a suggestion/gesture goes away
        if (!prefs.shiftRemovesAutospace()) return;
        pendingGestureSpace = false;
        if (!autoSpaceInserted) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before != null && before.length() == 1 && before.charAt(0) == ' ') {
            markEdit();
            ic.deleteSurroundingText(1, 0);
        }
        autoSpaceInserted = false;
    }

    @Override public void onEnter() {
        finishComposing();
        clearUndo();
        predictions = null;
        prevWord = null;
        recorrectWord = null;
        autoSpaceInserted = false;
        showStrip();
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return;
        markEdit();
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

    // ------------------------------------------------------------------ gesture typing

    @Override public void onGestureStart(Map<Character, float[]> centers, float keyWidth) {
        gestures.setKeys(centers, keyWidth);
    }

    @Override public String onGestureProgress(float[] xs, float[] ys, int n) {
        List<String> r = gestures.decode(xs, ys, n, dict(), user(), options, 1);
        return r.isEmpty() ? null : r.get(0);
    }

    @Override public void onGesture(List<float[]> xs, List<float[]> ys) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || view == null) return;
        markEdit();
        clearUndo();
        recorrectWord = null;
        predictions = null;
        ic.beginBatchEdit();
        try {
            // finish what was there and separate it with a space
            boolean hadGestureWord = gestureWord && composing.length() > 0;
            finishComposing();
            boolean needSpace;
            if (hadGestureWord) needSpace = prefs.autospaceAfterGesture();
            else {
                CharSequence before = ic.getTextBeforeCursor(1, 0);
                needSpace = prefs.autospaceBeforeGesture() && before != null && before.length() == 1
                        && !Character.isWhitespace(before.charAt(0)) && "([{«\"'".indexOf(before.charAt(0)) < 0;
            }
            if (needSpace) ic.commitText(" ", 1);

            for (int s = 0; s < xs.size(); s++) {
                float[] x = xs.get(s), y = ys.get(s);
                List<String> words = gestures.decode(x, y, x.length, dict(), user(), options, options.chips);
                if (words.isEmpty()) continue;
                String best = view.applyGestureShift(words.get(0));
                if (s < xs.size() - 1) {
                    ic.commitText(best + " ", 1);
                    learnWord(best);
                    prevWord = best;
                } else {
                    composing.setLength(0);
                    composing.append(best);
                    gestureWord = true;
                    ic.setComposingText(best, 1);
                    List<String> alts = new ArrayList<>();
                    for (String w : words) alts.add(Suggest.matchCase(w, best));
                    gestureSlots = Suggest.toSlots(alts, options.chips);
                }
            }
        } finally {
            ic.endBatchEdit();
        }
        showStrip();
    }

    // ------------------------------------------------------------------ toolbar

    @Override public void onToolKey(ToolKey key) {
        InputConnection ic = getCurrentInputConnection();
        if (runCustomCode(key, ic)) return;
        switch (key) {
            case SETTINGS:
                requestHideSelf(0);
                startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            case HIDE: requestHideSelf(0); return;
            case VOICE: startVoice(); return;
            case INCOGNITO: {
                boolean on = !prefs.incognito();
                prefs.set(Prefs.INCOGNITO, on);
                learn = !on && wordMode && prefs.learnWords();
                view.setToolState(ToolKey.INCOGNITO, on);
                toast(on ? "Инкогнито: слова не запоминаются" : "Инкогнито выключено");
                return;
            }
            case AUTOCORRECT: {
                boolean on = !prefs.autocorrect();
                prefs.set(Prefs.AUTOCORRECT, on);
                options.autocorrect = on && autocorrectHere;
                autocorrectHere = on && wordMode;
                options.autocorrect = autocorrectHere;
                view.setToolState(ToolKey.AUTOCORRECT, on);
                toast(on ? "Автоисправление включено" : "Автоисправление выключено");
                if (composing.length() > 0) updateSuggestions();
                return;
            }
            default: break;
        }
        if (ic == null) return;
        finishComposing();
        markEdit();
        predictions = null;
        showStrip();
        switch (key) {
            case UNDO: sendCombo(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
            case REDO: sendCombo(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON
                    | KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON); break;
            case SELECT_ALL: ic.performContextMenuAction(android.R.id.selectAll); break;
            case COPY: ic.performContextMenuAction(android.R.id.copy); break;
            case CUT: ic.performContextMenuAction(android.R.id.cut); break;
            case PASTE: ic.performContextMenuAction(android.R.id.paste); break;
            case SELECT_WORD: selectWord(ic); break;
            case LEFT: sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT); break;
            case RIGHT: sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT); break;
            case UP: sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP); break;
            case DOWN: sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN); break;
            case WORD_LEFT: sendCombo(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
            case WORD_RIGHT: sendCombo(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
            case FULL_LEFT: sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME); break;
            case FULL_RIGHT: sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END); break;
            case PAGE_UP: sendDownUpKeyEvents(KeyEvent.KEYCODE_PAGE_UP); break;
            case PAGE_DOWN: sendDownUpKeyEvents(KeyEvent.KEYCODE_PAGE_DOWN); break;
            case PAGE_START: sendCombo(KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
            case PAGE_END: sendCombo(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
            default: break;
        }
    }

    /** "Toolbar key codes" setting: a key can send its own key code or text instead. */
    private boolean runCustomCode(ToolKey key, InputConnection ic) {
        String all = prefs.getString(Prefs.TOOLBAR_CUSTOM_CODES, "");
        if (all.isEmpty()) return false;
        for (String line : all.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0 || !line.substring(0, eq).trim().equalsIgnoreCase(key.name())) continue;
            String value = line.substring(eq + 1).trim();
            if (value.isEmpty()) return false;
            int code = KeyEvent.keyCodeFromString(value.startsWith("KEYCODE_") ? value : "KEYCODE_" + value);
            if (code == KeyEvent.KEYCODE_UNKNOWN) {
                try { code = Integer.parseInt(value); } catch (NumberFormatException ignored) { code = KeyEvent.KEYCODE_UNKNOWN; }
            }
            finishComposing();
            markEdit();
            if (code != KeyEvent.KEYCODE_UNKNOWN) sendDownUpKeyEvents(code);
            else if (ic != null) ic.commitText(value, 1);
            return true;
        }
        return false;
    }

    private void sendCombo(int code, int meta) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long t = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta));
    }

    private void selectWord(InputConnection ic) {
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null || et.text == null) return;
        int cursor = et.selectionStart;
        CharSequence t = et.text;
        int s = cursor, e = cursor;
        while (s > 0 && isWordLetter(t.charAt(s - 1))) s--;
        while (e < t.length() && isWordLetter(t.charAt(e))) e++;
        if (s == e) return;
        ic.setSelection(et.startOffset + s, et.startOffset + e);
    }

    private void startVoice() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm == null) return;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            for (int i = 0; i < info.getSubtypeCount(); i++) {
                InputMethodSubtype st = info.getSubtypeAt(i);
                if ("voice".equals(st.getMode())) {
                    if (Build.VERSION.SDK_INT >= 28) switchInputMethod(info.getId(), st);
                    return;
                }
            }
        }
        toast("Голосовой ввод не найден: включите его в настройках клавиатур Android");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
