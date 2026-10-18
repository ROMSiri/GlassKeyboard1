package dev.glasskeyboard.ime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** All settings screens, built in code in a ColorOS-like card style. Screen chosen by the "screen" extra. */
public final class SettingsActivity extends Activity {
    static final String SCREEN = "screen";
    private static final String MAIN = "main", CORRECTION = "correction", TOOLBAR = "toolbar",
            GESTURE = "gesture", PERSONAL = "personal", TOOL_KEYS = "tool_keys", PINNED = "pinned",
            CUSTOM_CODES = "custom_codes", PUNCTUATION = "punctuation", APPEARANCE = "appearance";
    private static final int REQ_CONTACTS = 7;

    // icon tile colours
    private static final int BLUE = 0xff3478f6, GREEN = 0xff34c759, ORANGE = 0xffff9500, PURPLE = 0xffaf52de,
            PINK = 0xffff2d55, TEAL = 0xff30b0c7, INDIGO = 0xff5856d6, RED = 0xffff3b30, GRAY = 0xff8e8e93,
            YELLOW = 0xffffb800, MINT = 0xff00c7be;

    private Prefs prefs;
    private String screen;
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout card;          // card rows are added to
    private TextView statusTitle, statusText;
    private LinearLayout statusButtons;
    private boolean dark;
    private int accent, pageBg, cardBg, textColor, subColor, divider;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        screen = getIntent().getStringExtra(SCREEN);
        if (screen == null) screen = MAIN;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        accent = 0xff3478f6;
        if (Build.VERSION.SDK_INT >= 31) {
            try { accent = getColor(dark ? android.R.color.system_accent1_300 : android.R.color.system_accent1_600); }
            catch (Exception ignored) { }
        }
        pageBg = dark ? 0xff0b0b0d : 0xfff2f3f7;
        cardBg = dark ? 0xff1c1c1f : 0xffffffff;
        textColor = dark ? 0xfff2f2f4 : 0xff1d1d1f;
        subColor = dark ? 0xff9a9aa1 : 0xff6e6e73;
        divider = dark ? 0x22ffffff : 0x14000000;

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top; bottom = i.bottom;
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(scroll);   // creates the window decor; system bars can be styled after this
        setupWindow();
        build();
    }

    private void setupWindow() {
        getWindow().setBackgroundDrawable(new ColorDrawable(pageBg));
        getWindow().getDecorView(); // make sure the decor exists
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(0);
            getWindow().setNavigationBarColor(0);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            getWindow().setStatusBarColor(pageBg);
            getWindow().setNavigationBarColor(pageBg);
            //noinspection deprecation
            if (!dark) getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (MAIN.equals(screen)) updateStatus();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && MAIN.equals(screen)) updateStatus();
    }

    private void build() {
        content.removeAllViews();
        card = null;
        switch (screen) {
            case CORRECTION: buildCorrection(); break;
            case TOOLBAR: buildToolbar(); break;
            case GESTURE: buildGesture(); break;
            case APPEARANCE: buildAppearance(); break;
            case PERSONAL: buildPersonal(); break;
            case TOOL_KEYS: buildKeyList(Prefs.TOOLBAR_KEYS, prefs.toolbarKeys(), "Клавиши панели",
                    "Включите нужные клавиши и расставьте их стрелками — в таком порядке они будут на панели."); break;
            case PINNED: buildKeyList(Prefs.PINNED_KEYS, prefs.pinnedKeys(), "Закреплённые клавиши",
                    "Видны справа от подсказок, когда панель свёрнута."); break;
            case CUSTOM_CODES: buildCustomCodes(); break;
            case PUNCTUATION: buildPunctuation(); break;
            default: buildMain();
        }
    }

    /** Rebuild keeping the scroll position (used when a switch shows/hides dependent rows). */
    private void rebuild() {
        int y = scroll.getScrollY();
        build();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ------------------------------------------------------------------ screens

    private void buildMain() {
        TextView title = text("Glass Keyboard", 34, textColor, true);
        title.setPadding(dp(4), dp(8), 0, 0);
        content.addView(title);
        TextView sub = text("Клавиатура со стеклом ColorOS 17", 15, subColor, false);
        sub.setPadding(dp(4), dp(2), 0, dp(14));
        content.addView(sub);

        content.addView(new HeroView(this, accent, dark), matchWrap());

        // status card
        LinearLayout status = cardView();
        status.setPadding(dp(18), dp(16), dp(18), dp(16));
        statusTitle = text("", 17, textColor, true);
        statusText = text("", 14, subColor, false);
        statusText.setPadding(0, dp(3), 0, 0);
        status.addView(statusTitle);
        status.addView(statusText);
        statusButtons = new LinearLayout(this);
        statusButtons.setPadding(0, dp(12), 0, 0);
        status.addView(statusButtons, matchWrap());
        LinearLayout.LayoutParams sp = matchWrap();
        sp.topMargin = dp(14);
        content.addView(status, sp);

        section("Настройки");
        link(Glyph.CORRECTION, BLUE, "Исправление текста", "Автоисправление, подсказки, эмодзи", () -> open(CORRECTION));
        link(Glyph.TOOLBAR, ORANGE, "Панель инструментов", "Режим, клавиши, закреплённые", () -> open(TOOLBAR));
        link(Glyph.GESTURE, PURPLE, "Непрерывный ввод", "Набор слов жестами", () -> open(GESTURE));
        link(Glyph.BLUR, TEAL, "Внешний вид и отклик", "Стекло, высота, вибрация, звук", () -> open(APPEARANCE));
        link(Glyph.BOOK, GREEN, "Личный словарь", "Выученные и добавленные слова", () -> open(PERSONAL));

        section("Проверка");
        EditText test = field("Попробуйте клавиатуру здесь", 3);
        card.addView(test, fieldParams());
        footnote("Проверка правописания в других приложениях: Настройки Android → Язык и ввод → "
                + "Проверка правописания → «Glass Keyboard».");
        updateStatus();
    }

    private void buildAppearance() {
        header("Внешний вид и отклик");
        section("Стекло");
        toggle(Glyph.BLUR, TEAL, Prefs.BLUR, "Размытие фона", "Матовое стекло под клавиатурой", true, false);
        slider(Glyph.OPACITY, INDIGO, Prefs.OPACITY, "Плотность панели", 50, 100, "%", prefs.opacity());
        String mode = prefs.blurStatus();
        footnote(getString(R.string.blur_status, mode.isEmpty() ? getString(R.string.blur_unknown) : mode)
                + "\n\n" + getString(R.string.blur_note));

        section("Размер");
        slider(Glyph.HEIGHT, BLUE, Prefs.KEY_HEIGHT, "Высота клавиш", 70, 140, "%", prefs.keyHeight());
        slider(Glyph.OFFSET, MINT, Prefs.BOTTOM_OFFSET, "Отступ снизу", 0, 48, " dp", prefs.bottomOffset());

        section("Отклик");
        toggle(Glyph.PREVIEW, ORANGE, Prefs.PREVIEW, "Увеличение при нажатии", "Буква всплывает над клавишей", true, false);
        toggle(Glyph.VIBRATE, PINK, Prefs.VIBRATE, "Вибрация", null, true, false);
        toggle(Glyph.SOUND, GRAY, Prefs.SOUND, "Звук нажатий", null, false, false);
    }

    private void buildCorrection() {
        header("Исправление текста");
        section(null);
        link(Glyph.BOOK, GREEN, "Личный словарь", "Ваши слова", () -> open(PERSONAL));

        section("Исправления");
        toggle(Glyph.SHIELD, RED, Prefs.BLOCK_OFFENSIVE, "Блокировать нецензурные слова",
                "Не предлагать слова, которые могут быть сочтены оскорбительными", true, false);
        boolean ac = toggle(Glyph.MAGIC, BLUE, Prefs.AUTOCORRECT, "Автоисправление",
                "Исправлять опечатки при вводе пробела или знака препинания", true, true);
        if (ac) {
            toggle(Glyph.MAGIC, INDIGO, Prefs.MORE_AUTOCORRECT, "Больше автоисправлений",
                    "Исправлять, даже если поле ввода этого не просит", true, false);
            toggle(Glyph.SHORTCUT, TEAL, Prefs.AUTOCORRECT_SHORTCUTS, "Автоисправление сокращений",
                    "«щас» → «сейчас», «wont» → «won't»", true, false);
            choice(Glyph.PRECISION, ORANGE, Prefs.AUTOCORRECT_THRESHOLD, "Точность автоисправления",
                    new String[]{"Скромная", "Агрессивная", "Очень агрессивная"}, prefs.autocorrectThreshold());
            toggle(Glyph.BACKSPACE, GRAY, Prefs.BACKSPACE_REVERTS, "Backspace отменяет исправление",
                    "⌫ сразу после исправления возвращает набранное", true, false);
        }
        toggle(Glyph.CAPS, PURPLE, Prefs.AUTO_CAPS, "Заглавные автоматически",
                "Заглавная буква в начале предложения", true, false);

        section("Пробел");
        toggle(Glyph.DOUBLE_SPACE, BLUE, Prefs.DOUBLE_SPACE, "Двойной пробел — точка",
                "Двойное нажатие пробела ставит точку и пробел", true, false);
        toggle(Glyph.PUNCT, ORANGE, Prefs.AUTOSPACE_PUNCTUATION, "Пробел после знаков препинания",
                "Добавлять пробел после «,» «.» «!» при вводе нового слова", false, false);
        toggle(Glyph.SPACE, GREEN, Prefs.AUTOSPACE_SUGGESTION, "Пробел после выбора подсказки", null, true, false);
        if (prefs.gesture()) {
            toggle(Glyph.GESTURE, PURPLE, Prefs.AUTOSPACE_BEFORE_GESTURE, "Пробел перед словом жестом", null, true, false);
            toggle(Glyph.GESTURE, INDIGO, Prefs.AUTOSPACE_AFTER_GESTURE, "Пробел после слова жестом", null, true, false);
        }
        toggle(Glyph.CAPS, GRAY, Prefs.SHIFT_REMOVES_AUTOSPACE, "Shift убирает автопробел",
                "Нажатие Shift удаляет автоматически добавленный пробел", false, false);

        section("Подсказки");
        boolean sugg = toggle(Glyph.SUGGEST, YELLOW, Prefs.SUGGESTIONS, "Показывать подсказки",
                "Варианты слов и исправлений во время ввода", true, true);
        if (sugg) {
            boolean always = toggle(Glyph.SUGGEST, ORANGE, Prefs.ALWAYS_SUGGESTIONS, "Всегда показывать подсказки",
                    "Даже если приложение просит их отключить (может вызвать проблемы)", false, true);
            if (always) toggle(Glyph.WEB, GRAY, Prefs.ALWAYS_SUGGESTIONS_EXCEPT_WEB, "Кроме полей веб-форм",
                    "В браузерах постоянные подсказки часто мешают", true, false);
            toggle(Glyph.CENTER, BLUE, Prefs.CENTER_SUGGESTION, "Всегда брать среднюю подсказку",
                    "Пробел или знак препинания вставляет среднюю подсказку", false, false);
            toggle(Glyph.FIVE, TEAL, Prefs.FIVE_CHIPS, "Пять подсказок", "Вместо трёх", false, false);
        }
        if (sugg || ac) {
            toggle(Glyph.EMOJI, YELLOW, Prefs.SUGGEST_EMOJIS, "Предлагать эмодзи", "Эмодзи к словам в подсказках", true, false);
            toggle(Glyph.SEARCH, PINK, Prefs.INLINE_EMOJI, "Поиск эмодзи в строке",
                    "Наберите двоеточие и слово: «:сердце»", true, false);
        }
        boolean personal = toggle(Glyph.LEARN, GREEN, Prefs.LEARN, "Персональные подсказки",
                "Учиться на том, что вы пишете", true, true);
        toggle(Glyph.NEXT, BLUE, Prefs.PREDICTIONS, "Подсказки следующего слова",
                "Учитывать предыдущее слово", true, false);
        boolean punct = toggle(Glyph.PUNCT, PURPLE, Prefs.SUGGEST_PUNCTUATION, "Подсказки знаков препинания",
                "Знаки в полосе, когда нет других подсказок", false, true);
        if (punct) link(Glyph.KEY, GRAY, "Набор знаков", prefs.punctuationList(), () -> open(PUNCTUATION));
        toggle(Glyph.CLIPBOARD, TEAL, Prefs.SUGGEST_CLIPBOARD, "Подсказка из буфера обмена",
                "Только что скопированный текст в полосе", true, false);
        ToggleView contacts = toggleView(Glyph.CONTACTS, ORANGE, Prefs.USE_CONTACTS, "Имена контактов",
                "Подсказывать и не исправлять имена из «Контактов»", false);
        contacts.setListener(on -> {
            prefs.set(Prefs.USE_CONTACTS, on);
            bumpWords();
            if (on && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
        });
        ToggleView apps = toggleView(Glyph.APPS, INDIGO, Prefs.USE_APPS, "Названия приложений",
                "Подсказывать названия установленных приложений", false);
        apps.setListener(on -> { prefs.set(Prefs.USE_APPS, on); bumpWords(); });
        if (personal) toggle(Glyph.BOOK, GREEN, Prefs.ADD_TO_SYSTEM_DICT, "Сохранять в словарь Android",
                "Выученные слова попадут в личный словарь устройства", false, false);
        toggle(Glyph.SPELL, RED, Prefs.SPELLCHECK_SUGGEST, "Варианты для слова под курсором",
                "Поставьте курсор в слово — появятся замены", true, false);
    }

    private void buildToolbar() {
        header("Панель инструментов");
        String mode = prefs.toolbarMode();
        String[] modes = {Prefs.MODE_EXPANDABLE, Prefs.MODE_KEYS, Prefs.MODE_STRIP, Prefs.MODE_HIDDEN};
        String[] labels = {"Расширяемая", "Только клавиши", "Только подсказки", "Скрыта"};
        int idx = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(mode)) idx = i;
        final int current = idx;
        section("Режим");
        link(Glyph.MODE, ORANGE, "Режим панели", labels[idx], () -> new AlertDialog.Builder(this)
                .setTitle("Режим панели")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    prefs.setString(Prefs.TOOLBAR_MODE, modes[which]);
                    d.dismiss();
                    rebuild();
                }).show());
        boolean hidden = Prefs.MODE_HIDDEN.equals(mode);
        if (!hidden) {
            toggle(Glyph.SWIPE_DOWN, BLUE, Prefs.TOOLBAR_SWIPE_DOWN, "Скрывать смахиванием вниз",
                    "Проведите вниз по панели, чтобы закрыть клавиатуру", false, false);
            section("Клавиши");
            link(Glyph.TOOLBAR, ORANGE, "Клавиши панели", ToolKey.parse(prefs.toolbarKeys(), true).size() + " включено",
                    () -> open(TOOL_KEYS));
            if (Prefs.MODE_EXPANDABLE.equals(mode) || Prefs.MODE_KEYS.equals(mode))
                link(Glyph.PIN, RED, "Закреплённые клавиши", ToolKey.parse(prefs.pinnedKeys(), true).size() + " закреплено",
                        () -> open(PINNED));
            ToolKey persistent;
            try { persistent = ToolKey.valueOf(prefs.persistentKey()); } catch (IllegalArgumentException e) { persistent = ToolKey.VOICE; }
            final ToolKey cur = persistent;
            link(Glyph.KEY, PURPLE, "Постоянная кнопка", cur.title, () -> {
                ToolKey[] all = ToolKey.values();
                String[] names = new String[all.length];
                for (int i = 0; i < all.length; i++) names[i] = all[i].title;
                new AlertDialog.Builder(this).setTitle("Постоянная кнопка")
                        .setSingleChoiceItems(names, cur.ordinal(), (d, which) -> {
                            prefs.setString(Prefs.PERSISTENT_KEY, all[which].name());
                            d.dismiss();
                            rebuild();
                        }).show();
            });
            footnote("Постоянная кнопка видна в режиме «Только подсказки».");
            section("Для опытных");
            link(Glyph.CODE, GRAY, "Коды клавиш панели", "Своя клавиша или текст", () -> open(CUSTOM_CODES));
        }
    }

    private void buildGesture() {
        header("Непрерывный ввод");
        section(null);
        boolean on = toggle(Glyph.GESTURE, PURPLE, Prefs.GESTURE, "Набор жестами",
                "Проводите по буквам, не отрывая палец", true, true);
        if (!on) return;
        section("Вид");
        boolean trail = toggle(Glyph.TRAIL, BLUE, Prefs.GESTURE_TRAIL, "Траектория жеста", "Светящийся след пальца", true, true);
        boolean floating = toggle(Glyph.FLOAT, ORANGE, Prefs.GESTURE_FLOATING, "Плавающий предпросмотр",
                "Слово видно прямо во время жеста", true, true);
        if (floating) toggle(Glyph.FLOAT, INDIGO, Prefs.GESTURE_FLOATING_DYNAMIC, "Следовать за пальцем",
                "Предпросмотр движется вместе с пальцем", true, false);
        if (trail || floating) slider(Glyph.FADE, TEAL, Prefs.GESTURE_TRAIL_FADE, "Время жизни следа", 100, 2000, " мс", prefs.gestureTrailFade());
        section("Поведение");
        toggle(Glyph.PHRASE, GREEN, Prefs.GESTURE_SPACE_AWARE, "Ввод фраз",
                "Несколько слов одним жестом — проведите через пробел", false, false);
        slider(Glyph.TIMER, PINK, Prefs.GESTURE_COOLDOWN, "Пауза после быстрой печати", 0, 1000, " мс", prefs.gestureCooldown());
        footnote("После нажатия клавиши жест не начнётся в течение этого времени — быстрая печать не превратится в жест.");
    }

    // ---- personal dictionary

    private void buildPersonal() {
        header("Личный словарь");
        footnoteTop("Эти слова подсказываются и не исправляются.");
        section("Добавить");
        LinearLayout addRow = new LinearLayout(this);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(dp(12), dp(10), dp(10), dp(10));
        EditText input = field("Новое слово", 1);
        input.setSingleLine(true);
        addRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = pill("Добавить", true);
        add.setOnClickListener(v -> {
            String w = input.getText().toString().trim();
            if (w.isEmpty() || w.contains(" ")) return;
            String lang = isCyrillic(w) ? "ru" : "en";
            List<String[]> lines = readUserFile(lang);
            lines.add(new String[]{"w", "5", w});
            writeUserFile(lang, lines);
            rebuild();
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.leftMargin = dp(10);
        addRow.addView(add, ap);
        card.addView(addRow, matchWrap());

        for (String lang : new String[]{"ru", "en"}) {
            List<String[]> lines = readUserFile(lang);
            List<String[]> words = new ArrayList<>();
            for (String[] l : lines) if (l.length == 3 && "w".equals(l[0])) words.add(l);
            words.sort((a, b) -> a[2].compareToIgnoreCase(b[2]));
            section(("ru".equals(lang) ? "Русский" : "English") + " · " + words.size());
            if (words.isEmpty()) {
                TextView e = text("Пока пусто", 15, subColor, false);
                e.setPadding(dp(16), dp(16), dp(16), dp(16));
                card.addView(e);
                continue;
            }
            for (String[] w : words) {
                LinearLayout row = rowBase();
                TextView t = text(w[2], 16, textColor, false);
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                ImageView del = iconButton(Glyph.TRASH, RED);
                del.setOnClickListener(v -> {
                    List<String[]> all = readUserFile(lang);
                    all.removeIf(l -> l.length >= 3 && "w".equals(l[0]) && l[2].equals(w[2]));
                    writeUserFile(lang, all);
                    rebuild();
                });
                row.addView(del);
                addRow(row, false);
            }
        }
        section(null);
        LinearLayout danger = rowBase();
        danger.setGravity(Gravity.CENTER);
        danger.addView(text("Забыть все выученные слова", 16, RED, false));
        danger.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Удалить все выученные слова и пары слов?")
                .setPositiveButton("Удалить", (d, x) -> {
                    for (String lang : new String[]{"ru", "en"}) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(getFilesDir(), "user_words_" + lang + ".txt").delete();
                    }
                    bumpWords();
                    rebuild();
                })
                .setNegativeButton("Отмена", null).show());
        addRow(danger, false);
    }

    private static boolean isCyrillic(String w) {
        for (int i = 0; i < w.length(); i++)
            if (Character.UnicodeBlock.of(w.charAt(i)) == Character.UnicodeBlock.CYRILLIC) return true;
        return false;
    }

    private List<String[]> readUserFile(String lang) {
        List<String[]> out = new ArrayList<>();
        File f = new File(getFilesDir(), "user_words_" + lang + ".txt");
        if (!f.exists()) return out;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) out.add(line.split("\t"));
        } catch (Exception ignored) { }
        return out;
    }

    private void writeUserFile(String lang, List<String[]> lines) {
        File f = new File(getFilesDir(), "user_words_" + lang + ".txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (String[] l : lines) w.write(String.join("\t", l) + "\n");
        } catch (Exception ignored) { }
        bumpWords();
    }

    /** Tells the keyboard to reload learned/extra words next time it opens. */
    private void bumpWords() { prefs.setInt(Prefs.USER_DICT_REV, prefs.getInt(Prefs.USER_DICT_REV, 0) + 1); }

    // ---- toolbar key lists

    private void buildKeyList(String key, String pref, String heading, String help) {
        header(heading);
        footnoteTop(help);
        section(null);
        List<ToolKey> order = ToolKey.ordered(pref);
        List<ToolKey> enabled = ToolKey.parse(pref, true);
        for (int i = 0; i < order.size(); i++) {
            ToolKey k = order.get(i);
            final int pos = i;
            LinearLayout row = rowBase();
            row.addView(iconTile(null, k, enabled.contains(k) ? accent : GRAY));
            TextView t = text(k.title, 16, textColor, false);
            t.setPadding(dp(14), 0, dp(6), 0);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView up = iconButton(null, 0);
            up.setImageDrawable(lineIcon(2, pos > 0 ? accent : (subColor & 0x00ffffff) | 0x55000000, true));
            up.setEnabled(pos > 0);
            up.setOnClickListener(v -> { java.util.Collections.swap(order, pos, pos - 1); saveKeyList(key, order, enabled); rebuild(); });
            ImageView down = iconButton(null, 0);
            down.setImageDrawable(lineIcon(3, pos < order.size() - 1 ? accent : (subColor & 0x00ffffff) | 0x55000000, true));
            down.setEnabled(pos < order.size() - 1);
            down.setOnClickListener(v -> { java.util.Collections.swap(order, pos, pos + 1); saveKeyList(key, order, enabled); rebuild(); });
            row.addView(up);
            row.addView(down);
            ToggleView tv = new ToggleView(this, accent, dark);
            tv.setChecked(enabled.contains(k), false);
            tv.setListener(on -> {
                if (on) enabled.add(k); else enabled.remove(k);
                saveKeyList(key, order, enabled);
                content.postDelayed(this::rebuild, 190);
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = dp(6);
            row.addView(tv, tp);
            row.setOnClickListener(v -> tv.toggle());
            addRow(row, true);
        }
    }

    private void saveKeyList(String key, List<ToolKey> order, List<ToolKey> enabled) {
        StringBuilder sb = new StringBuilder();
        for (ToolKey k : order) sb.append(k.name()).append(':').append(enabled.contains(k)).append(';');
        prefs.setString(key, sb.toString());
    }

    private void buildCustomCodes() {
        header("Коды клавиш");
        footnoteTop("По строке на клавишу: ИМЯ=КОД. КОД — клавиша Android (HOME, TAB, ESCAPE, F5 или число) "
                + "или любой текст, который будет вставлен. Нет строки — обычное действие.");
        section(null);
        EditText e = field("COPY=TAB\nSETTINGS=Привет!", 7);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTypeface(Typeface.MONOSPACE);
        e.setText(prefs.getString(Prefs.TOOLBAR_CUSTOM_CODES, ""));
        card.addView(e, fieldParams());
        StringBuilder sb = new StringBuilder();
        for (ToolKey k : ToolKey.values()) { if (sb.length() > 0) sb.append(", "); sb.append(k.name()); }
        footnote("Имена клавиш: " + sb);
        TextView save = pill("Сохранить", true);
        save.setOnClickListener(v -> { prefs.setString(Prefs.TOOLBAR_CUSTOM_CODES, e.getText().toString()); finish(); });
        content.addView(save, buttonParams());
    }

    private void buildPunctuation() {
        header("Набор знаков");
        footnoteTop("Знаки через пробел, в порядке показа на полосе подсказок.");
        section(null);
        EditText e = field("! ? , .", 1);
        e.setText(prefs.punctuationList());
        card.addView(e, fieldParams());
        TextView save = pill("Сохранить", true);
        save.setOnClickListener(v -> { prefs.setString(Prefs.PUNCTUATION_LIST, e.getText().toString().trim()); finish(); });
        content.addView(save, buttonParams());
        TextView reset = pill("Вернуть по умолчанию", false);
        reset.setOnClickListener(v -> { prefs.setString(Prefs.PUNCTUATION_LIST, Prefs.DEFAULT_PUNCTUATION); finish(); });
        content.addView(reset, buttonParams());
    }

    // ------------------------------------------------------------------ status

    private boolean imeEnabled() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm == null) return false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList())
            if (getPackageName().equals(info.getPackageName())) return true;
        return false;
    }

    private void updateStatus() {
        if (statusTitle == null) return;
        boolean enabled = imeEnabled();
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");
        statusButtons.removeAllViews();
        if (selected) {
            statusTitle.setText("✓  Клавиатура готова");
            statusTitle.setTextColor(GREEN);
            statusText.setText("Glass Keyboard включена и выбрана.");
            TextView other = pill("Сменить клавиатуру", false);
            other.setOnClickListener(v -> pickIme());
            statusButtons.addView(other);
        } else {
            statusTitle.setText(enabled ? "Шаг 2 из 2" : "Шаг 1 из 2");
            statusTitle.setTextColor(textColor);
            statusText.setText(enabled ? "Выберите Glass Keyboard как текущую клавиатуру."
                    : "Включите Glass Keyboard в системных настройках клавиатур.");
            TextView b = pill(enabled ? "Выбрать клавиатуру" : "Включить", true);
            b.setOnClickListener(v -> {
                if (imeEnabled()) pickIme();
                else startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            });
            statusButtons.addView(b);
        }
    }

    private void pickIme() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showInputMethodPicker();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_CONTACTS && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            prefs.set(Prefs.USE_CONTACTS, false);
            rebuild();
        }
    }

    // ------------------------------------------------------------------ building blocks

    private void open(String target) {
        startActivity(new Intent(this, SettingsActivity.class).putExtra(SCREEN, target));
    }

    /** Sub-screen header: round back button and a large title. */
    private void header(String titleText) {
        ImageView back = new ImageView(this);
        back.setImageDrawable(lineIcon(1, textColor, true));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(ripple(roundRect(cardBg, dp(22))));
        back.setOnClickListener(v -> finish());
        back.setContentDescription("Назад");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(44), dp(44));
        bp.topMargin = dp(8);
        content.addView(back, bp);
        TextView t = text(titleText, 32, textColor, true);
        t.setPadding(dp(4), dp(14), 0, dp(6));
        content.addView(t);
    }

    /** Starts a new card; {@code caption} above it (may be null). */
    private void section(String caption) {
        if (caption != null) {
            TextView c = text(caption.toUpperCase(), 12, subColor, false);
            c.setLetterSpacing(.06f);
            c.setPadding(dp(16), dp(22), dp(16), dp(7));
            content.addView(c);
        }
        card = cardView();
        LinearLayout.LayoutParams p = matchWrap();
        if (caption == null) p.topMargin = dp(16);
        content.addView(card, p);
    }

    private LinearLayout cardView() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRect(cardBg, dp(22)));
        c.setClipToOutline(true);
        return c;
    }

    private void footnote(String s) {
        TextView t = text(s, 13, subColor, false);
        t.setPadding(dp(16), dp(8), dp(16), 0);
        content.addView(t);
    }

    private void footnoteTop(String s) {
        TextView t = text(s, 15, subColor, false);
        t.setPadding(dp(4), 0, dp(4), dp(4));
        content.addView(t);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private LinearLayout rowBase() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(10), dp(14), dp(10));
        row.setBackground(ripple(null));
        return row;
    }

    private void addRow(View row) { addRow(row, true); }

    /** Adds a row to the current card with a divider before it (inset past the icon when there is one). */
    private void addRow(View row, boolean hasIcon) {
        if (card.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(divider);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
            lp.leftMargin = hasIcon ? dp(62) : dp(16);
            card.addView(d, lp);
        }
        card.addView(row, matchWrap());
    }

    private ImageView iconTile(Glyph g, ToolKey key, int color) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Glyph.tile(g, key, color, getResources().getDisplayMetrics().density));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        return iv;
    }

    /** Icon, title and summary; returns the row so a trailing view can be added. */
    private LinearLayout row(Glyph g, int color, String titleText, String summary) {
        LinearLayout row = rowBase();
        row.addView(iconTile(g, null, color));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(10), 0);
        texts.addView(text(titleText, 16, textColor, false));
        if (summary != null && !summary.isEmpty()) {
            TextView s = text(summary, 13, subColor, false);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void link(Glyph g, int color, String titleText, String value, Runnable action) {
        LinearLayout row = row(g, color, titleText, null);
        if (value != null) {
            TextView v = text(value, 14, subColor, false);
            v.setMaxWidth(dp(150));
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(v);
        }
        ImageView chev = new ImageView(this);
        chev.setImageDrawable(lineIcon(0, subColor, false));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(18), dp(18));
        cp.leftMargin = dp(6);
        row.addView(chev, cp);
        row.setOnClickListener(v -> action.run());
        addRow(row);
    }

    /** Switch bound to a boolean pref; returns its value. {@code rebuildOnChange} shows/hides dependents. */
    private boolean toggle(Glyph g, int color, String key, String titleText, String summary, boolean def, boolean rebuildOnChange) {
        ToggleView t = toggleView(g, color, key, titleText, summary, def);
        t.setListener(on -> {
            prefs.set(key, on);
            if (rebuildOnChange) content.postDelayed(this::rebuild, 190);
        });
        return prefs.get(key, def);
    }

    private ToggleView toggleView(Glyph g, int color, String key, String titleText, String summary, boolean def) {
        LinearLayout row = row(g, color, titleText, summary);
        ToggleView t = new ToggleView(this, accent, dark);
        t.setChecked(prefs.get(key, def), false);
        row.addView(t);
        row.setOnClickListener(v -> t.toggle());
        addRow(row);
        return t;
    }

    private void choice(Glyph g, int color, String key, String titleText, String[] labels, int value) {
        link(g, color, titleText, labels[Math.max(0, Math.min(labels.length - 1, value))], () -> new AlertDialog.Builder(this)
                .setTitle(titleText)
                .setSingleChoiceItems(labels, value, (d, which) -> {
                    prefs.setInt(key, which);
                    d.dismiss();
                    rebuild();
                }).show());
    }

    private void slider(Glyph g, int color, String key, String label, int min, int max, String unit, int value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = row(g, color, label, null);
        row.setBackground(null);
        TextView val = text(value + unit, 15, accent, true);
        row.addView(val);
        box.addView(row, matchWrap());
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0xff3a3a3f : 0xffd8d8dd));
        bar.setPadding(dp(62), 0, dp(24), dp(14));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                val.setText((progress + min) + unit);
                if (fromUser) prefs.setInt(key, progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        box.addView(bar, matchWrap());
        addRow(box);
    }

    private EditText field(String hint, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(subColor);
        e.setTextColor(textColor);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setBackground(roundRect(dark ? 0xff2a2a2e : 0xfff2f3f7, dp(14)));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(dp(12), dp(12), dp(12), dp(12));
        return p;
    }

    /** Rounded pill button: filled accent or tonal. */
    private TextView pill(String s, boolean filled) {
        TextView b = text(s, 15, filled ? 0xffffffff : accent, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(11), dp(20), dp(11));
        int bg = filled ? accent : (accent & 0x00ffffff) | (dark ? 0x33000000 : 0x1f000000);
        b.setBackground(ripple(roundRect(bg, dp(22))));
        b.setClickable(true);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(14);
        return p;
    }

    private ImageView iconButton(Glyph g, int color) {
        ImageView iv = new ImageView(this);
        if (g != null) iv.setImageDrawable(Glyph.tile(g, null, color, getResources().getDisplayMetrics().density));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(6), dp(6), dp(6), dp(6));
        iv.setBackground(ripple(null));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return iv;
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** Touch ripple over {@code content} (or over a transparent background). */
    private Drawable ripple(Drawable content) {
        int c = dark ? 0x33ffffff : 0x1f000000;
        return new RippleDrawable(ColorStateList.valueOf(c), content,
                content != null ? content : new ColorDrawable(0xffffffff));
    }

    /** 0 chevron right, 1 back arrow, 2 up, 3 down. */
    private Drawable lineIcon(int kind, int color, boolean thick) {
        float d = getResources().getDisplayMetrics().density;
        return new Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path path = new Path();
            @Override public void draw(Canvas c) {
                p.setColor(color);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth((thick ? 2.2f : 2f) * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY(), u = d;
                path.reset();
                switch (kind) {
                    case 0: path.moveTo(cx - 2.5f * u, cy - 6 * u); path.lineTo(cx + 3.5f * u, cy); path.lineTo(cx - 2.5f * u, cy + 6 * u); break;
                    case 1: path.moveTo(cx + 7 * u, cy); path.lineTo(cx - 7 * u, cy);
                        path.moveTo(cx - 1 * u, cy - 6 * u); path.lineTo(cx - 7 * u, cy); path.lineTo(cx - 1 * u, cy + 6 * u); break;
                    case 2: path.moveTo(cx - 6 * u, cy + 3 * u); path.lineTo(cx, cy - 3 * u); path.lineTo(cx + 6 * u, cy + 3 * u); break;
                    default: path.moveTo(cx - 6 * u, cy - 3 * u); path.lineTo(cx, cy + 3 * u); path.lineTo(cx + 6 * u, cy - 3 * u);
                }
                c.drawPath(path, p);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            @Override public int getIntrinsicWidth() { return Math.round(24 * d); }
            @Override public int getIntrinsicHeight() { return Math.round(24 * d); }
        };
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
