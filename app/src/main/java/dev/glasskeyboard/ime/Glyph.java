package dev.glasskeyboard.ime;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small line icons for the settings rows, drawn white on a coloured rounded square. */
enum Glyph {
    KEYBOARD, CORRECTION, TOOLBAR, GESTURE, BLUR, OPACITY, HEIGHT, OFFSET, PREVIEW, VIBRATE, SOUND,
    BOOK, SHIELD, MAGIC, SHORTCUT, PRECISION, BACKSPACE, CAPS, SPACE, DOUBLE_SPACE, SUGGEST,
    CENTER, FIVE, EMOJI, SEARCH, LEARN, NEXT, PUNCT, CLIPBOARD, CONTACTS, APPS, CURSOR, MODE,
    SWIPE_DOWN, PIN, KEY, CODE, TRAIL, FLOAT, PHRASE, TIMER, FADE, SPELL, CHECK, TRASH, INFO, WEB;

    /** Drawable: rounded square in {@code color} with this glyph (or a toolbar key icon). */
    static Drawable tile(Glyph g, ToolKey key, int color, float density) {
        return new Drawable() {
            private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            private final RectF r = new RectF();

            @Override public void draw(Canvas c) {
                r.set(getBounds());
                bg.setColor(color);
                float rad = r.width() * .28f;
                c.drawRoundRect(r, rad, rad, bg);
                p.setColor(0xffffffff);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                float u = r.width() / 30f;          // icon drawn in a 24-unit box inside the tile
                p.setStrokeWidth(1.8f * u);
                float cx = r.centerX(), cy = r.centerY();
                if (key != null) key.draw(c, cx, cy, u * .95f, p, path, new RectF());
                else g.draw(c, cx, cy, u, p, path, new RectF());
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    void draw(Canvas c, float cx, float cy, float u, Paint p, Path path, RectF t) {
        path.reset();
        switch (this) {
            case KEYBOARD:
                t.set(cx - 9 * u, cy - 6 * u, cx + 9 * u, cy + 6 * u);
                c.drawRoundRect(t, 2 * u, 2 * u, p);
                for (int i = -1; i <= 1; i++) dot(c, cx + i * 4.5f * u, cy - 2.2f * u, u, p);
                c.drawLine(cx - 4 * u, cy + 2.5f * u, cx + 4 * u, cy + 2.5f * u, p);
                return;
            case CORRECTION:
                path.moveTo(cx - 9 * u, cy + 6 * u); path.lineTo(cx - 5 * u, cy - 6 * u); path.lineTo(cx - 1 * u, cy + 6 * u);
                path.moveTo(cx - 7.5f * u, cy + 2 * u); path.lineTo(cx - 2.5f * u, cy + 2 * u);
                path.moveTo(cx + 1.5f * u, cy + 1 * u); path.lineTo(cx + 4 * u, cy + 4 * u); path.lineTo(cx + 9 * u, cy - 4 * u);
                break;
            case TOOLBAR:
                for (int i = -1; i <= 1; i++) {
                    t.set(cx + i * 6.5f * u - 2.5f * u, cy - 2.5f * u, cx + i * 6.5f * u + 2.5f * u, cy + 2.5f * u);
                    c.drawRoundRect(t, 1 * u, 1 * u, p);
                }
                c.drawLine(cx - 9 * u, cy + 7 * u, cx + 9 * u, cy + 7 * u, p);
                return;
            case GESTURE:
                path.moveTo(cx - 9 * u, cy + 4 * u);
                path.cubicTo(cx - 5 * u, cy - 8 * u, cx - 1 * u, cy + 8 * u, cx + 3 * u, cy - 2 * u);
                path.quadTo(cx + 5 * u, cy - 6 * u, cx + 9 * u, cy - 5 * u);
                dot(c, cx + 9 * u, cy - 5 * u, u * 1.2f, p);
                break;
            case BLUR:
                path.moveTo(cx, cy - 9 * u);
                path.cubicTo(cx + 9 * u, cy + 1 * u, cx + 6 * u, cy + 8 * u, cx, cy + 8 * u);
                path.cubicTo(cx - 6 * u, cy + 8 * u, cx - 9 * u, cy + 1 * u, cx, cy - 9 * u);
                break;
            case OPACITY:
                c.drawCircle(cx, cy, 8 * u, p);
                path.moveTo(cx, cy - 8 * u);
                path.arcTo(new RectF(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u), -90, 180);
                path.close();
                fill(c, path, p);
                return;
            case HEIGHT:
                c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, p);
                chevron(path, cx, cy - 6 * u, u, 0, -1);
                chevron(path, cx, cy + 6 * u, u, 0, 1);
                break;
            case OFFSET:
                c.drawLine(cx - 8 * u, cy + 8 * u, cx + 8 * u, cy + 8 * u, p);
                c.drawLine(cx, cy + 4 * u, cx, cy - 8 * u, p);
                chevron(path, cx, cy - 5 * u, u, 0, -1);
                break;
            case PREVIEW:
                t.set(cx - 7 * u, cy - 9 * u, cx + 7 * u, cy + 3 * u);
                c.drawRoundRect(t, 3 * u, 3 * u, p);
                path.moveTo(cx - 2 * u, cy + 3 * u); path.lineTo(cx, cy + 7 * u); path.lineTo(cx + 2 * u, cy + 3 * u);
                c.drawLine(cx - 2.5f * u, cy - 3 * u, cx + 2.5f * u, cy - 3 * u, p);
                break;
            case VIBRATE:
                t.set(cx - 4 * u, cy - 8 * u, cx + 4 * u, cy + 8 * u);
                c.drawRoundRect(t, 2 * u, 2 * u, p);
                c.drawLine(cx - 8 * u, cy - 4 * u, cx - 8 * u, cy + 4 * u, p);
                c.drawLine(cx + 8 * u, cy - 4 * u, cx + 8 * u, cy + 4 * u, p);
                return;
            case SOUND:
                path.moveTo(cx - 8 * u, cy - 3 * u); path.lineTo(cx - 4 * u, cy - 3 * u); path.lineTo(cx + 1 * u, cy - 8 * u);
                path.lineTo(cx + 1 * u, cy + 8 * u); path.lineTo(cx - 4 * u, cy + 3 * u); path.lineTo(cx - 8 * u, cy + 3 * u); path.close();
                c.drawArc(new RectF(cx - 2 * u, cy - 5 * u, cx + 8 * u, cy + 5 * u), -50, 100, false, p);
                break;
            case BOOK:
                path.moveTo(cx, cy - 6 * u); path.quadTo(cx - 4 * u, cy - 9 * u, cx - 9 * u, cy - 7 * u);
                path.lineTo(cx - 9 * u, cy + 7 * u); path.quadTo(cx - 4 * u, cy + 5 * u, cx, cy + 8 * u);
                path.quadTo(cx + 4 * u, cy + 5 * u, cx + 9 * u, cy + 7 * u); path.lineTo(cx + 9 * u, cy - 7 * u);
                path.quadTo(cx + 4 * u, cy - 9 * u, cx, cy - 6 * u); path.lineTo(cx, cy + 8 * u);
                break;
            case SHIELD:
                path.moveTo(cx, cy - 9 * u); path.lineTo(cx + 8 * u, cy - 6 * u); path.lineTo(cx + 7 * u, cy + 2 * u);
                path.quadTo(cx + 5 * u, cy + 7 * u, cx, cy + 9 * u); path.quadTo(cx - 5 * u, cy + 7 * u, cx - 7 * u, cy + 2 * u);
                path.lineTo(cx - 8 * u, cy - 6 * u); path.close();
                path.moveTo(cx - 3.5f * u, cy); path.lineTo(cx + 3.5f * u, cy);
                break;
            case MAGIC:
                c.drawLine(cx - 8 * u, cy + 8 * u, cx + 3 * u, cy - 3 * u, p);
                star(path, cx + 5 * u, cy - 5 * u, 4 * u);
                star(path, cx - 4 * u, cy - 6 * u, 2.2f * u);
                break;
            case SHORTCUT:
                path.moveTo(cx - 9 * u, cy - 4 * u); path.lineTo(cx - 2 * u, cy - 4 * u);
                path.moveTo(cx - 9 * u, cy + 1 * u); path.lineTo(cx - 5 * u, cy + 1 * u);
                path.moveTo(cx + 1 * u, cy + 5 * u); path.lineTo(cx + 9 * u, cy + 5 * u);
                path.moveTo(cx + 6 * u, cy + 2 * u); path.lineTo(cx + 9 * u, cy + 5 * u); path.lineTo(cx + 6 * u, cy + 8 * u);
                path.moveTo(cx + 1 * u, cy - 8 * u); path.lineTo(cx + 1 * u, cy + 5 * u);
                break;
            case PRECISION:
                c.drawCircle(cx, cy, 8 * u, p);
                c.drawCircle(cx, cy, 4 * u, p);
                dot(c, cx, cy, u * 1.3f, p);
                return;
            case BACKSPACE:
                path.moveTo(cx - 10 * u, cy); path.lineTo(cx - 5 * u, cy - 6 * u); path.lineTo(cx + 9 * u, cy - 6 * u);
                path.lineTo(cx + 9 * u, cy + 6 * u); path.lineTo(cx - 5 * u, cy + 6 * u); path.close();
                path.moveTo(cx - 1 * u, cy - 2.5f * u); path.lineTo(cx + 4 * u, cy + 2.5f * u);
                path.moveTo(cx + 4 * u, cy - 2.5f * u); path.lineTo(cx - 1 * u, cy + 2.5f * u);
                break;
            case CAPS:
                path.moveTo(cx, cy - 9 * u); path.lineTo(cx + 8 * u, cy); path.lineTo(cx + 4 * u, cy);
                path.lineTo(cx + 4 * u, cy + 7 * u); path.lineTo(cx - 4 * u, cy + 7 * u); path.lineTo(cx - 4 * u, cy);
                path.lineTo(cx - 8 * u, cy); path.close();
                break;
            case SPACE:
                path.moveTo(cx - 9 * u, cy - 1 * u); path.lineTo(cx - 9 * u, cy + 4 * u);
                path.lineTo(cx + 9 * u, cy + 4 * u); path.lineTo(cx + 9 * u, cy - 1 * u);
                break;
            case DOUBLE_SPACE:
                path.moveTo(cx - 9 * u, cy - 1 * u); path.lineTo(cx - 9 * u, cy + 4 * u);
                path.lineTo(cx + 3 * u, cy + 4 * u); path.lineTo(cx + 3 * u, cy - 1 * u);
                dot(c, cx + 7.5f * u, cy + 4 * u, u * 1.5f, p);
                break;
            case SUGGEST:
                c.drawArc(new RectF(cx - 6.5f * u, cy - 9 * u, cx + 6.5f * u, cy + 4 * u), 140, 260, false, p);
                c.drawLine(cx - 3 * u, cy + 6 * u, cx + 3 * u, cy + 6 * u, p);
                c.drawLine(cx - 2 * u, cy + 9 * u, cx + 2 * u, cy + 9 * u, p);
                return;
            case CENTER:
                for (int i = -1; i <= 1; i++) {
                    t.set(cx + i * 7 * u - 3 * u, cy - 3 * u, cx + i * 7 * u + 3 * u, cy + 3 * u);
                    if (i == 0) { Paint.Style s = p.getStyle(); p.setStyle(Paint.Style.FILL_AND_STROKE); c.drawRoundRect(t, u, u, p); p.setStyle(s); }
                    else c.drawRoundRect(t, u, u, p);
                }
                return;
            case FIVE:
                for (int i = -2; i <= 2; i++) dot(c, cx + i * 4.2f * u, cy, u * 1.4f, p);
                return;
            case EMOJI:
                c.drawCircle(cx, cy, 8.5f * u, p);
                dot(c, cx - 3 * u, cy - 2.5f * u, u * 1.1f, p);
                dot(c, cx + 3 * u, cy - 2.5f * u, u * 1.1f, p);
                c.drawArc(new RectF(cx - 4.5f * u, cy - 2 * u, cx + 4.5f * u, cy + 5 * u), 20, 140, false, p);
                return;
            case SEARCH:
                c.drawCircle(cx - 2 * u, cy - 2 * u, 6 * u, p);
                c.drawLine(cx + 2.5f * u, cy + 2.5f * u, cx + 8 * u, cy + 8 * u, p);
                return;
            case LEARN:
                path.moveTo(cx - 9 * u, cy - 3 * u); path.lineTo(cx, cy - 8 * u); path.lineTo(cx + 9 * u, cy - 3 * u);
                path.lineTo(cx, cy + 2 * u); path.close();
                path.moveTo(cx - 5 * u, cy - 0.5f * u); path.lineTo(cx - 5 * u, cy + 5 * u);
                path.quadTo(cx, cy + 9 * u, cx + 5 * u, cy + 5 * u); path.lineTo(cx + 5 * u, cy - 0.5f * u);
                break;
            case NEXT:
                c.drawLine(cx - 9 * u, cy, cx + 6 * u, cy, p);
                chevron(path, cx + 5 * u, cy, u, 1, 0);
                dot(c, cx - 9 * u, cy, u * 1.4f, p);
                break;
            case PUNCT:
                c.drawLine(cx - 4 * u, cy - 8 * u, cx - 4 * u, cy + 3 * u, p);
                dot(c, cx - 4 * u, cy + 7 * u, u * 1.4f, p);
                c.drawArc(new RectF(cx + 1 * u, cy - 8 * u, cx + 9 * u, cy), 180, 250, false, p);
                c.drawLine(cx + 5 * u, cy, cx + 5 * u, cy + 3 * u, p);
                dot(c, cx + 5 * u, cy + 7 * u, u * 1.4f, p);
                return;
            case CLIPBOARD:
                t.set(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 9 * u);
                c.drawRoundRect(t, 2 * u, 2 * u, p);
                t.set(cx - 3.5f * u, cy - 9.5f * u, cx + 3.5f * u, cy - 5 * u);
                c.drawRoundRect(t, 1.5f * u, 1.5f * u, p);
                c.drawLine(cx - 3.5f * u, cy, cx + 3.5f * u, cy, p);
                c.drawLine(cx - 3.5f * u, cy + 4 * u, cx + 1.5f * u, cy + 4 * u, p);
                return;
            case CONTACTS:
                c.drawCircle(cx, cy - 3.5f * u, 4 * u, p);
                c.drawArc(new RectF(cx - 8 * u, cy + 2 * u, cx + 8 * u, cy + 16 * u), 180, 180, false, p);
                return;
            case APPS:
                for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2) {
                    t.set(cx + i * 4.5f * u - 3 * u, cy + j * 4.5f * u - 3 * u, cx + i * 4.5f * u + 3 * u, cy + j * 4.5f * u + 3 * u);
                    c.drawRoundRect(t, 1.5f * u, 1.5f * u, p);
                }
                return;
            case CURSOR:
                c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, p);
                c.drawLine(cx - 3 * u, cy - 8 * u, cx + 3 * u, cy - 8 * u, p);
                c.drawLine(cx - 3 * u, cy + 8 * u, cx + 3 * u, cy + 8 * u, p);
                return;
            case MODE:
                path.moveTo(cx, cy - 8 * u); path.lineTo(cx + 9 * u, cy - 3.5f * u); path.lineTo(cx, cy + 1 * u);
                path.lineTo(cx - 9 * u, cy - 3.5f * u); path.close();
                path.moveTo(cx - 9 * u, cy + 1.5f * u); path.lineTo(cx, cy + 6 * u); path.lineTo(cx + 9 * u, cy + 1.5f * u);
                break;
            case SWIPE_DOWN:
                c.drawLine(cx, cy - 8 * u, cx, cy + 5 * u, p);
                chevron(path, cx, cy + 4 * u, u, 0, 1);
                c.drawLine(cx - 8 * u, cy + 9 * u, cx + 8 * u, cy + 9 * u, p);
                break;
            case PIN:
                path.moveTo(cx - 4 * u, cy - 9 * u); path.lineTo(cx + 4 * u, cy - 9 * u);
                path.moveTo(cx - 3 * u, cy - 9 * u); path.lineTo(cx - 3 * u, cy - 3 * u); path.lineTo(cx - 7 * u, cy + 2 * u);
                path.lineTo(cx + 7 * u, cy + 2 * u); path.lineTo(cx + 3 * u, cy - 3 * u); path.lineTo(cx + 3 * u, cy - 9 * u);
                path.moveTo(cx, cy + 2 * u); path.lineTo(cx, cy + 9 * u);
                break;
            case KEY:
                t.set(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u);
                c.drawRoundRect(t, 3 * u, 3 * u, p);
                c.drawLine(cx - 3 * u, cy + 3 * u, cx + 3 * u, cy + 3 * u, p);
                return;
            case CODE:
                chevron(path, cx - 5 * u, cy, u, -1, 0);
                chevron(path, cx + 5 * u, cy, u, 1, 0);
                path.moveTo(cx + 2 * u, cy - 8 * u); path.lineTo(cx - 2 * u, cy + 8 * u);
                break;
            case TRAIL:
                path.moveTo(cx - 9 * u, cy + 6 * u);
                path.cubicTo(cx - 4 * u, cy + 6 * u, cx - 4 * u, cy - 6 * u, cx + 1 * u, cy - 6 * u);
                path.cubicTo(cx + 5 * u, cy - 6 * u, cx + 5 * u, cy + 2 * u, cx + 9 * u, cy + 2 * u);
                break;
            case FLOAT:
                t.set(cx - 9 * u, cy - 8 * u, cx + 9 * u, cy - 1 * u);
                c.drawRoundRect(t, 3.5f * u, 3.5f * u, p);
                path.moveTo(cx - 6 * u, cy + 8 * u);
                path.quadTo(cx, cy + 1 * u, cx + 6 * u, cy + 6 * u);
                break;
            case PHRASE:
                path.moveTo(cx - 9 * u, cy - 4 * u); path.quadTo(cx - 6 * u, cy - 8 * u, cx - 3 * u, cy - 4 * u);
                path.moveTo(cx + 3 * u, cy - 4 * u); path.quadTo(cx + 6 * u, cy - 8 * u, cx + 9 * u, cy - 4 * u);
                path.moveTo(cx - 8 * u, cy + 2 * u); path.lineTo(cx - 8 * u, cy + 6 * u);
                path.lineTo(cx + 8 * u, cy + 6 * u); path.lineTo(cx + 8 * u, cy + 2 * u);
                break;
            case TIMER:
                c.drawCircle(cx, cy + 1 * u, 8 * u, p);
                c.drawLine(cx, cy + 1 * u, cx, cy - 3.5f * u, p);
                c.drawLine(cx, cy + 1 * u, cx + 3.5f * u, cy + 3 * u, p);
                c.drawLine(cx - 2.5f * u, cy - 9.5f * u, cx + 2.5f * u, cy - 9.5f * u, p);
                return;
            case FADE:
                for (int i = 0; i < 4; i++) {
                    p.setAlpha(255 - i * 55);
                    dot(c, cx - 7 * u + i * 4.7f * u, cy + (i % 2 == 0 ? 2 : -2) * u, u * (2.2f - i * .35f), p);
                }
                p.setAlpha(255);
                return;
            case SPELL:
                path.moveTo(cx - 8 * u, cy + 1 * u); path.lineTo(cx - 5 * u, cy - 7 * u); path.lineTo(cx - 2 * u, cy + 1 * u);
                path.moveTo(cx - 7 * u, cy - 2 * u); path.lineTo(cx - 3 * u, cy - 2 * u);
                path.moveTo(cx - 9 * u, cy + 6 * u);
                path.quadTo(cx - 6.5f * u, cy + 3 * u, cx - 4 * u, cy + 6 * u);
                path.quadTo(cx - 1.5f * u, cy + 9 * u, cx + 1 * u, cy + 6 * u);
                path.moveTo(cx + 2 * u, cy - 1 * u); path.lineTo(cx + 4.5f * u, cy + 2 * u); path.lineTo(cx + 9 * u, cy - 5 * u);
                break;
            case CHECK:
                path.moveTo(cx - 7 * u, cy); path.lineTo(cx - 2 * u, cy + 5 * u); path.lineTo(cx + 8 * u, cy - 6 * u);
                break;
            case TRASH:
                c.drawLine(cx - 8 * u, cy - 6 * u, cx + 8 * u, cy - 6 * u, p);
                c.drawLine(cx - 3 * u, cy - 9 * u, cx + 3 * u, cy - 9 * u, p);
                path.moveTo(cx - 6 * u, cy - 6 * u); path.lineTo(cx - 5 * u, cy + 8 * u);
                path.lineTo(cx + 5 * u, cy + 8 * u); path.lineTo(cx + 6 * u, cy - 6 * u);
                break;
            case WEB:
                c.drawCircle(cx, cy, 8.5f * u, p);
                c.drawOval(new RectF(cx - 3.5f * u, cy - 8.5f * u, cx + 3.5f * u, cy + 8.5f * u), p);
                c.drawLine(cx - 8.5f * u, cy, cx + 8.5f * u, cy, p);
                return;
            case INFO:
            default:
                c.drawCircle(cx, cy, 8.5f * u, p);
                c.drawLine(cx, cy - 1 * u, cx, cy + 4.5f * u, p);
                dot(c, cx, cy - 4.5f * u, u * 1.2f, p);
                return;
        }
        c.drawPath(path, p);
    }

    private static void chevron(Path path, float cx, float cy, float u, int dx, int dy) {
        if (dx != 0) {
            path.moveTo(cx - dx * 3 * u, cy - 5 * u); path.lineTo(cx + dx * 1 * u, cy); path.lineTo(cx - dx * 3 * u, cy + 5 * u);
        } else {
            path.moveTo(cx - 5 * u, cy - dy * 3 * u); path.lineTo(cx, cy + dy * 1 * u); path.lineTo(cx + 5 * u, cy - dy * 3 * u);
        }
    }

    private static void star(Path path, float cx, float cy, float r) {
        path.moveTo(cx, cy - r); path.quadTo(cx, cy, cx + r, cy);
        path.quadTo(cx, cy, cx, cy + r); path.quadTo(cx, cy, cx - r, cy);
        path.quadTo(cx, cy, cx, cy - r);
    }

    private static void dot(Canvas c, float x, float y, float r, Paint p) {
        Paint.Style s = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, r, p);
        p.setStyle(s);
    }

    private static void fill(Canvas c, Path path, Paint p) {
        Paint.Style s = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(path, p);
        p.setStyle(s);
    }
}
