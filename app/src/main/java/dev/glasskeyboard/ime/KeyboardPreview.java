package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.util.List;
import java.util.Map;

/**
 * Live preview for the appearance settings: the real {@link KeyboardView}, scaled down, over a
 * wallpaper. The part behind the keyboard is blurred (Android 12+), so opacity, key height,
 * bottom offset and blur on/off are shown exactly as on the phone.
 */
final class KeyboardPreview extends FrameLayout {
    private final Prefs prefs;
    private final boolean dark;
    private final int accent;
    private final float dp, scale = .74f;
    private final KeyboardView keyboard;
    private final View backdrop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int topArea;

    KeyboardPreview(Context c, Prefs prefs, boolean dark, int accent) {
        super(c);
        this.prefs = prefs;
        this.dark = dark;
        this.accent = accent;
        dp = c.getResources().getDisplayMetrics().density;
        topArea = Math.round(70 * dp);
        setWillNotDraw(false);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 24 * dp);
            }
        });
        text.setTextSize(13 * dp);

        backdrop = new View(c) {
            @Override protected void onDraw(Canvas canvas) {
                canvas.save();
                canvas.translate(0, -getTop());
                drawWallpaper(canvas, KeyboardPreview.this.getWidth(), KeyboardPreview.this.getHeight());
                canvas.restore();
            }
        };
        if (Build.VERSION.SDK_INT >= 31)
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(22 * dp, 22 * dp, Shader.TileMode.CLAMP));
        keyboard = new KeyboardView(c, prefs, new NoActions());
        keyboard.setOnTouchListener((v, e) -> true); // look, don't type
        addView(backdrop);
        addView(keyboard);
        refresh();
    }

    /** Re-read the settings and redraw. */
    void refresh() {
        boolean blur = prefs.blur();
        keyboard.onBlurModeChanged(blur ? PanelBlur.Mode.WINDOW : PanelBlur.Mode.NONE, "");
        backdrop.setVisibility(blur ? VISIBLE : INVISIBLE);
        keyboard.resetState();
        keyboard.setSuggestions(new String[]{"привет", "приветик", "привета"}, -1, true);
        requestLayout();
        invalidate();
        keyboard.invalidate();
        backdrop.invalidate();
    }

    @Override public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
        return insets; // the preview never sits behind the navigation bar
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int kbW = Math.round(w / scale);
        keyboard.measure(MeasureSpec.makeMeasureSpec(kbW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int kbH = Math.round(keyboard.getMeasuredHeight() * scale);
        backdrop.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(kbH, MeasureSpec.EXACTLY));
        setMeasuredDimension(w, topArea + kbH);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int right, int b) {
        int w = right - l;
        keyboard.layout(0, topArea, keyboard.getMeasuredWidth(), topArea + keyboard.getMeasuredHeight());
        keyboard.setPivotX(0);
        keyboard.setPivotY(0);
        keyboard.setScaleX(scale);
        keyboard.setScaleY(scale);
        backdrop.layout(0, topArea, w, topArea + backdrop.getMeasuredHeight());
    }

    @Override protected void onDraw(Canvas canvas) {
        drawWallpaper(canvas, getWidth(), getHeight());
        // a chat bubble so the transparency has something to show through
        String msg = "Как вам новая клавиатура?";
        float tw = text.measureText(msg);
        r.set(14 * dp, 18 * dp, 14 * dp + tw + 26 * dp, 18 * dp + 34 * dp);
        paint.setShader(null);
        paint.setColor(dark ? 0x40ffffff : 0xd9ffffff);
        canvas.drawRoundRect(r, 17 * dp, 17 * dp, paint);
        text.setColor(dark ? 0xfff2f2f2 : 0xff1d1d1f);
        canvas.drawText(msg, r.left + 13 * dp, r.centerY() + 4.5f * dp, text);
    }

    private void drawWallpaper(Canvas c, float w, float h) {
        paint.setShader(new LinearGradient(0, 0, w, h, dark ? 0xff1b2340 : 0xffffe3c8,
                dark ? 0xff3a1f4a : 0xffc9dbff, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        blob(c, w * .18f, h * .35f, w * .32f, (accent & 0xffffff) | 0xcc000000);
        blob(c, w * .86f, h * .22f, w * .30f, dark ? 0xaa7b3fe4 : 0xccff8fa3);
        blob(c, w * .55f, h * .78f, w * .40f, dark ? 0x9930c5b4 : 0xbb6fe3c6);
        blob(c, w * .10f, h * .95f, w * .30f, dark ? 0x99ff6b6b : 0xccffd166);
        paint.setShader(null);
    }

    private void blob(Canvas c, float x, float y, float radius, int color) {
        paint.setShader(new RadialGradient(x, y, radius, color, color & 0x00ffffff, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
    }

    /** The preview keyboard does nothing when "pressed". */
    private static final class NoActions implements KeyboardView.Actions {
        @Override public void onText(String text) { }
        @Override public void onDelete() { }
        @Override public void onEnter() { }
        @Override public void onCursor(int delta) { }
        @Override public void onHide() { }
        @Override public void onLanguageChanged(String lang) { }
        @Override public void onPickInputMethod() { }
        @Override public void onSuggestion(int slot) { }
        @Override public void onToolKey(ToolKey key) { }
        @Override public void onShift() { }
        @Override public void onGestureStart(Map<Character, float[]> centers, float keyWidth) { }
        @Override public String onGestureProgress(float[] xs, float[] ys, int n) { return null; }
        @Override public void onGesture(List<float[]> xs, List<float[]> ys) { }
    }
}
