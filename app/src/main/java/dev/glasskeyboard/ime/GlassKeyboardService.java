package dev.glasskeyboard.ime;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/** The input method: wires {@link KeyboardView} to the current editor and keeps the blur bound. */
public final class GlassKeyboardService extends InputMethodService implements KeyboardView.Actions {
    private Prefs prefs;
    private KeyboardView view;
    private PanelBlur blur;
    private long lastSpaceAt;

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        blur = new PanelBlur((mode, detail) -> {
            String name = mode == PanelBlur.Mode.OPLUS ? "системный материал ColorOS"
                    : mode == PanelBlur.Mode.WINDOW ? "размытие окна Android" : "без размытия";
            prefs.setBlurStatus(detail == null || detail.isEmpty() ? name : name + " (" + detail + ")");
            if (view != null) view.onBlurModeChanged(mode, detail);
        });
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
        view.post(this::bindBlur);
        updateShift();
    }

    @Override public void onWindowShown() {
        super.onWindowShown();
        // The surface exists only now; re-submit the material after the first frame.
        if (view != null) view.post(() -> { bindBlur(); blur.refresh(); });
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (view != null) view.resetState();
    }

    @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        updateShift();
    }

    @Override public void onDestroy() {
        if (blur != null) blur.release();
        super.onDestroy();
    }

    private void updateShift() {
        if (view == null || !prefs.autoCaps()) return;
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return;
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

    // ------------------------------------------------------------------ KeyboardView.Actions

    @Override public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long now = SystemClock.uptimeMillis();
        if (" ".equals(text)) {
            if (prefs.doubleSpace() && now - lastSpaceAt < 600) {
                CharSequence before = ic.getTextBeforeCursor(2, 0);
                if (before != null && before.length() == 2 && before.charAt(1) == ' '
                        && Character.isLetterOrDigit(before.charAt(0))) {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(". ", 1);
                    ic.endBatchEdit();
                    lastSpaceAt = 0;
                    return;
                }
            }
            lastSpaceAt = now;
        } else {
            lastSpaceAt = 0;
        }
        ic.commitText(text, 1);
    }

    @Override public void onDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastSpaceAt = 0;
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) ic.commitText("", 1);
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
    }

    @Override public void onEnter() {
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
        int code = delta < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        for (int i = 0; i < Math.abs(delta); i++) sendDownUpKeyEvents(code);
    }

    @Override public void onHide() { requestHideSelf(0); }

    @Override public void onPaste() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.performContextMenuAction(android.R.id.paste);
    }

    @Override public void onSettings() {
        requestHideSelf(0);
        Intent intent = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override public void onLanguageChanged(String lang) { prefs.setLanguage(lang); }

    @Override public void onPickInputMethod() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showInputMethodPicker();
    }
}
