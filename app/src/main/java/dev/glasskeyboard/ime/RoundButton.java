package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

/** Round glass button (back / menu): grows a bit more than the capsules, fires only if released on it. */
final class RoundButton extends GlassView implements Stretchy.Inner {
    private static final float SIZE = 48, ICON = 24;

    private final Drawable icon;
    private final Runnable action;
    private final Stretchy stretchy;
    private final Tween.Color color;
    private float ix = 1, iy = 1;

    RoundButton(Context c, boolean dark, Drawable icon, String label, Runnable action) {
        super(c, dark);
        this.icon = icon;
        this.action = action;
        color = new Tween.Color(textColor);
        stretchy = new Stretchy(this, 1.12f, 16, this);
        setContentDescription(label);
        setFocusable(true);
    }

    @Override public void onInnerScale(float x, float y) { ix = x; iy = y; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int s = Math.round(SIZE * dp);
        setMeasuredDimension(s, s);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        setPivotX(w / 2f);
        setPivotY(h / 2f);
    }

    @Override float shape(RectF out) {
        out.set(0, 0, getWidth(), getHeight());
        return getWidth() / 2f;
    }

    @Override void drawContent(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, half = ICON * dp / 2;
        c.save();
        c.scale(ix, iy, cx, cy);
        icon.setBounds(Math.round(cx - half), Math.round(cy - half), Math.round(cx + half), Math.round(cy + half));
        icon.setTint(color.value());
        icon.draw(c);
        c.restore();
        if (color.running()) postInvalidateOnAnimation();
    }

    private void setHot(boolean hot) {
        color.to(hot ? ACTIVE : textColor, hot ? 250 : 450);
        invalidate();
    }

    private boolean over(MotionEvent e) {
        return e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stretchy.down(e);
                setHot(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                stretchy.move(e);
                setHot(over(e));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean ok = e.getActionMasked() == MotionEvent.ACTION_UP && over(e);
                stretchy.up();
                setHot(false);
                if (ok) performClick();
                return true;
            }
        }
        return false;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (action != null) action.run();
        return true;
    }
}
