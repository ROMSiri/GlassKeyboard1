package dev.glasskeyboard.ime;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/** Toolbar keys: title for settings and an icon drawn as paths in a 24dp box. */
enum ToolKey {
    SETTINGS("Настройки"),
    VOICE("Голосовой ввод"),
    UNDO("Отменить"),
    REDO("Повторить"),
    SELECT_WORD("Выделить слово"),
    SELECT_ALL("Выделить всё"),
    COPY("Копировать"),
    CUT("Вырезать"),
    PASTE("Вставить"),
    LEFT("Курсор влево"),
    RIGHT("Курсор вправо"),
    UP("Курсор вверх"),
    DOWN("Курсор вниз"),
    WORD_LEFT("На слово влево"),
    WORD_RIGHT("На слово вправо"),
    FULL_LEFT("В начало строки"),
    FULL_RIGHT("В конец строки"),
    PAGE_UP("Страница вверх"),
    PAGE_DOWN("Страница вниз"),
    PAGE_START("В начало текста"),
    PAGE_END("В конец текста"),
    NUMPAD("Цифровая панель"),
    ONE_HANDED("Режим одной руки"),
    RESIZE("Изменить высоту"),
    INCOGNITO("Инкогнито (не запоминать слова)"),
    AUTOCORRECT("Автоисправление вкл/выкл"),
    THEME("Светлая/тёмная для этого приложения"),
    HIDE("Скрыть клавиатуру");

    final String title;
    ToolKey(String title) { this.title = title; }

    static final ToolKey[] DEFAULT_ENABLED = {SETTINGS, VOICE, UNDO, REDO, SELECT_WORD, COPY, PASTE, LEFT, RIGHT, RESIZE, THEME, HIDE};
    static final ToolKey DEFAULT_PERSISTENT = VOICE;

    /** "NAME:true;NAME:false;..." – order and on/off. */
    static List<ToolKey> parse(String pref, boolean enabledOnly) {
        List<ToolKey> out = new ArrayList<>();
        if (pref != null && !pref.isEmpty()) {
            for (String part : pref.split(";")) {
                String[] kv = part.split(":");
                try {
                    ToolKey k = valueOf(kv[0]);
                    if (!out.contains(k) && (!enabledOnly || kv.length < 2 || Boolean.parseBoolean(kv[1]))) out.add(k);
                } catch (IllegalArgumentException ignored) { }
            }
            // keys added in an update are on by default for already saved toolbars
            if (enabledOnly && !pref.contains(THEME.name() + ":")) out.add(THEME);
        }
        return out;
    }

    static String defaultPref() {
        StringBuilder sb = new StringBuilder();
        List<ToolKey> on = java.util.Arrays.asList(DEFAULT_ENABLED);
        for (ToolKey k : DEFAULT_ENABLED) sb.append(k.name()).append(":true;");
        for (ToolKey k : values()) if (!on.contains(k)) sb.append(k.name()).append(":false;");
        return sb.toString();
    }

    /** All keys in stored order, unknown/new keys appended. */
    static List<ToolKey> ordered(String pref) {
        List<ToolKey> out = parse(pref, false);
        for (ToolKey k : values()) if (!out.contains(k)) out.add(k);
        return out;
    }

    // ------------------------------------------------------------------ icons

