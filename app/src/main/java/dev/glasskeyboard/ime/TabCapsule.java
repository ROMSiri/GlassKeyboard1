package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

/**
 * Bottom glass capsule with tabs. The finger can slide across it: blue moves to the tab under
 * the finger and stays where the finger is lifted. Shrinks to icons while the page scrolls down.
 */
final class TabCapsule extends GlassView implements Stretchy.Inner {
    interface Listener { void onTab(int index); }

    // a little larger than the mock-up, and stretched across nearly the whole bottom edge
    private static final float ICON = 24, LABEL_LINE = 15, LABEL_SIZE = 12, SIDE = 12, MAX_W = 520, COMPACT_TAB = 64;

    private final String[] labels;
    private final Drawable[] icons;
    private final float[] labelW;
    private final Tween.Color[] colors;
    private final Listener listener;
    private final Stretchy stretchy;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    // size: 0 full .. 1 compact (padding, gaps, min-width); label: height, opacity+blur, shrink
    private final Tween size = new Tween(0), labelH = new Tween(1), labelA = new Tween(1), labelT = new Tween(0);
    private final RectF tmp = new RectF();
    private int active, hot = -1;
    private boolean dragging, compact;
    private float ix = 1, iy = 1;
    private BlurMaskFilter blur;
    private float blurFor = -1;

    TabCapsule(Context c, boolean dark, String[] labels, Drawable[] icons, int active, Listener listener) {
        super(c, dark);
        this.labels = labels;
        this.icons = icons;
        this.active = active;
        this.listener = listener;
        text.setTextSize(LABEL_SIZE * dp);
        text.setTypeface(weight(500));
        text.setTextAlign(Paint.Align.CENTER);
        labelW = new float[labels.length];
        colors = new Tween.Color[labels.length];
        for (int i = 0; i < labels.length; i++) {
            labelW[i] = text.measureText(labels[i]) / dp;
            colors[i] = new Tween.Color(i == active ? ACTIVE : textColor);
        }
        stretchy = new Stretchy(this, 1.05f, 22, this);
        setContentDescription("Нижняя навигация");
    }

    @Override public void onInnerScale(float x, float y) { ix = x; iy = y; }

    void setActive(int index) {
        if (index == active) return;
        active = index;
        updateColors();
        invalidate();
    }

    /** Compact while scrolling down; full near the top, when scrolling up or when touched. */
    void setCompact(boolean on) {
        if (on == compact) return;
        compact = on;
        if (on) {
            size.to(1, 0, 600, Tween.SPRING);
            labelH.to(0, 40, 600, Tween.SPRING);
            labelA.to(0, 0, 350, Tween.EASE);
            labelT.to(1, 0, 500, Tween.SPRING);
        } else {
            // room first, then the label comes out of a slight blur and grows
            size.to(0, 0, 600, Tween.SPRING);
            labelH.to(1, 0, 600, Tween.SPRING);
            labelA.to(1, 100, 450, Tween.EASE);
            labelT.to(0, 50, 600, Tween.SPRING);
        }
        invalidate();
    }

