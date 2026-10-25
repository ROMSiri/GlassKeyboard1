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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Draws the whole keyboard (strip + keys) on one canvas and handles multi-touch and gestures. */
@SuppressLint("ViewConstructor")
final class KeyboardView extends View implements PanelBlur.Listener {

    interface Actions {
        void onText(String text);
        void onDelete();
        void onEnter();
        void onCursor(int delta);
        void onHide();
        void onLanguageChanged(String lang);
        void onPickInputMethod();
        void onSuggestion(int slot);
        void onToolKey(ToolKey key);
        void onShift();
        void onGestureStart(Map<Character, float[]> centers, float keyWidth);
        String onGestureProgress(float[] xs, float[] ys, int n);
        void onGesture(List<float[]> xs, List<float[]> ys);
    }

    static final int SHIFT_OFF = 0, SHIFT_ONCE = 1, SHIFT_LOCK = 2;

    private static final long LONG_PRESS_MS = 300, DELETE_DELAY_MS = 400, DELETE_REPEAT_MS = 55,
            RELEASE_FADE_MS = 160, DOUBLE_SHIFT_MS = 320, PREVIEW_EVERY_MS = 90;

    // strip cell ids
    private static final int CELL_SLOT = 100;      // + slot index
    private static final int CELL_TOOL = 200;      // + ToolKey ordinal
    private static final int CELL_TOGGLE = 300, CELL_RESIZE_DONE = 301, CELL_ONEHAND_SWAP = 302,
            CELL_ONEHAND_EXIT = 303, CELL_RESIZE_MINUS = 304, CELL_RESIZE_PLUS = 305;

    /** A laid-out key or strip button. */
    private static final class Cell {
        final Layouts.Key key;    // null for strip buttons
        final int id;
        final ToolKey tool;
        final RectF rect = new RectF();
        int row;
        boolean pressed;
        long downAt, upAt;
        Cell(Layouts.Key key, int id, ToolKey tool) { this.key = key; this.id = id; this.tool = tool; }
    }

    /** State of one finger. */
    private static final class Finger {
        int id;
        Cell cell;
        float downX, downY, lastX, lastCursorX;
        long downTime;
        boolean longPressed, cursorMode, done, scrolling, gesture, overSpace;
        List<String> alts;
        int altIndex;
        RectF altRect;
        Runnable longPress, repeat;
        float scrollStart;
    }

    private final Actions actions;
    private final Prefs prefs;
    private final GlassPainter painter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final float dp;

    private final List<Cell> keys = new ArrayList<>();
    private final SparseArray<Finger> fingers = new SparseArray<>();

    // ---- strip
    private final List<Cell> toolCells = new ArrayList<>();     // scrollable toolbar row
    private final List<Cell> pinnedCells = new ArrayList<>();   // right side next to suggestions
    private Cell[] slotCells = new Cell[0];
    private final Cell toggleCell = new Cell(null, CELL_TOGGLE, null);
    private final Cell resizeDone = new Cell(null, CELL_RESIZE_DONE, null);
    private final Cell resizeMinus = new Cell(null, CELL_RESIZE_MINUS, null);
    private final Cell resizePlus = new Cell(null, CELL_RESIZE_PLUS, null);
    private final Cell swapCell = new Cell(null, CELL_ONEHAND_SWAP, null);
    private final Cell exitCell = new Cell(null, CELL_ONEHAND_EXIT, null);
    private String[] suggestions;       // null: nothing to suggest
    private int literalSlot = -1;
    private boolean boldCenter;
    private boolean expanded;           // EXPANDABLE mode: toolbar opened by the toggle
    private float toolScroll, toolContentW, toolAreaL, toolAreaR;
    private final Set<ToolKey> activeTools = EnumSet.noneOf(ToolKey.class);
    private boolean resizeMode;
    private String mode;

    // ---- gesture
    private final List<float[]> trail = new ArrayList<>();      // x, y, time
    private final List<float[]> segXs = new ArrayList<>(), segYs = new ArrayList<>();
    private float[] gx = new float[256], gy = new float[256];
    private int gn;
    private String floatingText;
    private float floatX, floatY;
    private long lastPreviewAt, lastTapAt;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private float toolbarH, keyH, rowPitch;
    private final float hGap, sidePad, topPad, keyRadius, panelRadius, stripH;
    private float bottomPad;
    private float keysLeft, keysRight;   // horizontal extent of the key area (one-handed mode)