    void draw(Canvas c, float cx, float cy, float u, Paint p, Path path, RectF tmp) {
        path.reset();
        switch (this) {
            case SETTINGS:
                c.drawLine(cx - 7 * u, cy - 3.5f * u, cx + 7 * u, cy - 3.5f * u, p);
                c.drawLine(cx - 7 * u, cy + 3.5f * u, cx + 7 * u, cy + 3.5f * u, p);
                fillCircle(c, cx - 2.5f * u, cy - 3.5f * u, 2 * u, p);
                fillCircle(c, cx + 3 * u, cy + 3.5f * u, 2 * u, p);
                return;
            case VOICE:
                tmp.set(cx - 3 * u, cy - 9 * u, cx + 3 * u, cy + 2 * u);
                c.drawRoundRect(tmp, 3 * u, 3 * u, p);
                tmp.set(cx - 6.5f * u, cy - 4 * u, cx + 6.5f * u, cy + 5.5f * u);
                c.drawArc(tmp, 0, 180, false, p);
                c.drawLine(cx, cy + 5.5f * u, cx, cy + 9 * u, p);
                return;
            case UNDO: case REDO: {
                float s = this == UNDO ? 1 : -1;
                tmp.set(cx - 6 * u, cy - 5 * u, cx + 6 * u, cy + 7 * u);
                c.drawArc(tmp, s > 0 ? 200 : -20, s > 0 ? 250 : -250, false, p);
                float hx = cx - s * 5.6f * u, hy = cy - 2.5f * u;
                path.moveTo(hx - s * 0.5f * u, hy - 4.5f * u);
                path.lineTo(hx, hy);
                path.lineTo(hx + s * 4.5f * u, hy + 0.5f * u);
                break;
            }
            case SELECT_WORD:
                c.drawLine(cx - 6 * u, cy + 6 * u, cx + 6 * u, cy + 6 * u, p);
                bracket(c, cx - 8 * u, cy, u, 1, p);
                bracket(c, cx + 8 * u, cy, u, -1, p);
                c.drawLine(cx - 4 * u, cy - 1 * u, cx + 4 * u, cy - 1 * u, p);
                return;
            case SELECT_ALL:
                for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2) {
                    float x = cx + i * 7 * u, y = cy + j * 7 * u;
                    c.drawLine(x, y, x - i * 3 * u, y, p);
                    c.drawLine(x, y, x, y - j * 3 * u, p);
                }
                tmp.set(cx - 3 * u, cy - 3 * u, cx + 3 * u, cy + 3 * u);
                c.drawRect(tmp, p);
                return;
            case COPY:
                tmp.set(cx - 7 * u, cy - 7 * u, cx + 3 * u, cy + 4 * u);
                c.drawRoundRect(tmp, 2 * u, 2 * u, p);
                tmp.set(cx - 3 * u, cy - 3 * u, cx + 7 * u, cy + 8 * u);
                c.drawRoundRect(tmp, 2 * u, 2 * u, p);
                return;
            case CUT:
                c.drawCircle(cx - 4 * u, cy + 5 * u, 2.6f * u, p);
                c.drawCircle(cx + 4 * u, cy + 5 * u, 2.6f * u, p);
                c.drawLine(cx - 2.3f * u, cy + 3 * u, cx + 5 * u, cy - 8 * u, p);
                c.drawLine(cx + 2.3f * u, cy + 3 * u, cx - 5 * u, cy - 8 * u, p);
                return;
            case PASTE:
                tmp.set(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 8 * u);
                c.drawRoundRect(tmp, 2 * u, 2 * u, p);
                tmp.set(cx - 3 * u, cy - 8.5f * u, cx + 3 * u, cy - 4.5f * u);
                c.drawRoundRect(tmp, 1.5f * u, 1.5f * u, p);
                return;
            case LEFT: chevron(path, cx + 1.5f * u, cy, u, -1, 0); break;
            case RIGHT: chevron(path, cx - 1.5f * u, cy, u, 1, 0); break;
            case UP: chevron(path, cx, cy + 1.5f * u, u, 0, -1); break;
            case DOWN: chevron(path, cx, cy - 1.5f * u, u, 0, 1); break;
            case WORD_LEFT: chevron(path, cx - 1 * u, cy, u, -1, 0); chevron(path, cx + 5 * u, cy, u, -1, 0); break;
            case WORD_RIGHT: chevron(path, cx + 1 * u, cy, u, 1, 0); chevron(path, cx - 5 * u, cy, u, 1, 0); break;
            case FULL_LEFT: chevron(path, cx + 3 * u, cy, u, -1, 0); c.drawLine(cx - 5 * u, cy - 7 * u, cx - 5 * u, cy + 7 * u, p); break;
            case FULL_RIGHT: chevron(path, cx - 3 * u, cy, u, 1, 0); c.drawLine(cx + 5 * u, cy - 7 * u, cx + 5 * u, cy + 7 * u, p); break;
            case PAGE_UP: chevron(path, cx, cy + 4 * u, u, 0, -1); chevron(path, cx, cy - 2 * u, u, 0, -1); break;
            case PAGE_DOWN: chevron(path, cx, cy - 4 * u, u, 0, 1); chevron(path, cx, cy + 2 * u, u, 0, 1); break;
            case PAGE_START: chevron(path, cx, cy + 3 * u, u, 0, -1); c.drawLine(cx - 7 * u, cy - 5 * u, cx + 7 * u, cy - 5 * u, p); break;
            case PAGE_END: chevron(path, cx, cy - 3 * u, u, 0, 1); c.drawLine(cx - 7 * u, cy + 5 * u, cx + 7 * u, cy + 5 * u, p); break;
            case NUMPAD:
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)
                    fillCircle(c, cx + i * 5.5f * u, cy + j * 5.5f * u, 1.4f * u, p);
                return;
            case ONE_HANDED:
                tmp.set(cx - 8 * u, cy - 6 * u, cx + 8 * u, cy + 6 * u);
                c.drawRoundRect(tmp, 2 * u, 2 * u, p);
                tmp.set(cx - 1 * u, cy - 3 * u, cx + 5.5f * u, cy + 3 * u);
                c.drawRoundRect(tmp, 1 * u, 1 * u, p);
                return;
            case RESIZE:
                c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, p);
                chevron(path, cx, cy - 5 * u, u * .8f, 0, -1);
                chevron(path, cx, cy + 5 * u, u * .8f, 0, 1);
                c.drawLine(cx - 8 * u, cy, cx - 4 * u, cy, p);
                c.drawLine(cx + 4 * u, cy, cx + 8 * u, cy, p);
                break;
            case INCOGNITO:
                c.drawCircle(cx - 4 * u, cy + 3.5f * u, 2.8f * u, p);
                c.drawCircle(cx + 4 * u, cy + 3.5f * u, 2.8f * u, p);
                c.drawLine(cx - 1.2f * u, cy + 3 * u, cx + 1.2f * u, cy + 3 * u, p);
                c.drawLine(cx - 9 * u, cy - 1.5f * u, cx + 9 * u, cy - 1.5f * u, p);
                path.moveTo(cx - 6 * u, cy - 1.5f * u);
                path.lineTo(cx - 4 * u, cy - 8 * u);
                path.lineTo(cx + 4 * u, cy - 8 * u);
                path.lineTo(cx + 6 * u, cy - 1.5f * u);
                break;
            case AUTOCORRECT:
                path.moveTo(cx - 8 * u, cy + 6 * u);
                path.lineTo(cx - 4 * u, cy - 6 * u);
                path.lineTo(cx, cy + 6 * u);
                path.moveTo(cx - 6.5f * u, cy + 2 * u);
                path.lineTo(cx - 1.5f * u, cy + 2 * u);
                path.moveTo(cx + 2 * u, cy + 1 * u);
                path.lineTo(cx + 4.5f * u, cy + 4 * u);
                path.lineTo(cx + 9 * u, cy - 3 * u);
                break;
            case THEME: {
                c.drawCircle(cx, cy, 7.5f * u, p);
                Paint.Style st = p.getStyle();
                p.setStyle(Paint.Style.FILL);
                tmp.set(cx - 7.5f * u, cy - 7.5f * u, cx + 7.5f * u, cy + 7.5f * u);
                c.drawArc(tmp, -90, 180, true, p);
                p.setStyle(st);
                break;
            }
            case HIDE:
            default:
                path.moveTo(cx - 6.5f * u, cy - 3 * u);
                path.lineTo(cx, cy + 3.5f * u);
                path.lineTo(cx + 6.5f * u, cy - 3 * u);
        }
        c.drawPath(path, p);
    }

    private static void chevron(Path path, float cx, float cy, float u, int dx, int dy) {
        if (dx != 0) {
            path.moveTo(cx - dx * 3 * u, cy - 6 * u);
            path.lineTo(cx + dx * 3 * u, cy);
            path.lineTo(cx - dx * 3 * u, cy + 6 * u);
        } else {
            path.moveTo(cx - 6 * u, cy - dy * 3 * u);
            path.lineTo(cx, cy + dy * 3 * u);
            path.lineTo(cx + 6 * u, cy - dy * 3 * u);
        }
    }

    private static void bracket(Canvas c, float x, float cy, float u, int dir, Paint p) {
        c.drawLine(x, cy - 7 * u, x, cy + 7 * u, p);
        c.drawLine(x, cy - 7 * u, x + dir * 2.5f * u, cy - 7 * u, p);
        c.drawLine(x, cy + 7 * u, x + dir * 2.5f * u, cy + 7 * u, p);
    }

    private static void fillCircle(Canvas c, float x, float y, float r, Paint p) {
        Paint.Style s = p.getStyle();
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        c.drawCircle(x, y, r, p);
        p.setStyle(s);
    }
}
