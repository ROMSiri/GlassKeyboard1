package dev.glasskeyboard.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Root of the settings screen. Its first {@link #sources} children (the scrolling content) are
 * recorded into one RenderNode that the glass views above draw again, blurred: a backdrop-filter.
 */
final class GlassHost extends FrameLayout {
    int sources;
    private final RenderNode content = Build.VERSION.SDK_INT >= 31 ? new RenderNode("backdrop") : null;
    private RecordingCanvas rec;
    private boolean ready;

    GlassHost(Context c) { super(c); }

    /** The recorded content, or null when there is no blur (Android 11 and older, software drawing). */
    RenderNode backdrop() { return ready ? content : null; }

    @Override protected boolean drawChild(Canvas c, View child, long time) {
        int i = indexOfChild(child);
        if (content == null || !c.isHardwareAccelerated() || i >= sources) return super.drawChild(c, child, time);
        if (i == 0) {
            content.setPosition(0, 0, getWidth(), getHeight());
            rec = content.beginRecording();
        }
        boolean more = super.drawChild(rec != null ? rec : c, child, time);
        if (i == sources - 1 && rec != null) {
            content.endRecording();
            rec = null;
            c.drawRenderNode(content);
            ready = true;
        }
        return more;
    }
}
