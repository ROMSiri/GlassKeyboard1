package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/** Glass menu under the round menu button: pops in on the soft spring, rows light up under the finger. */
final class GlassMenu extends GlassView implements Stretchy.Inner {
    interface Listener { void onItem(int index); }

    private static final float ROW = 48, WIDTH = 236, PAD = 6;

    private final String[] items;
    private final Tween.Color[] colors;
    private final Listener listener;
    private final Stretchy stretchy;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint();
    private float ix = 1, iy = 1;
    private int hot = -1;
    private boolean closing;

    GlassMenu(Context c, boolean dark, String[] items, Listener listener) {
        super(c, dark);
        this.items = items;
        this.listener = listener;
        colors = new Tween.Color[items.length];
        for (int i = 0; i < items.length; i++) colors[i] = new Tween.Color(textColor);
        paint.setTextSize(16 * dp);
        paint.setTypeface(weight(500));
        line.setColor(dark ? 0x14ffffff : 0x12000000);
        stretchy = new Stretchy(this, 1.03f, 14, this);
    }

    @Override public void onInnerScale(float x, float y) { ix = x; iy = y; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(Math.round(WIDTH * dp), Math.round((2 * PAD + ROW * items.length) * dp));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        setPivotX(w - 24 * dp);          // grows out of the menu button
        setPivotY(0);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setAlpha(0f);
        animate().alpha(1f).setDuration(180).start();
        stretchy.pop(.55f);
    }

    /** Fades away, then runs {@code done}. */
    void close(Runnable done) {
        if (closing) return;
        closing = true;
        animate().alpha(0f).setDuration(160).withEndAction(done).start();
    }

    @Override float shape(RectF out) {
        out.set(0, 0, getWidth(), getHeight());
        return 24 * dp;
    }

    private int rowAt(float x, float y) {
        if (x < 0 || x > getWidth()) return -1;
        int i = (int) Math.floor((y - PAD * dp) / (ROW * dp));
        return i >= 0 && i < items.length && y >= PAD * dp ? i : -1;
    }

    @Override void drawContent(Canvas c) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        boolean anim = false;
        c.save();
        c.scale(ix, iy, getWidth() / 2f, getHeight() / 2f);
        for (int i = 0; i < items.length; i++) {
            float top = (PAD + ROW * i) * dp;
            if (i > 0) c.drawRect(18 * dp, top, getWidth() - 18 * dp, top + Math.max(1, dp / 2), line);
            paint.setColor(colors[i].value());
            anim |= colors[i].running();
            c.drawText(items[i], 20 * dp, top + ROW * dp / 2 - (fm.ascent + fm.descent) / 2, paint);
        }
        c.restore();
        if (anim) postInvalidateOnAnimation();
    }

    private void setHot(int i) {
        if (i == hot) return;
        hot = i;
        for (int k = 0; k < items.length; k++) colors[k].to(k == hot ? ACTIVE : textColor, k == hot ? 250 : 450);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (closing) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stretchy.down(e);
                setHot(rowAt(e.getX(), e.getY()));
                return true;
            case MotionEvent.ACTION_MOVE:
                stretchy.move(e);
                setHot(rowAt(e.getX(), e.getY()));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int i = e.getActionMasked() == MotionEvent.ACTION_UP ? rowAt(e.getX(), e.getY()) : -1;
                stretchy.up();
                if (i >= 0 && listener != null) listener.onItem(i);
                else setHot(-1);
                return true;
            }
        }
        return false;
    }
}
