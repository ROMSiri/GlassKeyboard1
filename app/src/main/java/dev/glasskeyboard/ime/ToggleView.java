package dev.glasskeyboard.ime;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Pill switch in the ColorOS / iOS style with an animated thumb. */
final class ToggleView extends View {
    interface Listener { void onChanged(boolean on); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private final int accent, offTrack;
    private boolean checked;
    private float pos;                 // 0 off .. 1 on
    private ValueAnimator anim;
    private Listener listener;

    ToggleView(Context c, int accent, boolean dark) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        this.accent = accent;
        offTrack = dark ? 0xff3a3a3f : 0xffdcdce1;
        setOnClickListener(v -> toggle());
    }

    void setListener(Listener l) { listener = l; }
    boolean isChecked() { return checked; }

    void setChecked(boolean on, boolean animate) {
        if (on == checked && (anim == null || !anim.isRunning())) { pos = on ? 1 : 0; invalidate(); return; }
        checked = on;
        if (anim != null) anim.cancel();
        if (!animate) { pos = on ? 1 : 0; invalidate(); return; }
        anim = ValueAnimator.ofFloat(pos, on ? 1 : 0);
        anim.setDuration(180);
        anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    void toggle() {
        setChecked(!checked, true);
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.onChanged(checked);
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(52 * dp), Math.round(32 * dp));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        r.set(1 * dp, 1 * dp, w - 1 * dp, h - 1 * dp);
        paint.setColor(blend(offTrack, accent, pos));
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint);
        float rad = r.height() / 2f - 2.5f * dp;
        float x = r.left + 2.5f * dp + rad + pos * (r.width() - 5 * dp - 2 * rad);
        paint.setColor(0x22000000);
        c.drawCircle(x, r.centerY() + 1 * dp, rad, paint);
        paint.setColor(0xffffffff);
        c.drawCircle(x, r.centerY(), rad, paint);
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xff000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }
}
