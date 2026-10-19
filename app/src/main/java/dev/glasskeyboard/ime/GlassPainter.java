package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Draws glass surfaces: keys, round toolbar buttons and the panel rim.
 * Android 13+ uses the AGSL material shader; older versions get a gradient approximation.
 */
final class GlassPainter {
    private static final String TAG = "GlassPainter";

    /** Visual style of one surface. */
    static final class Style {
        float exponent = 2f;
        float glowAlpha, glowWidth, glowOffsetY, glowScaleX = 1f, glowScaleY = 1f;
        float edgeAlpha, edgeWidth, edgeFloor = .2f;
        float lightX = -.6f, lightY = -.8f;
    }

    private final float density;
    private final Object shader; // RuntimeShader on API 33+, null otherwise
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final android.graphics.Path rimPath = new android.graphics.Path();

    final Style key = new Style();
    final Style round = new Style();
    final Style panel = new Style();
    final Style bubble = new Style();

    GlassPainter(Context context, boolean dark) {
        density = context.getResources().getDisplayMetrics().density;
        Object created = null;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                RuntimeShader s = new RuntimeShader(readShader(context));
                shaderPaint.setShader(s);
                created = s;
            } catch (Throwable error) {
                Log.w(TAG, "AGSL material unavailable, using fallback", error);
            }
        }
        shader = created;
        rimPaint.setStyle(Paint.Style.STROKE);
        setDark(dark);
    }

    boolean hasShader() { return shader != null; }

    void setDark(boolean dark) {
        // Keys: values follow the ColorOS 17 style-2 key (white inner light + white rim).
        key.exponent = 2.6f;
        key.glowAlpha = 0f;   // flat keys (no inner light / rim)
        key.glowWidth = 22f * density;
        key.glowOffsetY = -3.5f * density;
        key.glowScaleX = 1.35f;
        key.glowScaleY = 1.12f;
        key.edgeAlpha = 0f;
        key.edgeWidth = 1.4f * density;
        key.edgeFloor = .22f;

        // Round toolbar buttons: stronger, wider inner light, like the Xiaobu toolbar variant.
        round.exponent = 2f;
        round.glowAlpha = 0f;
        round.glowWidth = 16f * density;
        round.glowOffsetY = -2.5f * density;
        round.glowScaleX = 1.25f;
        round.glowScaleY = 1.2f;
        round.edgeAlpha = 0f;
        round.edgeWidth = 1.3f * density;
        round.edgeFloor = .25f;

        // Panel: rim only, lit from the top, drawn additively.
        panel.exponent = 3f;
        panel.glowAlpha = 0f;
        panel.edgeAlpha = dark ? .45f : .9f;
        panel.edgeWidth = 1.6f * density;
        panel.edgeFloor = .05f;
        panel.lightX = 0f;
        panel.lightY = -1f;

        // Key preview bubble.
        bubble.exponent = 2.6f;
        bubble.glowAlpha = 0f;
        bubble.glowWidth = 26f * density;
        bubble.glowOffsetY = -4f * density;
        bubble.glowScaleX = 1.3f;
        bubble.glowScaleY = 1.1f;
        bubble.edgeAlpha = 0f;
        bubble.edgeWidth = 1.4f * density;
        bubble.edgeFloor = .25f;
    }

    /** Fill + inner light + rim inside {@code bounds}. */
    void draw(Canvas canvas, RectF bounds, float radius, int fill, Style style, float alpha) {
        float w = bounds.width(), h = bounds.height();
        if (w < 1f || h < 1f) return;
        if (shader != null && Build.VERSION.SDK_INT >= 33) {
            RuntimeShader s = (RuntimeShader) shader;
            s.setFloatUniform("uSize", w, h);
            s.setFloatUniform("uRadius", radius);
            s.setFloatUniform("uExponent", style.exponent);
            s.setFloatUniform("uFill", Color.red(fill) / 255f, Color.green(fill) / 255f,
                    Color.blue(fill) / 255f, Color.alpha(fill) / 255f);
            s.setFloatUniform("uGlow", 1f, 1f, 1f, style.glowAlpha);
            s.setFloatUniform("uGlowWidth", Math.max(1f, style.glowWidth));
            s.setFloatUniform("uGlowOffset", 0f, style.glowOffsetY);
            s.setFloatUniform("uGlowScale", style.glowScaleX, style.glowScaleY);
            s.setFloatUniform("uEdge", 1f, 1f, 1f, style.edgeAlpha);
            s.setFloatUniform("uEdgeWidth", Math.max(.5f, style.edgeWidth));
            s.setFloatUniform("uLight", style.lightX, style.lightY);
            s.setFloatUniform("uEdgeFloor", style.edgeFloor);
            shaderPaint.setAlpha(Math.round(255 * clamp(alpha)));
            int save = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.drawRect(0f, 0f, w, h, shaderPaint);
            canvas.restoreToCount(save);
        } else {
            drawFallback(canvas, bounds, radius, fill, style, alpha);
        }
    }

    /** Additive rim along the top of the docked panel; bottom corners are clipped away. */
    /**
     * Thin white line along the top edge of the panel that follows the rounded top corners and
     * fades out down the sides.
     */
    void drawPanelRim(Canvas canvas, float width, float height, float topRadius) {
        float sw = 1.2f * density, h = sw / 2f;
        float r = Math.max(0f, topRadius - h);
        float fadeTo = Math.min(height, Math.max(topRadius * 1.6f, 24f * density));
        android.graphics.Path p = rimPath;
        p.reset();
        p.moveTo(h, fadeTo);
        p.lineTo(h, h + r);
        if (r > 0f) p.arcTo(h, h, h + 2 * r, h + 2 * r, 180f, 90f, false);
        p.lineTo(width - h - r, h);
        if (r > 0f) p.arcTo(width - h - 2 * r, h, width - h, h + 2 * r, 270f, 90f, false);
        p.lineTo(width - h, fadeTo);
        int top = Color.argb(Math.round(255 * panel.edgeAlpha), 255, 255, 255);
        rimPaint.setShader(new LinearGradient(0f, 0f, 0f, fadeTo,
                new int[]{top, top, top & 0x00ffffff},
                new float[]{0f, Math.min(.95f, (topRadius * .9f) / fadeTo), 1f}, Shader.TileMode.CLAMP));
        rimPaint.setStrokeWidth(sw);
        canvas.drawPath(p, rimPaint);
        rimPaint.setShader(null);
    }

    private void drawFallback(Canvas canvas, RectF b, float radius, int fill, Style style, float alpha) {
        int a = Math.round(255 * clamp(alpha));
        fillPaint.setShader(null);
        fillPaint.setColor(fill);
        fillPaint.setAlpha(Color.alpha(fill) * a / 255);
        canvas.drawRoundRect(b, radius, radius, fillPaint);
        if (style.glowAlpha > 0f) {
            int glow = Math.round(255 * style.glowAlpha * alpha);
            fillPaint.setShader(new LinearGradient(0f, b.bottom - style.glowWidth, 0f, b.bottom,
                    Color.argb(0, 255, 255, 255), Color.argb(glow, 255, 255, 255), Shader.TileMode.CLAMP));
            fillPaint.setAlpha(255);
            canvas.drawRoundRect(b, radius, radius, fillPaint);
            fillPaint.setShader(null);
        }
        if (style.edgeAlpha > 0f) {
            int hi = Math.round(255 * style.edgeAlpha * alpha);
            int lo = Math.round(255 * style.edgeAlpha * style.edgeFloor * alpha);
            rimPaint.setStrokeWidth(Math.max(1f, style.edgeWidth * .7f));
            rimPaint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{Color.argb(hi, 255, 255, 255), Color.argb(lo, 255, 255, 255),
                            Color.argb(hi * 4 / 5, 255, 255, 255)},
                    new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
            float inset = rimPaint.getStrokeWidth() / 2f;
            tmp.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            canvas.drawRoundRect(tmp, radius, radius, rimPaint);
            rimPaint.setShader(null);
        }
    }

    private static float clamp(float v) { return v < 0f ? 0f : Math.min(1f, v); }

    private static String readShader(Context context) throws Exception {
        try (InputStream in = context.getResources().openRawResource(R.raw.glass_material)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
