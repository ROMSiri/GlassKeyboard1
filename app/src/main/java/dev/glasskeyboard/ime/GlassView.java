package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;

/**
 * Glass element from the mock-up: layer 1 frosted glass (blur 24px, saturate 140%),
 * layer 2 white / black translucent tint, 1px hairline. Subclasses give the shape and the content.
 */
abstract class GlassView extends View {
    static final int ACTIVE = 0xff0a6cff;

    final float dp;
    final boolean dark;
    final int textColor;
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF(), edge = new RectF();
    private final Path clip = new Path();
    private final Matrix inverse = new Matrix();
    private RenderNode blurred;
    private int recX = Integer.MIN_VALUE, recY, recW, recH;

    GlassView(Context c, boolean dark) {
        super(c);
        this.dark = dark;
        dp = c.getResources().getDisplayMetrics().density;
        textColor = dark ? 0xffffffff : 0xff000000;
        boolean blur = Build.VERSION.SDK_INT >= 31;
        // without blur the tint is denser so text stays readable over the content
        tint.setColor(dark ? (blur ? 0xc7000000 : 0xf0101012) : (blur ? 0x8cffffff : 0xf0f8f8fa));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp);
        stroke.setColor(dark ? 0x0fffffff : 0x14000000);
    }

    /** Fills {@code out} with the glass shape in local coordinates; returns its corner radius. */
    abstract float shape(RectF out);

    abstract void drawContent(Canvas c);

    static Typeface weight(int w) { return Typeface.create(Typeface.DEFAULT, w, false); }

    /** CSS blur() is a standard deviation; Android blur radii are converted with this inverse. */
    static float radiusFor(float sigmaPx) { return Math.max(0f, (sigmaPx - .5f) / .57735f); }

    boolean inShape(float x, float y) {
        shape(shape);
        return shape.contains(x, y);
    }

    @Override protected void onDraw(Canvas c) {
        float r = shape(shape);
        int save = c.save();
        clip.reset();
        clip.addRoundRect(shape, r, r, Path.Direction.CW);
        c.clipPath(clip);
        drawBackdrop(c);
        c.drawRect(shape, tint);
        drawContent(c);
        c.restoreToCount(save);
        edge.set(shape);
        edge.inset(dp / 2, dp / 2);
        float er = Math.max(0, r - dp / 2);
        c.drawRoundRect(edge, er, er, stroke);
    }

    private void drawBackdrop(Canvas c) {
        if (Build.VERSION.SDK_INT < 31 || !c.isHardwareAccelerated()) return;
        GlassHost host = null;
        int x = getLeft(), y = getTop();
        for (ViewParent p = getParent(); p instanceof View; p = p.getParent()) {
            if (p instanceof GlassHost) { host = (GlassHost) p; break; }
            View v = (View) p;
            x += v.getLeft() + Math.round(v.getTranslationX());
            y += v.getTop() + Math.round(v.getTranslationY());
        }
        RenderNode content = host != null ? host.backdrop() : null;
        if (content == null) return;
        int m = Math.round(48 * dp);                      // room for the blur to reach in from outside
        int w = getWidth() + 2 * m, h = getHeight() + 2 * m;
        if (blurred == null) {
            blurred = new RenderNode("glass");
            ColorMatrix sat = new ColorMatrix();
            sat.setSaturation(1.4f);
            float br = radiusFor(24 * dp);
            blurred.setRenderEffect(RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(sat),
                    RenderEffect.createBlurEffect(br, br, Shader.TileMode.CLAMP)));
        }
        if (x != recX || y != recY || w != recW || h != recH) {
            recX = x; recY = y; recW = w; recH = h;
            blurred.setPosition(0, 0, w, h);
            RecordingCanvas rc = blurred.beginRecording();
            rc.translate(m - x, m - y);
            rc.drawRenderNode(content);
            blurred.endRecording();
        }
        c.save();
        // the backdrop stays put while the glass itself stretches and grows
        Matrix mx = getMatrix();
        if (!mx.isIdentity() && mx.invert(inverse)) c.concat(inverse);
        c.translate(-m, -m);
        c.drawRenderNode(blurred);
        c.restore();
    }
}
