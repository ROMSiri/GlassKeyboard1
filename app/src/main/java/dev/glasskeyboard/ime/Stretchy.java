package dev.glasskeyboard.ime;

import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * Soft rubber press: the view grows while held, stretches after the finger with growing
 * resistance (round limit, same in every direction) and springs back with a wobble.
 * Same numbers as the HTML mock-up (makeStretchy); CSS px are dp here.
 */
final class Stretchy implements Choreographer.FrameCallback {
    interface Inner { void onInnerScale(float ix, float iy); }

    private static final float TIGHT = .4f;                     // smaller — stiffer pull
    private static final float HOLD_K = 900, HOLD_C = 55;       // held: stiff, follows the finger
    private static final float RELEASE_K = 260, RELEASE_C = 11; // released: soft, wobbles
    private static final int UP = 0, DOWN = 1, SIDE = 2, SHIFT = 3, GROW = 4;

    private final View el;
    private final float grow, radius, dp;
    private final Inner inner;
    private final float[] cur = {0, 0, 0, 0, 1}, vel = new float[5], tgt = {0, 0, 0, 0, 1};
    private boolean running, pressing;
    private long last;
    private float sx, sy;

    Stretchy(View el, float grow, float radiusDp, Inner inner) {
        this.el = el;
        this.grow = grow;
        this.radius = radiusDp;
        this.inner = inner;
        dp = el.getResources().getDisplayMetrics().density;
    }

    static float rubber(float d, float limit) { return limit * (1 - 1 / (Math.abs(d) * TIGHT / limit + 1)); }

    boolean pressing() { return pressing; }

    void down(MotionEvent e) {
        pressing = true;
        sx = e.getRawX();
        sy = e.getRawY();
        setTarget(0, 0, grow);
    }

    void move(MotionEvent e) {
        if (!pressing) return;
        setTarget((e.getRawX() - sx) / dp, (e.getRawY() - sy) / dp, grow);
    }

    /** Springs back to rest. */
    void up() {
        if (!pressing) return;
        pressing = false;
        setTarget(0, 0, 1);
    }

    /** Pops in from {@code fromScale} with the soft release spring (menus). */
    void pop(float fromScale) {
        cur[GROW] = fromScale;
        vel[GROW] = 0;
        setTarget(0, 0, 1);
    }

    private void setTarget(float dx, float dy, float g) {
        // by distance from the touch point — the limit is round
        float dist = (float) Math.hypot(dx, dy);
        float k = dist > 0 ? rubber(dist, radius) / dist : 0;
        float ex = dx * k, ey = dy * k;
        tgt[UP] = Math.max(-ey, 0);
        tgt[DOWN] = Math.max(ey, 0);
        tgt[SIDE] = Math.abs(ex);
        tgt[SHIFT] = ex;
        tgt[GROW] = g;
        if (!running) {
            running = true;
            last = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override public void doFrame(long now) {
        float dt = Math.max(0f, Math.min((now - last) / 1e9f, 1 / 30f));
        last = now;
        float k = pressing ? HOLD_K : RELEASE_K, c = pressing ? HOLD_C : RELEASE_C;
        boolean moving = false;
        for (int i = 0; i < 5; i++) {
            float acc = -k * (cur[i] - tgt[i]) - c * vel[i];
            vel[i] += acc * dt;
            cur[i] += vel[i] * dt;
            if (Math.abs(vel[i]) > .001f || Math.abs(cur[i] - tgt[i]) > .001f) moving = true;
        }
        if (moving || pressing) {
            render();
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            System.arraycopy(tgt, 0, cur, 0, 5);
            java.util.Arrays.fill(vel, 0);
            render();
            running = false;
        }
    }

    private void render() {
        float up = cur[UP], down = cur[DOWN], side = cur[SIDE], shift = cur[SHIFT], g = cur[GROW];
        float vert = up + down;                   // up and down stretch the same way
        el.setScaleY(g * (1 + vert * .004f - side * .0008f));
        el.setScaleX(g * (1 + side * .0018f - vert * .0012f));
        el.setTranslationX(shift * .25f * dp);
        el.setTranslationY((-up * .3f + down * .3f) * dp);
        // icons and text: up/down — narrower and taller, sideways — wider; no rotation
        if (inner != null) inner.onInnerScale(1 - vert * .006f + side * .005f, 1 + vert * .006f - side * .003f);
        el.invalidate();                          // glass backdrop follows the new transform
    }
}
