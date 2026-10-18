package dev.glasskeyboard.ime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draws the whole keyboard (toolbar + keys) on one canvas and handles multi-touch input. */
@SuppressLint("ViewConstructor")
final class KeyboardView extends View implements PanelBlur.Listener {

    interface Actions {
        void onText(String text);
        void onDelete();
        void onEnter();
        void onCursor(int delta);
        void onHide();
        void onPaste();
        void onSettings();
        void onLanguageChanged(String lang);
        void onPickInputMethod();
    }

    static final int SHIFT_OFF = 0, SHIFT_ONCE = 1, SHIFT_LOCK = 2;

    private static final int TOOL_SETTINGS = 1, TOOL_PASTE = 2, TOOL_LEFT = 3, TOOL_RIGHT = 4, TOOL_HIDE = 5;
    private static final long LONG_PRESS_MS = 380, DELETE_DELAY_MS = 400, DELETE_REPEAT_MS = 55,
            RELEASE_FADE_MS = 160, DOUBLE_SHIFT_MS = 320;

    /** A laid-out key or toolbar button. */
    private static final class Cell {
        final Layouts.Key key;    // null for toolbar buttons
        final int tool;
        final RectF rect = new RectF();
        int row;
        boolean pressed;
        long downAt, upAt;
        Cell(Layouts.Key key, int tool) { this.key = key; this.tool = tool; }
    }

    /** State of one finger. */
    private static final class Finger {
        int id;
        Cell cell;
        float downX, downY, lastCursorX;
        boolean longPressed, cursorMode, done;
        List<String> alts;
        int altIndex;
        RectF altRect;
        Runnable longPress, repeat;
    }

    private final Actions actions;
    private final Prefs prefs;
    private final GlassPainter painter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final float dp;

    private final List<Cell> keys = new ArrayList<>();
    private final List<Cell> tools = new ArrayList<>();
    private final SparseArray<Finger> fingers = new SparseArray<>();

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();

    private boolean dark;
    private int accent;
    private String lang;
    private Layouts.Layout layout;
    private int shift = SHIFT_OFF;
    private long lastShiftTap;
    private int enterAction = EditorInfo.IME_ACTION_UNSPECIFIED;
    private PanelBlur.Mode blurMode = PanelBlur.Mode.NONE;
    private int insetBottom;
    private int laidOutWidth = -1;

    // Dimensions
    private final float toolbarH, keyH, rowPitch, hGap, sidePad, topPad, keyRadius, panelRadius;
    private float bottomPad;

