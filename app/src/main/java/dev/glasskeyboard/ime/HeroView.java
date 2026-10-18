package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Main-screen showcase: a small glass keyboard on a colourful backdrop that types a word. */
final class HeroView extends View {
    private static final String[] ROWS = {"йцукенгшзх", "ывапролдж", "ясмитьб"};
    private static final String[] WORDS = {"привет", "стекло", "клавиатура"};

    private final float dp;
    private final boolean dark;
    private final int accent;
    private final GlassPainter painter;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path clip = new Path();
    private int word, letter;
    private char pressed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            String w = WORDS[word];
            if (letter < w.length()) { pressed = w.charAt(letter); letter++; }
            else { pressed = 0; letter = 0; word = (word + 1) % WORDS.length; }
            invalidate();
            postDelayed(this, letter == 0 ? 1400 : 330);
        }
    };

    HeroView(Context c, int accent, boolean dark) {
        super(c);
        this.accent = accent;
        this.dark = dark;
        dp = c.getResources().getDisplayMetrics().density;
        painter = new GlassPainter(c, dark);
        text.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(tick, 800); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(230 * dp));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = 26 * dp;
        clip.reset();
        clip.addRoundRect(0, 0, w, h, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);

        // backdrop: soft gradient with coloured blobs (what the blur would show)
        paint.setShader(new LinearGradient(0, 0, w, h, dark ? 0xff1b2340 : 0xffffe3c8, dark ? 0xff3a1f4a : 0xffc9dbff, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        blob(c, w * .18f, h * .30f, w * .30f, (accent & 0xffffff) | 0xcc000000);
        blob(c, w * .85f, h * .25f, w * .28f, dark ? 0xaa7b3fe4 : 0xccff8fa3);
        blob(c, w * .60f, h * .95f, w * .35f, dark ? 0x9930c5b4 : 0xbb6fe3c6);
        paint.setShader(null);

        // typed word bubble
        String typed = WORDS[word].substring(0, letter);
        text.setTextSize(18 * dp);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float bw = Math.max(text.measureText(typed) + 34 * dp, 96 * dp), bh = 38 * dp;
        r.set(w / 2f - bw / 2f, 18 * dp, w / 2f + bw / 2f, 18 * dp + bh);
        painter.draw(c, r, bh / 2f, dark ? 0xcc2a2a2e : 0xd9ffffff, painter.bubble, 1f);
        text.setColor(dark ? 0xfff2f2f2 : 0xff1d1d1f);
        c.drawText(typed + "|", r.centerX(), r.centerY() + 6.5f * dp, text);

        // glass panel with keys
        float top = 72 * dp;
        r.set(10 * dp, top, w - 10 * dp, h + rad);
        paint.setColor(PanelBlur.tint(dark, 72));
        c.drawRoundRect(r, rad, rad, paint);
        painter.drawPanelRim(c, w, h, 0);
        float padX = 18 * dp, kw = (w - 2 * padX) / 10f, kh = 36 * dp, gap = 4 * dp, y = top + 14 * dp;
        text.setTextSize(15 * dp);
        text.setTypeface(Typeface.DEFAULT);
        for (String row : ROWS) {
            float x = padX + (10 - row.length()) * kw / 2f;
            for (int i = 0; i < row.length(); i++) {
                char ch = row.charAt(i);
                boolean on = ch == pressed;
                r.set(x + gap / 2f, y, x + kw - gap / 2f, y + kh);
                if (on) { float s = 1.5f * dp; r.inset(s, s); }
                int fill = on ? (accent & 0xffffff) | 0xcc000000 : (dark ? 0x30ffffff : 0x99ffffff);
                painter.draw(c, r, 8 * dp, fill, painter.key, 1f);
                text.setColor(on ? 0xffffffff : (dark ? 0xfff2f2f2 : 0xff1d1d1f));
                c.drawText(String.valueOf(ch), r.centerX(), r.centerY() + 5 * dp, text);
                x += kw;
            }
            y += kh + 8 * dp;
        }
        c.restore();
    }

    private void blob(Canvas c, float x, float y, float radius, int color) {
        paint.setShader(new RadialGradient(x, y, radius, color, color & 0x00ffffff, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
    }
}
