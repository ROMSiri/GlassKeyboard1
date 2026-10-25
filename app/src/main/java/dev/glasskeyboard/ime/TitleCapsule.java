package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/** Top glass title capsule: width follows the text with a spring; old title floats up, new one rises. */
final class TitleCapsule extends GlassView implements Stretchy.Inner {
    private static final float HEIGHT = 48, MIN_W = 180, PAD = 48, SIDE = 72;

    private static final class Line {
        final String text;
        final Tween alpha, offset, scale;    // offset: -1 floated up, 0 centre, 1 below
        boolean out;
        Line(String text, boolean instant) {
            this.text = text;
            alpha = new Tween(instant ? 1 : 0);
            offset = new Tween(instant ? 0 : 1);
            scale = new Tween(instant ? 1 : .85f);
        }
    }

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Line> lines = new ArrayList<>();
    private final Tween width;
    private final Stretchy stretchy;
    private float ix = 1, iy = 1;
    private Line now;

    TitleCapsule(Context c, boolean dark) {
        super(c, dark);
        paint.setTextSize(15 * dp);
        paint.setTypeface(weight(600));
        paint.setTextAlign(Paint.Align.CENTER);
        width = new Tween(MIN_W * dp);
        stretchy = new Stretchy(this, 1.06f, 18, this);
    }

    @Override public void onInnerScale(float x, float y) { ix = x; iy = y; }

    String title() { return now != null ? now.text : ""; }

    void setTitle(String text, boolean instant) {
        if (now != null && now.text.equals(text)) return;
        Line next = new Line(text, instant);
        lines.add(next);
        // width follows the new text
        float w = Math.max(MIN_W * dp, paint.measureText(text) + 2 * PAD * dp);
        if (instant) width.jump(w); else width.to(w, 0, 600, Tween.SPRING);
        if (now != null) {
            Line old = now;
            old.out = true;
            old.alpha.to(0, 0, 400, Tween.EASE);
            old.offset.to(-1, 0, 550, Tween.SPRING);
            old.scale.to(.85f, 0, 550, Tween.SPRING);
        }
        now = next;
        if (!instant) {
            next.alpha.to(1, 0, 400, Tween.EASE);
            next.offset.to(0, 0, 550, Tween.SPRING);
            next.scale.to(1, 0, 550, Tween.SPRING);
        }
        setContentDescription(text);
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(getDefaultSize(0, wSpec), Math.round(HEIGHT * dp));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        setPivotX(w / 2f);               // transform-origin: 50% 0
        setPivotY(0);
    }

    private float maxWidth() { return Math.max(MIN_W * dp, getWidth() - 2 * SIDE * dp); }

    @Override float shape(RectF out) {
        float w = Math.min(width.value(), maxWidth()), cx = getWidth() / 2f;
        out.set(cx - w / 2, 0, cx + w / 2, getHeight());
        return out.height() / 2;
    }

    @Override void drawContent(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float boxH = fm.descent - fm.ascent, baseFromCentre = -(fm.ascent + fm.descent) / 2;
        float avail = maxWidth() - 2 * 16 * dp;
        boolean anim = width.running();
        c.save();
        c.scale(ix, iy, cx, cy);             // text stretches with the capsule
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            float a = l.alpha.value();
            if (l.out && !l.alpha.running()) {
                lines.remove(i--);
                continue;
            }
            anim |= l.alpha.running() || l.offset.running() || l.scale.running();
            float y = cy + l.offset.value() * .6f * boxH, sc = l.scale.value();
            float sigma = 4 * (1 - Math.max(0f, Math.min(1f, a))) * dp;
            paint.setMaskFilter(sigma >= .9f * dp ? new BlurMaskFilter(radiusFor(sigma), BlurMaskFilter.Blur.NORMAL) : null);
            paint.setColor(textColor);
            paint.setAlpha(Math.round(Math.max(0f, Math.min(1f, a)) * 255));
            c.save();
            c.scale(sc, sc, cx, y);
            CharSequence s = TextUtils.ellipsize(l.text, paint, avail, TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), cx, y + baseFromCentre, paint);
            c.restore();
        }
        c.restore();
        paint.setMaskFilter(null);
        if (anim) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inShape(e.getX(), e.getY())) return false;
                stretchy.down(e);
                return true;
            case MotionEvent.ACTION_MOVE: stretchy.move(e); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: stretchy.up(); return true;
        }
        return false;
    }
}