    KeyboardView(Context context, Prefs prefs, Actions actions) {
        super(context);
        this.prefs = prefs;
        this.actions = actions;
        dp = context.getResources().getDisplayMetrics().density;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        toolbarH = 46 * dp;
        keyH = 46 * dp;
        rowPitch = 56 * dp;
        hGap = 6 * dp;
        sidePad = 3 * dp;
        topPad = 4 * dp;
        bottomPad = (6 + prefs.bottomOffset()) * dp;
        keyRadius = 10 * dp;
        panelRadius = 28 * dp;

        dark = isNight(context);
        painter = new GlassPainter(context, dark);
        accent = resolveAccent();
        lang = prefs.language();
        layout = letters();

        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        icon.setStrokeWidth(1.7f * dp);

        for (int t : new int[]{TOOL_SETTINGS, TOOL_PASTE, TOOL_LEFT, TOOL_RIGHT, TOOL_HIDE})
            tools.add(new Cell(null, t));

        setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            if (bottom != insetBottom) { insetBottom = bottom; requestLayout(); }
            return insets;
        });
    }

    // ------------------------------------------------------------------ public API

    float panelRadiusPx() { return panelRadius; }
    boolean isDark() { return dark; }

    void setEnterAction(int action) {
        if (enterAction != action) { enterAction = action; invalidate(); }
    }

    /** Switch between letters and numbers/symbols depending on the editor. */
    void setSymbolsMode(boolean symbols) {
        Layouts.Layout next = symbols ? Layouts.symbols() : letters();
        if (!next.id.equals(layout.id)) { layout = next; relayout(); }
    }

    /** Auto-capitalization from the editor; never overrides caps lock. */
    void setAutoShift(boolean caps) {
        if (shift == SHIFT_LOCK || !isLetterLayout()) return;
        int next = caps ? SHIFT_ONCE : SHIFT_OFF;
        if (next != shift) { shift = next; invalidate(); }
    }

    void resetState() {
        cancelAllFingers();
        if (shift != SHIFT_LOCK) shift = SHIFT_OFF;
        requestLayout();
        invalidate();
    }

    @Override public void onBlurModeChanged(PanelBlur.Mode mode, String detail) {
        blurMode = mode;
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    private Layouts.Layout letters() { return "en".equals(lang) ? Layouts.english() : Layouts.russian(); }

    private boolean isLetterLayout() { return "ru".equals(layout.id) || "en".equals(layout.id); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int rows = layout.rows.size();
        bottomPad = (6 + prefs.bottomOffset()) * dp; // re-read: may change in settings
        int height = Math.round(topPad + toolbarH + rows * rowPitch + bottomPad) + insetBottom;
        setMeasuredDimension(width, height);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    @Override protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        boolean night = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night != dark) {
            dark = night;
            painter.setDark(dark);
            accent = resolveAccent();
            invalidate();
        }
    }

    private void relayout() {
        int w = getWidth();
        if (w <= 0) return;
        laidOutWidth = w;
        keys.clear();
        float unit = (w - 2 * sidePad) / layout.maxWeight;
        float y = topPad + toolbarH;
        for (int r = 0; r < layout.rows.size(); r++) {
            List<Layouts.Key> row = layout.rows.get(r);
            float rowWeight = 0f;
            for (Layouts.Key k : row) rowWeight += k.weight;
            float x = sidePad + (layout.maxWeight - rowWeight) / 2f * unit;
            for (Layouts.Key k : row) {
                Cell c = new Cell(k, 0);
                c.row = r;
                float kw = k.weight * unit;
                c.rect.set(x + hGap / 2f, y + (rowPitch - keyH) / 2f, x + kw - hGap / 2f, y + (rowPitch + keyH) / 2f);
                keys.add(c);
                x += kw;
            }
            y += rowPitch;
        }
        // Toolbar: two buttons on the left, three on the right.
        float size = 36 * dp, gap = 8 * dp, cy = topPad + toolbarH / 2f;
        float left = sidePad + 6 * dp, right = w - sidePad - 6 * dp;
        for (Cell t : tools) {
            float cx;
            switch (t.tool) {
                case TOOL_SETTINGS: cx = left + size / 2f; break;
                case TOOL_PASTE: cx = left + size * 1.5f + gap; break;
                case TOOL_LEFT: cx = right - size * 2.5f - gap * 2; break;
                case TOOL_RIGHT: cx = right - size * 1.5f - gap; break;
                default: cx = right - size / 2f; break;
            }
            t.rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        }
        invalidate();
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas canvas) {
        if (laidOutWidth != getWidth()) relayout();
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        float w = getWidth(), h = getHeight();

        drawPanel(canvas, w, h);

        for (Cell t : tools) animating |= drawTool(canvas, t, now);
        for (Cell c : keys) animating |= drawKey(canvas, c, now);

        Finger top = topFinger();
        if (top != null && top.alts != null) drawAlternatives(canvas, top);
        else if (top != null && prefs.preview() && top.cell != null && top.cell.key != null
                && top.cell.key.kind == Layouts.Kind.CHAR && !top.cursorMode) drawPreview(canvas, top.cell);

        if (animating) postInvalidateOnAnimation();
    }

    private void drawPanel(Canvas canvas, float w, float h) {
        // Window blur is clipped to the window outline (all four corners rounded), so the tint matches it.
        boolean windowBlur = blurMode == PanelBlur.Mode.WINDOW;
        float r = panelRadius;
        if (blurMode != PanelBlur.Mode.OPLUS) {
            int opacity = blurMode == PanelBlur.Mode.NONE ? Math.max(prefs.opacity(), 95) : prefs.opacity();
            panelPaint.setColor(PanelBlur.tint(dark, opacity));
            path.reset();
            float b = windowBlur ? r : 0f;
            path.addRoundRect(0f, 0f, w, h, new float[]{r, r, r, r, b, b, b, b}, Path.Direction.CW);
            canvas.drawPath(path, panelPaint);
        }
        painter.drawPanelRim(canvas, w, h, r);
    }

    private float pressAmount(Cell c, long now) {
        if (c.pressed) return Math.min(1f, (now - c.downAt) / 50f);
        if (c.upAt == 0) return 0f;
        float t = 1f - (now - c.upAt) / (float) RELEASE_FADE_MS;
        if (t <= 0f) { c.upAt = 0; return 0f; }
        return t * t;
    }

    private boolean drawTool(Canvas canvas, Cell t, long now) {
        float p = pressAmount(t, now);
        int fill = lerpColor(dark ? 0x26ffffff : 0x8affffff, dark ? 0x3dffffff : 0x85bbbec2, p);
        scaled(t.rect, 1f - .06f * p);
        painter.draw(canvas, tmp, tmp.width() / 2f, fill, painter.round, 1f);
        icon.setColor(dark ? 0xfff2f2f2 : 0xff4a4a4f);
        drawToolIcon(canvas, t.tool, tmp.centerX(), tmp.centerY());
        return t.pressed || t.upAt != 0;
    }

    private boolean drawKey(Canvas canvas, Cell c, long now) {
        Layouts.Key k = c.key;
        float p = pressAmount(c, now);
        int normal, pressed;
        int fg = dark ? 0xfff2f2f2 : 0xff1d1d1f;
        switch (k.kind) {
            case ACCENT:
                normal = (accent & 0x00ffffff) | 0x99000000;
                pressed = (accent & 0x00ffffff) | 0xc0000000;
                fg = 0xffffffff;
                break;
            case FUNCTION:
                boolean active = k.code == Layouts.SHIFT && shift != SHIFT_OFF;
                normal = active ? (dark ? 0x40ffffff : 0xd9ffffff) : (dark ? 0x14ffffff : 0x4dffffff);
                pressed = dark ? 0x3dffffff : 0x85bbbec2;
                break;
            default:
                normal = dark ? 0x26ffffff : 0x8affffff;
                pressed = dark ? 0x3dffffff : 0x85bbbec2;
        }
        int fill = lerpColor(normal, pressed, p);
        scaled(c.rect, 1f - .045f * p);
        painter.draw(canvas, tmp, Math.min(keyRadius, tmp.height() / 2f), fill, painter.key, 1f);
        drawKeyContent(canvas, c, tmp, fg);
        return c.pressed || c.upAt != 0;
    }

    private void drawKeyContent(Canvas canvas, Cell c, RectF r, int fg) {
        Layouts.Key k = c.key;
        float cx = r.centerX(), cy = r.centerY();
        icon.setColor(fg);
        switch (k.code) {
            case Layouts.SHIFT: drawShift(canvas, cx, cy); return;
            case Layouts.DELETE: drawDelete(canvas, cx, cy); return;
            case Layouts.ENTER: drawEnter(canvas, cx, cy); return;
            case Layouts.LANG: drawGlobe(canvas, cx, cy); return;
            default: break;
        }
        if (k.kind == Layouts.Kind.SPACE) {
            if (isLetterLayout()) {
                text.setColor(withAlpha(fg, .45f));
                text.setTextSize(sp(13));
                drawCentered(canvas, "en".equals(lang) ? "English" : "Русский", cx, cy);
            }
            return;
        }
        String label = labelFor(k);
        text.setColor(fg);
        text.setTextSize(k.kind == Layouts.Kind.FUNCTION ? sp(15) : sp(label.length() > 1 ? 17 : 22));
        drawCentered(canvas, label, cx, cy);
        if (!k.alts.isEmpty() && k.kind == Layouts.Kind.CHAR && !",.".contains(k.label)) {
            text.setColor(withAlpha(fg, .45f));
            text.setTextSize(sp(10));
            String hint = k.alts.substring(0, k.alts.offsetByCodePoints(0, 1));
            canvas.drawText(hint, r.right - 7 * dp, r.top + 12 * dp, text);
        }
    }

    private void drawPreview(Canvas canvas, Cell c) {
        RectF k = c.rect;
        float bw = Math.max(k.width() * 1.2f, 44 * dp), bh = k.height() * 1.25f;
        float left = clamp(k.centerX() - bw / 2f, 2 * dp, getWidth() - 2 * dp - bw);
        float bottom = k.top - 6 * dp;
        float top = Math.max(2 * dp, bottom - bh);
        tmp.set(left, top, left + bw, top + bh);
        painter.draw(canvas, tmp, keyRadius * 1.3f, dark ? 0xf24a4a4d : 0xf7ffffff, painter.bubble, 1f);
        text.setColor(dark ? 0xfff2f2f2 : 0xff1d1d1f);
        text.setTextSize(sp(30));
        drawCentered(canvas, labelFor(c.key), tmp.centerX(), tmp.centerY());
    }

    private void drawAlternatives(Canvas canvas, Finger f) {
        RectF r = f.altRect;
        painter.draw(canvas, r, keyRadius * 1.3f, dark ? 0xf24a4a4d : 0xf7ffffff, painter.bubble, 1f);
        float cell = r.width() / f.alts.size();
        for (int i = 0; i < f.alts.size(); i++) {
            float l = r.left + i * cell;
            boolean sel = i == f.altIndex;
            if (sel) {
                tmp.set(l + 4 * dp, r.top + 4 * dp, l + cell - 4 * dp, r.bottom - 4 * dp);
                painter.draw(canvas, tmp, keyRadius, (accent & 0x00ffffff) | 0xe6000000, painter.key, 1f);
            }
            text.setColor(sel ? 0xffffffff : (dark ? 0xfff2f2f2 : 0xff1d1d1f));
            text.setTextSize(sp(22));
            drawCentered(canvas, applyShift(f.alts.get(i)), l + cell / 2f, r.centerY());
        }
    }

    // ---- icons (drawn as paths in a 24dp box around the center)

    private void drawShift(Canvas canvas, float cx, float cy) {
        path.reset();
        float u = dp;
        path.moveTo(cx, cy - 9 * u);
        path.lineTo(cx + 8 * u, cy);
        path.lineTo(cx + 4 * u, cy);
        path.lineTo(cx + 4 * u, cy + 7 * u);
        path.lineTo(cx - 4 * u, cy + 7 * u);
        path.lineTo(cx - 4 * u, cy);
        path.lineTo(cx - 8 * u, cy);
        path.close();
        icon.setStyle(shift == SHIFT_OFF ? Paint.Style.STROKE : Paint.Style.FILL_AND_STROKE);
        canvas.drawPath(path, icon);
        icon.setStyle(Paint.Style.STROKE);
        if (shift == SHIFT_LOCK) canvas.drawLine(cx - 5 * u, cy + 11 * u, cx + 5 * u, cy + 11 * u, icon);
    }

    private void drawDelete(Canvas canvas, float cx, float cy) {
        float u = dp;
        path.reset();
        path.moveTo(cx - 11 * u, cy);
        path.lineTo(cx - 5 * u, cy - 7 * u);
        path.lineTo(cx + 9 * u, cy - 7 * u);
        path.lineTo(cx + 9 * u, cy + 7 * u);
        path.lineTo(cx - 5 * u, cy + 7 * u);
        path.close();
        canvas.drawPath(path, icon);
        canvas.drawLine(cx - 1 * u, cy - 3 * u, cx + 5 * u, cy + 3 * u, icon);
        canvas.drawLine(cx + 5 * u, cy - 3 * u, cx - 1 * u, cy + 3 * u, icon);
    }

    private void drawEnter(Canvas canvas, float cx, float cy) {
        float u = dp;
        path.reset();
        switch (enterAction) {
            case EditorInfo.IME_ACTION_SEARCH:
                canvas.drawCircle(cx - 2 * u, cy - 2 * u, 6 * u, icon);
                canvas.drawLine(cx + 2.5f * u, cy + 2.5f * u, cx + 8 * u, cy + 8 * u, icon);
                return;
            case EditorInfo.IME_ACTION_GO:
            case EditorInfo.IME_ACTION_NEXT:
                canvas.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, icon);
                path.moveTo(cx + 3 * u, cy - 5 * u);
                path.lineTo(cx + 8 * u, cy);
                path.lineTo(cx + 3 * u, cy + 5 * u);
                break;
            case EditorInfo.IME_ACTION_DONE:
                path.moveTo(cx - 7 * u, cy);
                path.lineTo(cx - 2 * u, cy + 5 * u);
                path.lineTo(cx + 8 * u, cy - 6 * u);
                break;
            case EditorInfo.IME_ACTION_SEND:
                path.moveTo(cx - 8 * u, cy - 7 * u);
                path.lineTo(cx + 9 * u, cy);
                path.lineTo(cx - 8 * u, cy + 7 * u);
                path.lineTo(cx - 5 * u, cy);
                path.close();
                path.moveTo(cx - 5 * u, cy);
                path.lineTo(cx + 2 * u, cy);
                break;
            default:
                path.moveTo(cx + 8 * u, cy - 7 * u);
                path.lineTo(cx + 8 * u, cy + 2 * u);
                path.lineTo(cx - 7 * u, cy + 2 * u);
                path.moveTo(cx - 3 * u, cy - 2 * u);
                path.lineTo(cx - 7 * u, cy + 2 * u);
                path.lineTo(cx - 3 * u, cy + 6 * u);
        }
        canvas.drawPath(path, icon);
    }

    private void drawGlobe(Canvas canvas, float cx, float cy) {
        float u = dp;
        canvas.drawCircle(cx, cy, 8 * u, icon);
        tmp.set(cx - 3.5f * u, cy - 8 * u, cx + 3.5f * u, cy + 8 * u);
        canvas.drawOval(tmp, icon);
        canvas.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, icon);
    }

    private void drawToolIcon(Canvas canvas, int tool, float cx, float cy) {
        float u = dp;
        path.reset();
        switch (tool) {
            case TOOL_SETTINGS:
                canvas.drawLine(cx - 7 * u, cy - 3.5f * u, cx + 7 * u, cy - 3.5f * u, icon);
                canvas.drawLine(cx - 7 * u, cy + 3.5f * u, cx + 7 * u, cy + 3.5f * u, icon);
                icon.setStyle(Paint.Style.FILL_AND_STROKE);
                canvas.drawCircle(cx - 2.5f * u, cy - 3.5f * u, 2 * u, icon);
                canvas.drawCircle(cx + 3 * u, cy + 3.5f * u, 2 * u, icon);
                icon.setStyle(Paint.Style.STROKE);
                return;
            case TOOL_PASTE:
                tmp.set(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 8 * u);
                canvas.drawRoundRect(tmp, 2 * u, 2 * u, icon);
                tmp.set(cx - 3 * u, cy - 8.5f * u, cx + 3 * u, cy - 4.5f * u);
                canvas.drawRoundRect(tmp, 1.5f * u, 1.5f * u, icon);
                return;
            case TOOL_LEFT:
                path.moveTo(cx + 2.5f * u, cy - 6 * u);
                path.lineTo(cx - 3.5f * u, cy);
                path.lineTo(cx + 2.5f * u, cy + 6 * u);
                break;
            case TOOL_RIGHT:
                path.moveTo(cx - 2.5f * u, cy - 6 * u);
                path.lineTo(cx + 3.5f * u, cy);
                path.lineTo(cx - 2.5f * u, cy + 6 * u);
                break;
            default:
                path.moveTo(cx - 6.5f * u, cy - 3 * u);
                path.lineTo(cx, cy + 3.5f * u);
                path.lineTo(cx + 6.5f * u, cy - 3 * u);
        }
        canvas.drawPath(path, icon);
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                fingerDown(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) fingerMove(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                fingerUp(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelAllFingers();
                break;
            default: break;
        }
        return true;
    }

    private void fingerDown(int id, float x, float y) {
        // Fast typing: a new finger commits the character held by the previous one.
        for (int i = 0; i < fingers.size(); i++) {
            Finger other = fingers.valueAt(i);
            if (!other.done && other.cell != null && other.cell.key != null
                    && other.cell.key.kind == Layouts.Kind.CHAR && other.alts == null) {
                commitFinger(other);
            }
        }
        Cell cell = hit(x, y);
        if (cell == null) return;
        Finger f = new Finger();
        f.id = id;
        f.cell = cell;
        f.downX = f.lastCursorX = x;
        f.downY = y;
        fingers.put(id, f);
        press(cell);
        feedback(cell);

        Layouts.Key k = cell.key;
        if (k == null) { invalidate(); return; }
        if (k.code == Layouts.DELETE) {
            actions.onDelete();
            f.repeat = new Runnable() {
                @Override public void run() {
                    actions.onDelete();
                    feedback(f.cell);
                    handler.postDelayed(this, DELETE_REPEAT_MS);
                }
            };
            handler.postDelayed(f.repeat, DELETE_DELAY_MS);
        } else if (k.code == Layouts.SHIFT) {
            long now = SystemClock.uptimeMillis();
            if (shift == SHIFT_LOCK) shift = SHIFT_OFF;
            else if (now - lastShiftTap < DOUBLE_SHIFT_MS) shift = SHIFT_LOCK;
            else shift = shift == SHIFT_ONCE ? SHIFT_OFF : SHIFT_ONCE;
            lastShiftTap = now;
        }
        scheduleLongPress(f);
        invalidate();
    }

    private void scheduleLongPress(Finger f) {
        if (f.longPress != null) handler.removeCallbacks(f.longPress);
        f.longPress = null;
        Layouts.Key k = f.cell.key;
        boolean hasAlts = k.kind == Layouts.Kind.CHAR && !k.alts.isEmpty();
        if (!hasAlts && k.kind != Layouts.Kind.SPACE && k.code != Layouts.LANG) return;
        f.longPress = () -> {
            if (f.done || f.cursorMode) return;
            f.longPressed = true;
            if (hasAlts) openAlternatives(f);
            else {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                actions.onPickInputMethod();
                release(f.cell);
                f.done = true;
            }
            invalidate();
        };
        handler.postDelayed(f.longPress, LONG_PRESS_MS);
    }

    private void openAlternatives(Finger f) {
        List<String> alts = new ArrayList<>();
        String s = f.cell.key.alts;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            alts.add(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        if (!alts.contains(f.cell.key.label)) alts.add(f.cell.key.label);
        RectF k = f.cell.rect;
        float cell = Math.max(k.width(), 36 * dp);
        float width = cell * alts.size(), height = k.height() * 1.2f;
        float left = clamp(k.centerX() - cell / 2f, 2 * dp, getWidth() - 2 * dp - width);
        float bottom = k.top - 6 * dp, top = Math.max(2 * dp, bottom - height);
        f.alts = alts;
        f.altIndex = 0;
        f.altRect = new RectF(left, top, left + width, top + height);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void fingerMove(int id, float x, float y) {
        Finger f = fingers.get(id);
        if (f == null || f.done || f.cell == null) return;
        Layouts.Key k = f.cell.key;
        if (f.alts != null) {
            float cell = f.altRect.width() / f.alts.size();
            int idx = (int) Math.floor((x - f.altRect.left) / cell);
            idx = Math.max(0, Math.min(f.alts.size() - 1, idx));
            if (idx != f.altIndex) { f.altIndex = idx; invalidate(); }
            return;
        }
        if (k == null) return;
        if (k.kind == Layouts.Kind.SPACE) {
            float step = 9 * dp;
            if (!f.cursorMode && Math.abs(x - f.downX) > 14 * dp) {
                f.cursorMode = true;
                f.lastCursorX = x;
                if (f.longPress != null) handler.removeCallbacks(f.longPress);
            }
            if (f.cursorMode) {
                int moves = (int) ((x - f.lastCursorX) / step);
                if (moves != 0) {
                    actions.onCursor(moves);
                    f.lastCursorX += moves * step;
                    if (prefs.vibrate()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            return;
        }
        if (k.kind == Layouts.Kind.CHAR && !f.longPressed) {
            Cell other = hit(x, y);
            if (other != null && other != f.cell && other.key != null && other.key.kind == Layouts.Kind.CHAR) {
                release(f.cell);
                f.cell = other;
                press(other);
                scheduleLongPress(f);
                invalidate();
            }
        }
    }

    private void fingerUp(int id, float x, float y) {
        Finger f = fingers.get(id);
        if (f == null) return;
        if (!f.done) {
            if (f.cell.key == null) {
                if (f.cell.rect.contains(x, y) || distance(f.cell.rect, x, y) < 12 * dp) runTool(f.cell.tool);
            } else {
                commitFinger(f);
            }
        }
        clearFinger(f);
        fingers.remove(id);
        invalidate();
    }

    private void commitFinger(Finger f) {
        if (f.done) return;
        f.done = true;
        Layouts.Key k = f.cell.key;
        if (f.alts != null) {
            emitText(f.alts.get(f.altIndex));
        } else if (f.cursorMode) {
            // cursor drag, nothing to type
        } else if (k != null) {
            switch (k.code) {
                case Layouts.DELETE:
                case Layouts.SHIFT:
                    break;
                case Layouts.ENTER: actions.onEnter(); break;
                case Layouts.SYMBOLS: layout = Layouts.symbols(); relayout(); break;
                case Layouts.SYMBOLS_MORE: layout = Layouts.symbolsMore(); relayout(); break;
                case Layouts.ALPHA: layout = letters(); relayout(); break;
                case Layouts.LANG:
                    if (!f.longPressed) {
                        lang = "en".equals(lang) ? "ru" : "en";
                        actions.onLanguageChanged(lang);
                        if (isLetterLayout()) { layout = letters(); relayout(); }
                        else invalidate();
                    }
                    break;
                default:
                    if (k.kind == Layouts.Kind.SPACE) {
                        if (!f.longPressed) actions.onText(" ");
                    } else {
                        emitText(labelFor(k));
                    }
            }
        }
        release(f.cell);
        cancelCallbacks(f);
    }

    private void emitText(String s) {
        actions.onText(applyShift(s));
        if (shift == SHIFT_ONCE && isLetterLayout()) { shift = SHIFT_OFF; invalidate(); }
    }

    private void runTool(int tool) {
        switch (tool) {
            case TOOL_SETTINGS: actions.onSettings(); break;
            case TOOL_PASTE: actions.onPaste(); break;
            case TOOL_LEFT: actions.onCursor(-1); break;
            case TOOL_RIGHT: actions.onCursor(1); break;
            case TOOL_HIDE: actions.onHide(); break;
            default: break;
        }
    }

    private void clearFinger(Finger f) {
        release(f.cell);
        cancelCallbacks(f);
        f.done = true;
    }

    private void cancelCallbacks(Finger f) {
        if (f.longPress != null) handler.removeCallbacks(f.longPress);
        if (f.repeat != null) handler.removeCallbacks(f.repeat);
        f.longPress = f.repeat = null;
    }

    private void cancelAllFingers() {
        for (int i = 0; i < fingers.size(); i++) clearFinger(fingers.valueAt(i));
        fingers.clear();
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        cancelAllFingers();
        super.onDetachedFromWindow();
    }

    private Finger topFinger() {
        Finger best = null;
        for (int i = 0; i < fingers.size(); i++) {
            Finger f = fingers.valueAt(i);
            if (!f.done && (best == null || f.cell.downAt >= best.cell.downAt)) best = f;
        }
        return best;
    }

    private void press(Cell c) {
        c.pressed = true;
        c.downAt = SystemClock.uptimeMillis();
    }

    private void release(Cell c) {
        if (c == null || !c.pressed) return;
        c.pressed = false;
        c.upAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /** Toolbar hit test first, then nearest key in the touched row (gaps and margins included). */
    private Cell hit(float x, float y) {
        if (y < topPad + toolbarH) {
            Cell best = null;
            float bestD = 10 * dp;
            for (Cell t : tools) {
                float d = distance(t.rect, x, y);
                if (d <= bestD) { bestD = d; best = t; }
            }
            if (best != null || y < topPad + toolbarH - 4 * dp) return best;
        }
        int rows = layout.rows.size();
        int row = (int) Math.floor((y - topPad - toolbarH) / rowPitch);
        row = Math.max(0, Math.min(rows - 1, row));
        Cell best = null;
        float bestD = Float.MAX_VALUE;
        for (Cell c : keys) {
            if (c.row != row) continue;
            float d = x < c.rect.left ? c.rect.left - x : x > c.rect.right ? x - c.rect.right : 0f;
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    private void feedback(Cell c) {
        if (prefs.vibrate()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (prefs.sound() && audio != null) {
            int fx = AudioManager.FX_KEYPRESS_STANDARD;
            if (c.key != null) {
                if (c.key.code == Layouts.DELETE) fx = AudioManager.FX_KEYPRESS_DELETE;
                else if (c.key.kind == Layouts.Kind.SPACE) fx = AudioManager.FX_KEYPRESS_SPACEBAR;
                else if (c.key.code == Layouts.ENTER) fx = AudioManager.FX_KEYPRESS_RETURN;
            }
            audio.playSoundEffect(fx, -1f);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String labelFor(Layouts.Key k) {
        if (k.code == Layouts.ALPHA) return "en".equals(lang) ? "ABC" : "АБВ";
        return k.kind == Layouts.Kind.CHAR ? applyShift(k.label) : k.label;
    }

    private String applyShift(String s) {
        if (shift == SHIFT_OFF || !isLetterLayout()) return s;
        return s.toUpperCase("en".equals(lang) ? Locale.ENGLISH : Locale.forLanguageTag("ru"));
    }

    private void drawCentered(Canvas canvas, String s, float cx, float cy) {
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    private void scaled(RectF r, float s) {
        float dw = r.width() * (1f - s) / 2f, dh = r.height() * (1f - s) / 2f;
        tmp.set(r.left + dw, r.top + dh, r.right - dw, r.bottom - dh);
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    private int resolveAccent() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return getContext().getColor(dark ? android.R.color.system_accent1_400 : android.R.color.system_accent1_600);
            } catch (Throwable ignored) { }
        }
        return 0xff3478f6;
    }

    private static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static float distance(RectF r, float x, float y) {
        float dx = Math.max(Math.max(r.left - x, 0f), x - r.right);
        float dy = Math.max(Math.max(r.top - y, 0f), y - r.bottom);
        return (float) Math.hypot(dx, dy);
    }

    private static float clamp(float v, float lo, float hi) { return hi < lo ? lo : Math.max(lo, Math.min(hi, v)); }

    private static int withAlpha(int color, float a) {
        return (Math.round(Color.alpha(color) * a) << 24) | (color & 0x00ffffff);
    }

    private static int lerpColor(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = a >>> 24, ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int ba = b >>> 24, br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
