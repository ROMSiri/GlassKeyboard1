package dev.glasskeyboard.ime;

import android.animation.TimeInterpolator;
import android.os.SystemClock;
import android.view.animation.PathInterpolator;

/** A CSS-like transition of one number: retargeting starts from the current value. */
final class Tween {
    /** cubic-bezier(.34, 1.25, .5, 1): overshoots a little and settles softly. */
    static final TimeInterpolator SPRING = new PathInterpolator(.34f, 1.25f, .5f, 1f);
    /** CSS "ease". */
    static final TimeInterpolator EASE = new PathInterpolator(.25f, .1f, .25f, 1f);

    private float from, to;
    private long start, delay, duration;
    private TimeInterpolator interpolator = EASE;

    Tween(float value) { from = to = value; }

    float target() { return to; }

    void to(float target, long delayMs, long durationMs, TimeInterpolator in) {
        if (target == to) return;
        from = value();
        to = target;
        start = SystemClock.uptimeMillis();
        delay = delayMs;
        duration = durationMs;
        interpolator = in;
    }

    void jump(float value) { from = to = value; duration = 0; }

    float value() {
        long t = SystemClock.uptimeMillis() - start - delay;
        if (duration <= 0 || t >= duration) return to;
        if (t < 0) return from;
        return from + (to - from) * interpolator.getInterpolation(t / (float) duration);
    }

    boolean running() { return duration > 0 && SystemClock.uptimeMillis() < start + delay + duration; }

    /** Colour transition (CSS "color .45s ease"). */
    static final class Color {
        private int from, to;
        private final Tween t = new Tween(1);

        Color(int value) { from = to = value; }

        int target() { return to; }

        void to(int target, long durationMs) {
            if (target == to) return;
            from = value();
            to = target;
            t.jump(0);
            t.to(1, 0, durationMs, EASE);
        }

        int value() { return mix(from, to, t.value()); }
        boolean running() { return t.running(); }
    }

    static int mix(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int aa = a >>> 24, ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int ba = b >>> 24, br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

    static float lerp(float a, float b, float f) { return a + (b - a) * f; }
}