    /** Height of the full capsule, for content padding. */
    int fullHeight() { return Math.round((2 * 5 + 2 + 2 * 9 + ICON + 3 + LABEL_LINE) * dp); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        // the view spans the width (+ room for the spring's overshoot); the capsule is drawn inside
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), wSpec), fullHeight() + Math.round(4 * dp));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        setPivotX(w / 2f);               // transform-origin: 50% 100%
        setPivotY(h);
    }

    // ---- geometry (dp, from the current animation values)

    private float s() { return size.value(); }
    private float pad() { return Tween.lerp(5, 4, s()); }
    private float gap() { return Tween.lerp(2, 0, s()); }
    /** Full: tabs share the whole width equally. Compact: shrinks to the icons. */
    private float tabW(int i) {
        float avail = Math.min(MAX_W, getWidth() / dp - 2 * SIDE);
        float full = (avail - 2 * 5 - 2 - (labels.length - 1) * 2) / labels.length;
        return Tween.lerp(Math.max(full, Math.max(ICON, labelW[i]) + 12), COMPACT_TAB, s());
    }
    private float tabH() {
        return 2 * 9 + ICON + Tween.lerp(3, 0, s()) + LABEL_LINE * labelH.value();
    }

    @Override float shape(RectF out) {
        float w = 2 * pad() + 2 + (labels.length - 1) * gap();
        for (int i = 0; i < labels.length; i++) w += tabW(i);
        float h = 2 * pad() + 2 + tabH();
        float cx = getWidth() / 2f;
        out.set(cx - w * dp / 2, getHeight() - h * dp, cx + w * dp / 2, getHeight());
        return out.height() / 2;
    }

    /** Index of the tab at a local point, or -1 (padding and gaps are not tabs). */
    private int tabAt(float x, float y) {
        shape(tmp);
        float top = tmp.top + (1 + pad()) * dp, bottom = top + tabH() * dp;
        if (y < top || y > bottom) return -1;
        float left = tmp.left + (1 + pad()) * dp;
        for (int i = 0; i < labels.length; i++) {
            float w = tabW(i) * dp;
            if (x >= left && x < left + w) return i;
            left += w + gap() * dp;
        }
        return -1;
    }

    @Override void drawContent(Canvas c) {
        RectF r = tmp;
        shape(r);
        float padV = 9, tgap = Tween.lerp(3, 0, s());
        float a = labelA.value(), t = labelT.value();
        float top = r.top + (1 + pad()) * dp;
        float left = r.left + (1 + pad()) * dp;
        float blurPx = radiusFor(3 * (1 - a) * dp);
        if (blurPx != blurFor) { blurFor = blurPx; blur = blurPx >= .5f ? new BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) : null; }
        Paint.FontMetrics fm = text.getFontMetrics();
        boolean anim = size.running() || labelH.running() || labelA.running() || labelT.running();
        for (int i = 0; i < labels.length; i++) {
            float w = tabW(i) * dp, cx = left + w / 2;
            int color = colors[i].value();
            anim |= colors[i].running();
            // icon: only stretches with the capsule, never rotates
            float iconTop = top + padV * dp, half = ICON * dp / 2;
            c.save();
            c.scale(ix, iy, cx, iconTop + half);
            icons[i].setBounds(Math.round(cx - half), Math.round(iconTop), Math.round(cx + half), Math.round(iconTop + 2 * half));
            icons[i].setTint(color);
            icons[i].draw(c);
            c.restore();
            // label: fades into a blur and pulls up into the icon when hidden
            if (a > .004f) {
                float boxTop = iconTop + (ICON + tgap) * dp;
                float base = boxTop + (LABEL_LINE * dp - (fm.descent - fm.ascent)) / 2 - fm.ascent;
                c.save();
                c.translate(0, -5 * t * dp);
                float sc = 1 - .45f * t;
                c.scale(sc, sc, cx, boxTop);
                text.setColor(color);
                text.setAlpha(Math.round(Math.min(1f, Math.max(0f, a)) * 255));
                text.setMaskFilter(blur);
                c.drawText(labels[i], cx, base, text);
                c.restore();
            }
            left += w + gap() * dp;
        }
        if (anim) postInvalidateOnAnimation();
    }

    private void setHot(int i) {
        if (i == hot) return;
        hot = i;                         // old one fades slowly, the new one lights up quickly
        updateColors();
    }

    private void updateColors() {
        for (int i = 0; i < labels.length; i++) {
            // while the finger is down, blue moves to the tab under it
            int target = i == hot || (i == active && !dragging) ? ACTIVE : textColor;
            colors[i].to(target, i == hot ? 250 : 450);
        }
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inShape(e.getX(), e.getY())) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                stretchy.down(e);
                dragging = true;
                setCompact(false);       // full size while held
                hot = tabAt(e.getX(), e.getY());
                updateColors();
                return true;
            case MotionEvent.ACTION_MOVE:
                stretchy.move(e);
                setHot(tabAt(e.getX(), e.getY()));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean commit = e.getActionMasked() == MotionEvent.ACTION_UP;
                stretchy.up();
                int t = commit ? tabAt(e.getX(), e.getY()) : -1;
                dragging = false;
                if (t >= 0) active = t;  // blue stays where the finger was lifted
                hot = -1;
                updateColors();          // missed — blue goes back to the previous tab
                if (t >= 0) {
                    performClick();
                    if (listener != null) listener.onTab(t);
                }
                return true;
            }
        }
        return false;
    }

    @Override public boolean performClick() { return super.performClick(); }
}