    KeyboardView(Context context, Prefs prefs, Actions actions) {
        super(context);
        this.prefs = prefs;
        this.actions = actions;
        dp = context.getResources().getDisplayMetrics().density;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        stripH = 46 * dp;
        hGap = 6 * dp;
        sidePad = 3 * dp;
        topPad = 4 * dp;
        keyRadius = 10 * dp;
        hairline.setStrokeWidth(Math.max(1f, .6f * dp));
        panelRadius = 28 * dp;
        readDimensions();

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
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeWidth(5 * dp);

        setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            if (bottom != insetBottom) { insetBottom = bottom; requestLayout(); }
            return insets;
        });
    }

    private void readDimensions() {
        mode = prefs.toolbarMode();
        toolbarH = Prefs.MODE_HIDDEN.equals(mode) && !resizeMode ? 0 : stripH;
        float scale = prefs.keyHeight() / 100f;
        keyH = 46 * dp * scale;
        rowPitch = keyH + 10 * dp * scale;
        bottomPad = (6 + prefs.bottomOffset()) * dp;
    }

    // ------------------------------------------------------------------ public API

    float panelRadiusPx() { return panelRadius; }
    String language() { return lang; }
    boolean isDark() { return dark; }

    /** Word suggestions for the strip; null when there is nothing to suggest. */
    void setSuggestions(String[] slots, int literal, boolean autocorrect) {
        boolean any = false;
        if (slots != null) for (String s : slots) any |= s != null;
        String[] next = any ? slots.clone() : null;
        boolean wasShown = suggestions != null;
        suggestions = next;
        literalSlot = literal;
        boldCenter = autocorrect;
        if (next != null && !wasShown) expanded = false; // typing closes the "⋯" menu
        if (next != null && slotCells.length != next.length) layoutStrip();
        invalidate();
    }

    void setToolState(ToolKey key, boolean active) {
        if (active ? activeTools.add(key) : activeTools.remove(key)) invalidate();
    }

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
        resizeMode = false;
        expanded = false;
        readDimensions();
        requestLayout();
        relayout();
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
        readDimensions();
        int rows = layout.rows.size();
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
        if (!followApp) setDark(night);
    }

    private boolean followApp;
    private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    { hairline.setStyle(Paint.Style.STROKE); }

    /** Theme chosen by the service (e.g. to match the app being typed into). */
    void setDark(boolean d) { setDark(d, true); }

    void setDark(boolean d, boolean fromApp) {
        followApp = fromApp;
        if (d == dark) return;
        dark = d;
        painter.setDark(dark);
        accent = resolveAccent();
        invalidate();
    }

    private void relayout() {
        int w = getWidth();
        if (w <= 0) return;
        laidOutWidth = w;
        keys.clear();
        int oneHanded = prefs.oneHanded();
        float areaW = oneHanded == 0 ? w - 2 * sidePad : (w - 2 * sidePad) * .82f;
        keysLeft = oneHanded == 2 ? sidePad : (oneHanded == 1 ? w - sidePad - areaW : sidePad);
        keysRight = keysLeft + areaW;
        float unit = areaW / layout.maxWeight;
        float y = topPad + toolbarH;
        for (int r = 0; r < layout.rows.size(); r++) {
            List<Layouts.Key> row = layout.rows.get(r);
            float rowWeight = 0f;
            for (Layouts.Key k : row) rowWeight += k.weight;
            float x = keysLeft + (layout.maxWeight - rowWeight) / 2f * unit;
            for (Layouts.Key k : row) {
                Cell c = new Cell(k, 0, null);
                c.row = r;
                float kw = k.weight * unit;
                c.rect.set(x + hGap / 2f, y + (rowPitch - keyH) / 2f, x + kw - hGap / 2f, y + (rowPitch + keyH) / 2f);
                keys.add(c);
                x += kw;
            }
            y += rowPitch;
        }
        // one-handed side buttons, centred in the free column
        if (oneHanded != 0) {
            float colL = oneHanded == 1 ? sidePad : keysRight, colR = oneHanded == 1 ? keysLeft : w - sidePad;
            float cx = (colL + colR) / 2f, keysMid = topPad + toolbarH + layout.rows.size() * rowPitch / 2f;
            float s = Math.min(40 * dp, colR - colL - 6 * dp);
            swapCell.rect.set(cx - s / 2f, keysMid - s - 8 * dp, cx + s / 2f, keysMid - 8 * dp);
            exitCell.rect.set(cx - s / 2f, keysMid + 8 * dp, cx + s / 2f, keysMid + 8 * dp + s);
        }
        layoutStrip();
        invalidate();
    }

    /** Positions of the strip parts for the current mode and state. */
    private void layoutStrip() {
        int w = getWidth();
        if (w <= 0) return;
        float cy = topPad + toolbarH / 2f, size = 36 * dp, gap = 8 * dp;
        float l = sidePad + 4 * dp, r = w - sidePad - 4 * dp;

        boolean expandable = Prefs.MODE_EXPANDABLE.equals(mode);
        if (expandable) {
            // "⋯" opens / closes the toolbar keys
            toggleCell.rect.set(r - size, cy - size / 2f, r, cy + size / 2f);
            r -= size + gap;
        }
        resizeDone.rect.set(r - 96 * dp, cy - size / 2f, r, cy + size / 2f);
        resizeMinus.rect.set(sidePad + 4 * dp, cy - size / 2f, sidePad + 4 * dp + size, cy + size / 2f);
        resizePlus.rect.set(resizeDone.rect.left - gap - size, cy - size / 2f, resizeDone.rect.left - gap, cy + size / 2f);

        // pinned keys (EXPANDABLE) or the persistent key (SUGGESTION_STRIP) on the right
        pinnedCells.clear();
        List<ToolKey> right = new ArrayList<>();
        if (expandable) right.addAll(ToolKey.parse(prefs.pinnedKeys(), true));
        else if (Prefs.MODE_STRIP.equals(mode)) {
            try { right.add(ToolKey.valueOf(prefs.persistentKey())); } catch (IllegalArgumentException ignored) { }
        }
        float pr = r;
        for (int i = right.size() - 1; i >= 0; i--) {
            Cell c = new Cell(null, CELL_TOOL + right.get(i).ordinal(), right.get(i));
            c.rect.set(pr - size, cy - size / 2f, pr, cy + size / 2f);
            pinnedCells.add(0, c);
            pr -= size + gap;
        }

        // suggestion slots between
        int n = suggestions != null ? suggestions.length : 3;
        if (slotCells.length != n) {
            slotCells = new Cell[n];
            for (int i = 0; i < n; i++) slotCells[i] = new Cell(null, CELL_SLOT + i, null);
        }
        float sl = l, sr = pinnedCells.isEmpty() ? r : pr + gap / 2f;
        float slotW = (sr - sl) / n;
        for (int i = 0; i < n; i++)
            slotCells[i].rect.set(sl + i * slotW + 2 * dp, cy - 18 * dp, sl + (i + 1) * slotW - 2 * dp, cy + 18 * dp);

        // scrollable toolbar keys
        toolCells.clear();
        toolAreaL = sidePad + 4 * dp;
        toolAreaR = r;
        float x = toolAreaL;
        for (ToolKey k : ToolKey.parse(prefs.toolbarKeys(), true)) {
            Cell c = new Cell(null, CELL_TOOL + k.ordinal(), k);
            c.rect.set(x, cy - size / 2f, x + size, cy + size / 2f);
            toolCells.add(c);
            x += size + gap;
        }
        toolContentW = x - gap - toolAreaL;
        // without scrolling, spread the keys over the full width
        float avail = toolAreaR - toolAreaL;
        if (toolContentW < avail && toolCells.size() > 1) {
            float step = (avail - size) / (toolCells.size() - 1);
            step = Math.min(step, size * 1.9f);
            float start = toolAreaL + (avail - (step * (toolCells.size() - 1) + size)) / 2f;
            for (int i = 0; i < toolCells.size(); i++) {
                float cx0 = start + i * step;
                toolCells.get(i).rect.set(cx0, cy - size / 2f, cx0 + size, cy + size / 2f);
            }
            toolContentW = avail;
        }
        toolScroll = clamp(toolScroll, 0, Math.max(0, toolContentW - avail));
    }

    /** What the strip shows right now. */
    private boolean showSlots() {
        if (toolbarH == 0 || resizeMode) return false;
        if (Prefs.MODE_KEYS.equals(mode)) return false;
        if (Prefs.MODE_STRIP.equals(mode)) return true;
        return !expanded;   // EXPANDABLE: suggestions (or empty) until "⋯" is tapped
    }

    private boolean showToolRow() {
        if (toolbarH == 0 || resizeMode) return false;
        if (Prefs.MODE_KEYS.equals(mode)) return true;
        if (Prefs.MODE_STRIP.equals(mode)) return false;
        return expanded;
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas canvas) {
        if (laidOutWidth != getWidth()) relayout();
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        float w = getWidth(), h = getHeight();

        drawPanel(canvas, w, h);

        if (resizeMode) drawResizeBar(canvas, now);
        else if (toolbarH > 0) {
            if (Prefs.MODE_EXPANDABLE.equals(mode)) animating |= drawToggle(canvas, now);
            if (showSlots()) {
                if (suggestions != null) animating |= drawSuggestions(canvas, now);
                for (Cell c : pinnedCells) animating |= drawTool(canvas, c, now);
            }
            if (showToolRow()) {
                int save = canvas.save();
                canvas.clipRect(toolAreaL - 4 * dp, 0, toolAreaR + 4 * dp, topPad + toolbarH);
                canvas.translate(-toolScroll, 0);
                for (Cell c : toolCells) animating |= drawTool(canvas, c, now);
                canvas.restoreToCount(save);
            }
        }
        for (Cell c : keys) animating |= drawKey(canvas, c, now);
        if (prefs.oneHanded() != 0) {
            animating |= drawRoundButton(canvas, swapCell, now, "swap");
            animating |= drawRoundButton(canvas, exitCell, now, "exit");
        }

        animating |= drawTrail(canvas, now);

        Finger top = topFinger();
        if (top != null && top.alts != null) drawAlternatives(canvas, top);
        else if (top != null && !top.gesture && prefs.preview() && top.cell != null && top.cell.key != null
                && top.cell.key.kind == Layouts.Kind.CHAR && !top.cursorMode) drawPreview(canvas, top.cell);
        if (floatingText != null && top != null && top.gesture) drawFloating(canvas);

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

    private int fg() { return dark ? 0xfff2f2f2 : 0xff1d1d1f; }

    private boolean drawSuggestions(Canvas canvas, long now) {
        boolean animating = false;
        int fg = fg();
        int n = Math.min(slotCells.length, suggestions.length);
        for (int i = 0; i < n; i++) {
            Cell c = slotCells[i];
            String s = suggestions[i];
            float p = pressAmount(c, now);
            animating |= c.pressed || c.upAt != 0;
            if (s == null) continue;
            if (p > 0f) {
                scaled(c.rect, 1f - .04f * p);
                painter.draw(canvas, tmp, tmp.height() / 2f, dark ? 0x3dffffff : 0x85ffffff, painter.round, p);
            }
            boolean bold = i == n / 2 && boldCenter;
            text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            text.setTextSize(sp(n > 3 ? 14 : 16));
            text.setColor(i == literalSlot ? withAlpha(fg, .6f) : fg);
            String label = i == literalSlot ? "«" + s + "»" : s;
            drawCentered(canvas, fit(label, c.rect.width() - 6 * dp), c.rect.centerX(), c.rect.centerY());
        }
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        panelPaint.setColor(withAlpha(fg, .18f));
        for (int i = 1; i < n; i++) {
            if (suggestions[i - 1] == null || suggestions[i] == null) continue;
            float x = (slotCells[i - 1].rect.right + slotCells[i].rect.left) / 2f;
            canvas.drawRect(x - dp * .5f, slotCells[i].rect.centerY() - 9 * dp,
                    x + dp * .5f, slotCells[i].rect.centerY() + 9 * dp, panelPaint);
        }
        return animating;
    }

    private boolean drawToggle(Canvas canvas, long now) {
        Cell t = toggleCell;
        float p = pressAmount(t, now);
        if (expanded || p > 0f) {
            scaled(t.rect, 1f - .06f * p);
            int fill = expanded ? (accent & 0x00ffffff) | 0x33000000 : (dark ? 0x3dffffff : 0x85bbbec2);
            painter.draw(canvas, tmp, tmp.width() / 2f, fill, painter.round, expanded ? 1f : p);
        }
        panelPaint.setColor(expanded ? accent : withAlpha(fg(), .7f));
        float cx = t.rect.centerX(), cy = t.rect.centerY();
        for (int i = -1; i <= 1; i++) canvas.drawCircle(cx + i * 5.5f * dp, cy, 2 * dp, panelPaint);
        return t.pressed || t.upAt != 0;
    }

    private void drawResizeBar(Canvas canvas, long now) {
        text.setColor(fg());
        text.setTextSize(sp(15));
        float cy = topPad + stripH / 2f;
        drawCentered(canvas, "Высота клавиш " + prefs.keyHeight() + "%",
                (resizeMinus.rect.right + resizePlus.rect.left) / 2f, cy);
        for (Cell c : new Cell[]{resizeMinus, resizePlus}) {
            float p = pressAmount(c, now);
            scaled(c.rect, 1f - .06f * p);
            painter.draw(canvas, tmp, tmp.width() / 2f,
                    lerpColor(dark ? 0x26ffffff : 0x8affffff, dark ? 0x3dffffff : 0x85bbbec2, p), painter.round, 1f);
            icon.setColor(fg());
            canvas.drawLine(tmp.centerX() - 6 * dp, tmp.centerY(), tmp.centerX() + 6 * dp, tmp.centerY(), icon);
            if (c == resizePlus)
                canvas.drawLine(tmp.centerX(), tmp.centerY() - 6 * dp, tmp.centerX(), tmp.centerY() + 6 * dp, icon);
        }
        float p = pressAmount(resizeDone, now);
        painter.draw(canvas, resizeDone.rect, resizeDone.rect.height() / 2f,
                lerpColor((accent & 0x00ffffff) | 0x99000000, (accent & 0x00ffffff) | 0xc0000000, p), painter.round, 1f);
        text.setColor(0xffffffff);
        drawCentered(canvas, "Готово", resizeDone.rect.centerX(), resizeDone.rect.centerY());
    }

    /** Ellipsizes {@code s} to {@code width} with the current text paint. */
    private String fit(String s, float width) {
        if (text.measureText(s) <= width) return s;
        int end = s.length();
        while (end > 1 && text.measureText(s, 0, end) + text.measureText("…") > width) end--;
        return s.substring(0, end) + "…";
    }

    private boolean drawTool(Canvas canvas, Cell t, long now) {
        float p = pressAmount(t, now);
        boolean active = t.tool != null && activeTools.contains(t.tool);
        int normal = active ? (accent & 0x00ffffff) | 0x99000000 : (dark ? 0x26ffffff : 0x8affffff);
        int pressed = active ? (accent & 0x00ffffff) | 0xc0000000 : (dark ? 0x3dffffff : 0x85bbbec2);
        scaled(t.rect, 1f - .06f * p);
        painter.draw(canvas, tmp, tmp.width() / 2f, lerpColor(normal, pressed, p), painter.round, 1f);
        icon.setColor(active ? 0xffffffff : (dark ? 0xfff2f2f2 : 0xff4a4a4f));
        if (t.tool != null) t.tool.draw(canvas, tmp.centerX(), tmp.centerY(), dp, icon, path, tmp);
        return t.pressed || t.upAt != 0;
    }

    private boolean drawRoundButton(Canvas canvas, Cell c, long now, String kind) {
        float p = pressAmount(c, now);
        scaled(c.rect, 1f - .06f * p);
        painter.draw(canvas, tmp, tmp.width() / 2f,
                lerpColor(dark ? 0x26ffffff : 0x8affffff, dark ? 0x3dffffff : 0x85bbbec2, p), painter.round, 1f);
        icon.setColor(dark ? 0xfff2f2f2 : 0xff4a4a4f);
        float cx = tmp.centerX(), cy = tmp.centerY(), u = dp;
        path.reset();
        if ("swap".equals(kind)) {
            path.moveTo(cx - 7 * u, cy - 3 * u); path.lineTo(cx + 7 * u, cy - 3 * u);
            path.moveTo(cx + 4 * u, cy - 6 * u); path.lineTo(cx + 7 * u, cy - 3 * u); path.lineTo(cx + 4 * u, cy);
            path.moveTo(cx + 7 * u, cy + 3 * u); path.lineTo(cx - 7 * u, cy + 3 * u);
            path.moveTo(cx - 4 * u, cy); path.lineTo(cx - 7 * u, cy + 3 * u); path.lineTo(cx - 4 * u, cy + 6 * u);
        } else {
            path.moveTo(cx - 7 * u, cy - 3 * u); path.lineTo(cx - 7 * u, cy - 7 * u); path.lineTo(cx - 3 * u, cy - 7 * u);
            path.moveTo(cx + 7 * u, cy + 3 * u); path.lineTo(cx + 7 * u, cy + 7 * u); path.lineTo(cx + 3 * u, cy + 7 * u);
            path.moveTo(cx - 7 * u, cy - 7 * u); path.lineTo(cx - 2 * u, cy - 2 * u);
            path.moveTo(cx + 7 * u, cy + 7 * u); path.lineTo(cx + 2 * u, cy + 2 * u);
        }
        canvas.drawPath(path, icon);
        return c.pressed || c.upAt != 0;
    }

    private boolean drawKey(Canvas canvas, Cell c, long now) {
        Layouts.Key k = c.key;
        float p = pressAmount(c, now);
        int normal, pressed;
        int fg = fg();
        switch (k.kind) {
            case ACCENT:
                normal = (accent & 0x00ffffff) | 0x99000000;
                pressed = (accent & 0x00ffffff) | 0xc0000000;
                fg = 0xffffffff;
                break;
            case FUNCTION:
                boolean active = k.code == Layouts.SHIFT && shift != SHIFT_OFF;
                // dark theme: service keys a bit dimmer than letters but lighter than the panel;
                // light theme: lighter (whiter) than letters
                normal = active ? (dark ? 0x40ffffff : ((accent & 0x00ffffff) | 0x47000000))
                        : (dark ? 0x17ffffff : 0xf2ffffff);
                pressed = dark ? 0x3dffffff : 0x85bbbec2;
                break;
            case SPACE: // space is styled like the service keys
                normal = dark ? 0x17ffffff : 0xf2ffffff;
                pressed = dark ? 0x3dffffff : 0x85bbbec2;
                break;
            default:
                normal = dark ? 0x26ffffff : 0x8affffff;
                pressed = dark ? 0x3dffffff : 0x85bbbec2;
        }
        int fill = lerpColor(normal, pressed, p);
        scaled(c.rect, 1f - .045f * p);
        float rad = Math.min(keyRadius, tmp.height() / 2f);
        painter.draw(canvas, tmp, rad, fill, painter.key, 1f);
        // hairline edge on the letter keys in light theme so they stand out from the panel
        if (!dark && k.kind == Layouts.Kind.CHAR) {
            float hw = hairline.getStrokeWidth() / 2f;
            hairline.setColor(0xb3ffffff);
            tmp.inset(hw, hw);
            canvas.drawRoundRect(tmp, rad - hw, rad - hw, hairline);
            tmp.inset(-hw, -hw);
        }
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
        boolean numpad = "num".equals(layout.id);
        text.setTextSize(k.kind == Layouts.Kind.FUNCTION ? sp(15) : sp(numpad ? 26 : label.length() > 1 ? 17 : 22));
        drawCentered(canvas, label, cx, cy);
        if (!k.alts.isEmpty() && k.kind == Layouts.Kind.CHAR && !numpad) {
            String hint = k.alts.substring(0, k.alts.offsetByCodePoints(0, 1));
            if (!hint.equals(k.label)) {   // e.g. "," on the period key
                text.setColor(withAlpha(fg, .45f));
                text.setTextSize(sp(10));
                canvas.drawText(hint, r.right - 7 * dp, r.top + 12 * dp, text);
            }
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
        text.setColor(fg());
        text.setTextSize(sp(30));
        drawCentered(canvas, labelFor(c.key), tmp.centerX(), tmp.centerY());
    }

    private void drawFloating(Canvas canvas) {
        text.setTextSize(sp(20));
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        float tw = text.measureText(floatingText) + 28 * dp, th = 40 * dp;
        float cx, cy;
        if (prefs.gestureFloatingDynamic()) { cx = floatX; cy = floatY - 64 * dp; }
        else { cx = getWidth() / 2f; cy = topPad + toolbarH + th / 2f + 4 * dp; }
        float left = clamp(cx - tw / 2f, 2 * dp, getWidth() - 2 * dp - tw);
        float top = Math.max(2 * dp, cy - th / 2f);
        tmp.set(left, top, left + tw, top + th);
        painter.draw(canvas, tmp, th / 2f, dark ? 0xf24a4a4d : 0xf7ffffff, painter.bubble, 1f);
        text.setColor(fg());
        drawCentered(canvas, floatingText, tmp.centerX(), tmp.centerY());
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    private boolean drawTrail(Canvas canvas, long now) {
        if (trail.isEmpty()) return false;
        float fade = Math.max(100, prefs.gestureTrailFade());
        Finger f = topFinger();
        boolean live = f != null && f.gesture;
        // drop points that have fully faded
        while (!trail.isEmpty() && now - trail.get(0)[2] > fade) trail.remove(0);
        if (trail.isEmpty()) return live;
        if (!prefs.gestureTrail()) return live;
        for (int i = 1; i < trail.size(); i++) {
            float[] a = trail.get(i - 1), b = trail.get(i);
            if (a[2] < 0 || b[2] < 0) continue; // segment break
            float age = (now - b[2]) / fade;
            int alpha = Math.round(230 * Math.max(0f, 1f - age));
            if (alpha <= 0) continue;
            trailPaint.setColor((accent & 0x00ffffff) | (alpha << 24));
            trailPaint.setStrokeWidth(dp * (2.5f + 3f * (1f - age)));
            canvas.drawLine(a[0], a[1], b[0], b[1], trailPaint);
        }
        return true;
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
            text.setColor(sel ? 0xffffffff : fg());
            text.setTextSize(sp(22));
            drawCentered(canvas, applyShift(f.alts.get(i)), l + cell / 2f, r.centerY());
        }
    }

    // ---- key icons

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

    private boolean inStrip(float y) { return y < topPad + toolbarH; }

    private void fingerDown(int id, float x, float y) {
        // Fast typing: a new finger commits the character held by the previous one.
        for (int i = 0; i < fingers.size(); i++) {
            Finger other = fingers.valueAt(i);
            if (!other.done && !other.gesture && other.cell != null && other.cell.key != null
                    && other.cell.key.kind == Layouts.Kind.CHAR && other.alts == null) {
                commitFinger(other);
            }
        }
        Finger f = new Finger();
        f.id = id;
        f.downX = f.lastX = f.lastCursorX = x;
        f.downY = y;
        f.downTime = SystemClock.uptimeMillis();
        f.scrollStart = toolScroll;
        if (resizeMode && inStrip(y)) {
            for (Cell c : new Cell[]{resizeMinus, resizePlus, resizeDone})
                if (distance(c.rect, x, y) < 6 * dp) { f.cell = c; press(c); feedback(c); }
            if (f.cell != null) fingers.put(id, f);
            invalidate();
            return;
        }
        Cell cell = hit(x, y);
        if (cell == null && !inStrip(y)) return;
        f.cell = cell;
        fingers.put(id, f);
        if (cell == null) return; // empty strip area: may still be a swipe down
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
            actions.onShift();
        }
        scheduleLongPress(f);
        invalidate();
    }

    private void scheduleLongPress(Finger f) {
        if (f.longPress != null) handler.removeCallbacks(f.longPress);
        f.longPress = null;
        Layouts.Key k = f.cell.key;
        if (k == null) return;
        boolean hasAlts = k.kind == Layouts.Kind.CHAR && !k.alts.isEmpty();
        if (!hasAlts && k.kind != Layouts.Kind.SPACE && k.code != Layouts.LANG) return;
        f.longPress = () -> {
            if (f.done || f.cursorMode || f.gesture) return;
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

    private float letterKeyWidth() {
        for (Cell c : keys) if (c.key.isLetter()) return c.rect.width() + hGap;
        return 36 * dp;
    }

    private boolean canStartGesture(Finger f) {
        if (!prefs.gesture() || !isLetterLayout() || f.cell == null || f.cell.key == null) return false;
        if (!f.cell.key.isLetter() || f.longPressed || f.alts != null) return false;
        return f.downTime - lastTapAt >= prefs.gestureCooldown();
    }

    private void fingerMove(int id, float x, float y) {
        Finger f = fingers.get(id);
        if (f == null || f.done) return;
        float dx = x - f.downX, dy = y - f.downY;

        // strip: swipe down hides the keyboard, horizontal drag scrolls the toolbar
        if (inStrip(f.downY) && (f.cell == null || f.cell.key == null)) {
            if (prefs.toolbarSwipeDown() && dy > 40 * dp && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                if (f.cell != null) release(f.cell);
                f.done = true;
                actions.onHide();
                return;
            }
            boolean onToolRow = showToolRow() && f.cell != null && f.cell.tool != null && toolCells.contains(f.cell)
                    || (f.cell == null && showToolRow());
            if (onToolRow && toolContentW > toolAreaR - toolAreaL && (f.scrolling || Math.abs(dx) > 8 * dp)) {
                if (!f.scrolling && f.cell != null) release(f.cell);
                f.scrolling = true;
                toolScroll = clamp(f.scrollStart - dx, 0, toolContentW - (toolAreaR - toolAreaL));
                invalidate();
            }
            return;
        }
        if (f.cell == null) return;
        Layouts.Key k = f.cell.key;
        if (f.alts != null) {
            float cell = f.altRect.width() / f.alts.size();
            int idx = (int) Math.floor((x - f.altRect.left) / cell);
            idx = Math.max(0, Math.min(f.alts.size() - 1, idx));
            if (idx != f.altIndex) { f.altIndex = idx; invalidate(); }
            return;
        }
        if (k == null) return;
        if (f.gesture) { gestureMove(f, x, y); return; }
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
            if (canStartGesture(f) && Math.hypot(dx, dy) > letterKeyWidth() * .6f) {
                startGesture(f, x, y);
                return;
            }
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

    // ---- gesture typing

    private void startGesture(Finger f, float x, float y) {
        f.gesture = true;
        if (f.longPress != null) handler.removeCallbacks(f.longPress);
        release(f.cell);
        trail.clear();
        segXs.clear();
        segYs.clear();
        gn = 0;
        floatingText = null;
        lastPreviewAt = 0;
        Map<Character, float[]> centers = new HashMap<>();
        for (Cell c : keys) {
            if (!c.key.isLetter()) continue;
            float[] pos = {c.rect.centerX(), c.rect.centerY()};
            centers.put(Character.toLowerCase(c.key.label.charAt(0)), pos);
            for (int i = 0; i < c.key.alts.length(); i++) {
                char a = c.key.alts.charAt(i);
                if (Character.isLetter(a) && !centers.containsKey(a)) centers.put(a, pos);
            }
        }
        actions.onGestureStart(centers, letterKeyWidth());
        addGesturePoint(f.downX, f.downY);
        addGesturePoint(x, y);
        invalidate();
    }

    private void addGesturePoint(float x, float y) {
        long now = SystemClock.uptimeMillis();
        trail.add(new float[]{x, y, now});
        if (gn == gx.length) { gx = java.util.Arrays.copyOf(gx, gn * 2); gy = java.util.Arrays.copyOf(gy, gn * 2); }
        gx[gn] = x; gy[gn] = y; gn++;
    }

    private void endSegment() {
        if (gn >= 2) {
            segXs.add(java.util.Arrays.copyOf(gx, gn));
            segYs.add(java.util.Arrays.copyOf(gy, gn));
        }
        gn = 0;
    }

    private void gestureMove(Finger f, float x, float y) {
        float lx = gn > 0 ? gx[gn - 1] : x, ly = gn > 0 ? gy[gn - 1] : y;
        if (Math.hypot(x - lx, y - ly) < 3 * dp && gn > 0) return;
        if (prefs.gestureSpaceAware()) {
            Cell over = hit(x, y);
            boolean onSpace = over != null && over.key != null && over.key.kind == Layouts.Kind.SPACE;
            if (onSpace && !f.overSpace) { endSegment(); trail.add(new float[]{x, y, -1}); }
            f.overSpace = onSpace;
            if (onSpace) { trail.add(new float[]{x, y, SystemClock.uptimeMillis()}); invalidate(); return; }
        }
        addGesturePoint(x, y);
        floatX = x;
        floatY = y;
        long now = SystemClock.uptimeMillis();
        if (prefs.gestureFloating() && now - lastPreviewAt > PREVIEW_EVERY_MS && gn >= 3) {
            lastPreviewAt = now;
            floatingText = actions.onGestureProgress(gx, gy, gn);
        }
        invalidate();
    }

    private void finishGesture() {
        endSegment();
        floatingText = null;
        if (!segXs.isEmpty()) actions.onGesture(new ArrayList<>(segXs), new ArrayList<>(segYs));
        segXs.clear();
        segYs.clear();
        // keep the trail fading out
        postInvalidateOnAnimation();
    }

    private void fingerUp(int id, float x, float y) {
        Finger f = fingers.get(id);
        if (f == null) return;
        if (!f.done) {
            if (f.gesture) {
                f.done = true;
                finishGesture();
            } else if (f.cell == null || f.scrolling) {
                f.done = true;
            } else if (f.cell.key == null) {
                if (f.cell.rect.contains(x + (toolCells.contains(f.cell) ? toolScroll : 0), y)
                        || distance(f.cell.rect, x + (toolCells.contains(f.cell) ? toolScroll : 0), y) < 12 * dp)
                    runStripCell(f.cell);
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
                        lastTapAt = SystemClock.uptimeMillis();
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

    /** Gesture word committed: shift applies to it like to a typed letter. */
    String applyGestureShift(String word) {
        if (word == null || word.isEmpty()) return word;
        String out = word;
        if (shift == SHIFT_LOCK) out = word.toUpperCase(locale());
        else if (shift == SHIFT_ONCE) out = word.substring(0, 1).toUpperCase(locale()) + word.substring(1);
        if (shift == SHIFT_ONCE) { shift = SHIFT_OFF; invalidate(); }
        return out;
    }

    private void runStripCell(Cell c) {
        switch (c.id) {
            case CELL_TOGGLE:
                expanded = !expanded;
                invalidate();
                return;
            case CELL_RESIZE_DONE:
                resizeMode = false;
                setToolState(ToolKey.RESIZE, false);
                readDimensions();
                requestLayout();
                relayout();
                return;
            case CELL_RESIZE_MINUS:
            case CELL_RESIZE_PLUS: {
                int pct = prefs.keyHeight() + (c.id == CELL_RESIZE_PLUS ? 5 : -5);
                prefs.setInt(Prefs.KEY_HEIGHT, Math.max(70, Math.min(140, pct)));
                readDimensions();
                requestLayout();
                relayout();
                return;
            }
            case CELL_ONEHAND_SWAP:
                prefs.setInt(Prefs.ONE_HANDED, prefs.oneHanded() == 1 ? 2 : 1);
                relayout();
                return;
            case CELL_ONEHAND_EXIT:
                prefs.setInt(Prefs.ONE_HANDED, 0);
                setToolState(ToolKey.ONE_HANDED, false);
                relayout();
                return;
            default: break;
        }
        if (c.id >= CELL_SLOT && c.id < CELL_SLOT + 10) { actions.onSuggestion(c.id - CELL_SLOT); return; }
        if (c.tool != null) {
            switch (c.tool) {
                case NUMPAD:
                    layout = "num".equals(layout.id) ? letters() : Layouts.numpad();
                    relayout();
                    break;
                case ONE_HANDED: {
                    boolean on = prefs.oneHanded() == 0;
                    prefs.setInt(Prefs.ONE_HANDED, on ? 1 : 0);
                    setToolState(ToolKey.ONE_HANDED, on);
                    relayout();
                    break;
                }
                case RESIZE:
                    resizeMode = true;
                    setToolState(ToolKey.RESIZE, true);
                    readDimensions();
                    requestLayout();
                    relayout();
                    break;
                default:
                    actions.onToolKey(c.tool);
            }
        }
    }

    private void clearFinger(Finger f) {
        if (f.cell != null) release(f.cell);
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
        floatingText = null;
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
            if (f.done || f.cell == null) continue;
            if (best == null || f.downTime >= best.downTime) best = f;
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

    /** Strip parts first, then the nearest key in the touched row (gaps and margins included). */
    private Cell hit(float x, float y) {
        if (inStrip(y)) {
            if (toolbarH == 0) return null;
            if (Prefs.MODE_EXPANDABLE.equals(mode) && distance(toggleCell.rect, x, y) < 6 * dp) return toggleCell;
            if (showSlots()) {
                for (Cell c : pinnedCells) if (distance(c.rect, x, y) < 6 * dp) return c;
                if (suggestions != null) {
                    for (int i = 0; i < slotCells.length && i < suggestions.length; i++) {
                        Cell c = slotCells[i];
                        if (x >= c.rect.left - 2 * dp && x <= c.rect.right + 2 * dp)
                            return suggestions[i] != null ? c : null;
                    }
                }
            }
            if (showToolRow()) {
                float sx = x + toolScroll;
                Cell best = null;
                float bestD = 10 * dp;
                for (Cell t : toolCells) {
                    float d = distance(t.rect, sx, y);
                    if (d <= bestD) { bestD = d; best = t; }
                }
                return best;
            }
            return null;
        }
        if (prefs.oneHanded() != 0) {
            if (distance(swapCell.rect, x, y) < 8 * dp) return swapCell;
            if (distance(exitCell.rect, x, y) < 8 * dp) return exitCell;
            if (x < keysLeft - hGap || x > keysRight + hGap) return null;
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

    private Locale locale() { return "en".equals(lang) ? Locale.ENGLISH : Locale.forLanguageTag("ru"); }

    private String labelFor(Layouts.Key k) {
        if (k.code == Layouts.ALPHA) return "en".equals(lang) ? "ABC" : "АБВ";
        return k.kind == Layouts.Kind.CHAR ? applyShift(k.label) : k.label;
    }

    private String applyShift(String s) {
        if (shift == SHIFT_OFF || !isLetterLayout()) return s;
        return s.toUpperCase(locale());
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
