package dev.glasskeyboard.ime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
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

/** All settings screens, built in code (no extra libraries). Screen chosen by the "screen" extra. */
public final class SettingsActivity extends Activity {
    static final String SCREEN = "screen";
    private static final String MAIN = "main", CORRECTION = "correction", TOOLBAR = "toolbar",
            GESTURE = "gesture", PERSONAL = "personal", TOOL_KEYS = "tool_keys", PINNED = "pinned",
            CUSTOM_CODES = "custom_codes", PUNCTUATION = "punctuation";
    private static final int REQ_CONTACTS = 7;

    private Prefs prefs;
    private String screen;
    private LinearLayout root;
    private TextView status, blurStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        screen = getIntent().getStringExtra(SCREEN);
        if (screen == null) screen = MAIN;
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad + dp(24), pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        build();
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
        root.removeAllViews();
        switch (screen) {
            case CORRECTION: buildCorrection(); break;
            case TOOLBAR: buildToolbar(); break;
            case GESTURE: buildGesture(); break;
            case PERSONAL: buildPersonal(); break;
            case TOOL_KEYS: buildKeyList(Prefs.TOOLBAR_KEYS, prefs.toolbarKeys(), "Клавиши панели инструментов",
                    "Отмеченные клавиши показываются на панели; стрелками меняйте порядок."); break;
            case PINNED: buildKeyList(Prefs.PINNED_KEYS, prefs.pinnedKeys(), "Закреплённые клавиши",
                    "Показываются справа от подсказок, когда панель свёрнута."); break;
            case CUSTOM_CODES: buildCustomCodes(); break;
            case PUNCTUATION: buildPunctuation(); break;
            default: buildMain();
        }
    }

    private void rebuild() { build(); }

    // ------------------------------------------------------------------ screens

    private void buildMain() {
        title(getString(R.string.app_name));
        note(getString(R.string.settings_about));
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);
        button(getString(R.string.step_enable), v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        button(getString(R.string.step_choose), v -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showInputMethodPicker();
        });

        header("Разделы");
        link("Исправление текста", "Автоисправление, подсказки, пробелы, эмодзи", () -> open(CORRECTION));
        link("Панель инструментов", "Режим панели, клавиши, закреплённые клавиши", () -> open(TOOLBAR));
        link("Непрерывный ввод", "Набор слов жестами, след, предпросмотр", () -> open(GESTURE));
        note("Проверка правописания в других приложениях (красное подчёркивание): Настройки Android → "
                + "Язык и ввод → Проверка правописания → «Glass Keyboard».");

        header("Внешний вид и отклик");
        toggle(Prefs.BLUR, "Размытие фона", null, true, false);
        slider(Prefs.OPACITY, "Плотность панели", 50, 100, "%", prefs.opacity());
        slider(Prefs.KEY_HEIGHT, "Высота клавиш", 70, 140, "%", prefs.keyHeight());
        slider(Prefs.BOTTOM_OFFSET, "Отступ снизу", 0, 48, " dp", prefs.bottomOffset());
        toggle(Prefs.PREVIEW, "Всплывающая подсказка при нажатии", null, true, false);
        toggle(Prefs.VIBRATE, "Вибрация", null, true, false);
        toggle(Prefs.SOUND, "Звук нажатий", null, false, false);

        blurStatus = new TextView(this);
        blurStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        blurStatus.setPadding(0, dp(12), 0, 0);
        root.addView(blurStatus);
        note(getString(R.string.blur_note));

        EditText test = new EditText(this);
        test.setHint(R.string.test_hint);
        test.setMinLines(3);
        test.setGravity(Gravity.TOP | Gravity.START);
        root.addView(test, matchWrap());
        updateStatus();
    }

    private void buildCorrection() {
        title("Исправление текста");
        link("Личный словарь", "Слова, которые клавиатура запомнила или вы добавили", () -> open(PERSONAL));

        header("Исправления");
        toggle(Prefs.BLOCK_OFFENSIVE, "Блокировать нецензурные слова",
                "Не предлагать слова, которые могут быть сочтены оскорбительными", true, false);
        boolean ac = toggle(Prefs.AUTOCORRECT, "Автоисправление",
                "Автоматическое исправление опечаток при вводе знака препинания или пробела", true, true);
        if (ac) {
            toggle(Prefs.MORE_AUTOCORRECT, "Больше автоисправлений",
                    "Автоматически исправлять даже если поле ввода не требует этого", true, false);
            toggle(Prefs.AUTOCORRECT_SHORTCUTS, "Автоисправление сокращений",
                    "Заменять сокращения на полные слова («щас» → «сейчас», «wont» → «won't»)", true, false);
            choice(Prefs.AUTOCORRECT_THRESHOLD, "Точность автоисправления",
                    new String[]{"Скромная", "Агрессивная", "Очень агрессивная"}, prefs.autocorrectThreshold());
            toggle(Prefs.BACKSPACE_REVERTS, "Нажатие Backspace отменяет автоисправление", null, true, false);
        }
        toggle(Prefs.AUTO_CAPS, "Заглавные автоматически",
                "Автоматически ставить заглавную букву в начале предложений", true, false);

        header("Пробел");
        toggle(Prefs.DOUBLE_SPACE, "Двойной пробел после точки",
                "Двойное нажатие на клавишу пробела вставляет точку и пробел", true, false);
        toggle(Prefs.AUTOSPACE_PUNCTUATION, "Пробел автоматически после знаков препинания",
                "Автоматически добавлять пробел после знаков препинания при вводе нового слова", false, false);
        toggle(Prefs.AUTOSPACE_SUGGESTION, "Пробел автоматически после выбора подсказки", null, true, false);
        if (prefs.gesture()) {
            toggle(Prefs.AUTOSPACE_BEFORE_GESTURE, "Пробел автоматически перед набором слова жестами", null, true, false);
            toggle(Prefs.AUTOSPACE_AFTER_GESTURE, "Пробел автоматически после набора слова жестами", null, true, false);
        }
        toggle(Prefs.SHIFT_REMOVES_AUTOSPACE, "Пробел автоматически отключается при нажатии «Shift»",
                "Нажатие «Shift» убирает автоматически добавленный пробел", false, false);

        header("Подсказки");
        boolean sugg = toggle(Prefs.SUGGESTIONS, "Показывать подсказки по исправлениям",
                "Показывать варианты исправлений во время ввода", true, true);
        if (sugg) {
            boolean always = toggle(Prefs.ALWAYS_SUGGESTIONS, "Всегда показывать подсказки",
                    "Игнорировать запросы других приложений на отключение подсказок (может вызвать проблемы)", false, true);
            if (always) toggle(Prefs.ALWAYS_SUGGESTIONS_EXCEPT_WEB, "Не показывать подсказки в полях веб-форм постоянно",
                    "Поля ввода на веб-страницах часто вызывают проблемы с постоянным показом подсказок", true, false);
            toggle(Prefs.CENTER_SUGGESTION, "Всегда использовать среднюю подсказку",
                    "При нажатии пробела или знака препинания будет вставлена средняя подсказка", false, false);
            toggle(Prefs.FIVE_CHIPS, "Пять подсказок", "Показывать 5 подсказок вместо 3", false, false);
        }
        if (sugg || ac) {
            toggle(Prefs.SUGGEST_EMOJIS, "Предлагать эмодзи", "Использовать словарь эмодзи в обычных подсказках", true, false);
            toggle(Prefs.INLINE_EMOJI, "Поиск эмодзи в строке ввода",
                    "Предлагать эмодзи, если перед словом стоит двоеточие (:сердце)", true, false);
        }
        boolean personal = toggle(Prefs.LEARN, "Персональные подсказки",
                "Обучаться на основе ввода для улучшения подсказок", true, true);
        toggle(Prefs.PREDICTIONS, "Подсказки следующего слова", "Учитывать предыдущее слово при подборе подсказок", true, false);
        boolean punct = toggle(Prefs.SUGGEST_PUNCTUATION, "Подсказки знаков препинания",
                "Показывать знаки препинания, если нет других подсказок", false, true);
        if (punct) link("Набор знаков препинания", prefs.punctuationList(), () -> open(PUNCTUATION));
        toggle(Prefs.SUGGEST_CLIPBOARD, "Предложить содержимое буфера обмена",
                "Показывать недавно скопированный текст как подсказку", true, false);
        Switch contacts = toggleSwitch(Prefs.USE_CONTACTS, "Поиск в именах контактов",
                "Использовать имена из «Контактов» для подсказок и исправлений", false);
        contacts.setOnCheckedChangeListener((b, on) -> {
            prefs.set(Prefs.USE_CONTACTS, on);
            bumpWords();
            if (on && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
        });
        Switch apps = toggleSwitch(Prefs.USE_APPS, "Поиск по названиям приложений",
                "Использовать названия установленных приложений для подсказок и исправлений", false);
        apps.setOnCheckedChangeListener((b, on) -> { prefs.set(Prefs.USE_APPS, on); bumpWords(); });
        if (personal) toggle(Prefs.ADD_TO_SYSTEM_DICT, "Добавить слова в личный словарь",
                "Сохранять выученные слова в личный словарь устройства (Android)", false, false);
        toggle(Prefs.SPELLCHECK_SUGGEST, "Подсказки для слова под курсором",
                "Когда вы ставите курсор в слово, показывать варианты его замены", true, false);
    }

    private void buildToolbar() {
        title("Панель инструментов");
        String mode = prefs.toolbarMode();
        String[] modes = {Prefs.MODE_EXPANDABLE, Prefs.MODE_KEYS, Prefs.MODE_STRIP, Prefs.MODE_HIDDEN};
        String[] labels = {"Расширяемая", "Только клавиши панели", "Только строка подсказок", "Скрыта"};
        int idx = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(mode)) idx = i;
        final int current = idx;
        link("Режим панели инструментов", labels[idx], () -> new AlertDialog.Builder(this)
                .setTitle("Режим панели инструментов")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    prefs.setString(Prefs.TOOLBAR_MODE, modes[which]);
                    d.dismiss();
                    rebuild();
                }).show());
        boolean hidden = Prefs.MODE_HIDDEN.equals(mode);
        if (!hidden) {
            toggle(Prefs.TOOLBAR_SWIPE_DOWN, "Скрывать клавиатуру смахиванием",
                    "Чтобы закрыть клавиатуру, смахните пальцем вниз по панели инструментов", false, false);
            link("Выбрать клавиши панели инструментов", null, () -> open(TOOL_KEYS));
            if (Prefs.MODE_EXPANDABLE.equals(mode) || Prefs.MODE_KEYS.equals(mode))
                link("Выбрать закреплённые клавиши панели инструментов", null, () -> open(PINNED));
            ToolKey persistent;
            try { persistent = ToolKey.valueOf(prefs.persistentKey()); } catch (IllegalArgumentException e) { persistent = ToolKey.VOICE; }
            final ToolKey cur = persistent;
            link("Постоянная кнопка панели", cur.title + " — видна в режиме «Только строка подсказок»", () -> {
                ToolKey[] all = ToolKey.values();
                String[] names = new String[all.length];
                for (int i = 0; i < all.length; i++) names[i] = all[i].title;
                new AlertDialog.Builder(this).setTitle("Постоянная кнопка панели")
                        .setSingleChoiceItems(names, cur.ordinal(), (d, which) -> {
                            prefs.setString(Prefs.PERSISTENT_KEY, all[which].name());
                            d.dismiss();
                            rebuild();
                        }).show();
            });
            link("Настроить коды клавиш панели инструментов", "Своя клавиша Android или текст вместо действия кнопки",
                    () -> open(CUSTOM_CODES));
        }
        note("Пункты FrostKeys «Скрыть панели буфера обмена и эмодзи» и «Адаптивное направление панели» "
                + "здесь не нужны: в этой клавиатуре нет отдельных панелей буфера и эмодзи и нет языков с письмом справа налево.");
    }

    private void buildGesture() {
        title("Непрерывный ввод");
        boolean on = toggle(Prefs.GESTURE, "Включить распознавание жестов при вводе",
                "Набирайте текст, проводя по буквам и не отрывая палец от экрана", true, true);
        if (!on) return;
        boolean trail = toggle(Prefs.GESTURE_TRAIL, "Показывать траекторию жеста", null, true, true);
        boolean floating = toggle(Prefs.GESTURE_FLOATING, "Плавающий предпросмотр",
                "Показывать предложенное слово во время набора жестами", true, true);
        if (floating) toggle(Prefs.GESTURE_FLOATING_DYNAMIC, "Динамический плавающий предпросмотр",
                "Предпросмотр следует за пальцем", true, false);
        toggle(Prefs.GESTURE_SPACE_AWARE, "Непрерывный ввод фраз",
                "Вводите несколько слов, проводя пальцем через пробел", false, false);
        slider(Prefs.GESTURE_COOLDOWN, "Время восстановления быстрой печати", 0, 1000, " мс", prefs.gestureCooldown());
        note("После нажатия клавиши жесты не начинаются в течение этого времени — так быстрая печать не превращается в жест.");
        if (trail || floating) slider(Prefs.GESTURE_TRAIL_FADE, "Время жизни следа жеста", 100, 2000, " мс", prefs.gestureTrailFade());
    }

    // ---- personal dictionary

    private void buildPersonal() {
        title("Личный словарь");
        note("Слова, которые клавиатура выучила, и слова, добавленные вами. Они подсказываются и не исправляются.");
        LinearLayout addRow = new LinearLayout(this);
        EditText input = new EditText(this);
        input.setHint("Новое слово");
        input.setSingleLine(true);
        addRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button add = new Button(this);
        add.setText("Добавить");
        add.setOnClickListener(v -> {
            String w = input.getText().toString().trim();
            if (w.isEmpty() || w.contains(" ")) return;
            String lang = isCyrillic(w) ? "ru" : "en";
            List<String[]> lines = readUserFile(lang);
            lines.add(new String[]{"w", "5", w});
            writeUserFile(lang, lines);
            rebuild();
        });
        addRow.addView(add);
        root.addView(addRow, matchWrap());
        for (String lang : new String[]{"ru", "en"}) {
            List<String[]> lines = readUserFile(lang);
            List<String[]> words = new ArrayList<>();
            for (String[] l : lines) if (l.length == 3 && "w".equals(l[0])) words.add(l);
            words.sort((a, b) -> a[2].compareToIgnoreCase(b[2]));
            header(("ru".equals(lang) ? "Русский" : "English") + " · " + words.size());
            for (String[] w : words) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView t = new TextView(this);
                t.setText(w[2]);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button del = new Button(this);
                del.setText("✕");
                del.setOnClickListener(v -> {
                    List<String[]> all = readUserFile(lang);
                    all.removeIf(l -> l.length >= 3 && "w".equals(l[0]) && l[2].equals(w[2]));
                    writeUserFile(lang, all);
                    rebuild();
                });
                row.addView(del);
                root.addView(row, matchWrap());
            }
        }
        button("Забыть все выученные слова", v -> new AlertDialog.Builder(this)
                .setMessage("Удалить все выученные слова и пары слов?")
                .setPositiveButton("Удалить", (d, w) -> {
                    for (String lang : new String[]{"ru", "en"}) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(getFilesDir(), "user_words_" + lang + ".txt").delete();
                    }
                    bumpWords();
                    rebuild();
                })
                .setNegativeButton("Отмена", null).show());
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
        title(heading);
        note(help);
        List<ToolKey> order = ToolKey.ordered(pref);
        List<ToolKey> enabled = ToolKey.parse(pref, true);
        for (int i = 0; i < order.size(); i++) {
            ToolKey k = order.get(i);
            final int pos = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            cb.setText(k.title);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            cb.setChecked(enabled.contains(k));
            cb.setOnCheckedChangeListener((b, on) -> {
                if (on) enabled.add(k); else enabled.remove(k);
                saveKeyList(key, order, enabled);
            });
            row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button up = new Button(this);
            up.setText("↑");
            up.setEnabled(pos > 0);
            up.setOnClickListener(v -> { java.util.Collections.swap(order, pos, pos - 1); saveKeyList(key, order, enabled); rebuild(); });
            Button down = new Button(this);
            down.setText("↓");
            down.setEnabled(pos < order.size() - 1);
            down.setOnClickListener(v -> { java.util.Collections.swap(order, pos, pos + 1); saveKeyList(key, order, enabled); rebuild(); });
            row.addView(up, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(down, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(row, matchWrap());
        }
    }

    private void saveKeyList(String key, List<ToolKey> order, List<ToolKey> enabled) {
        StringBuilder sb = new StringBuilder();
        for (ToolKey k : order) sb.append(k.name()).append(':').append(enabled.contains(k)).append(';');
        prefs.setString(key, sb.toString());
    }

    private void buildCustomCodes() {
        title("Коды клавиш панели инструментов");
        note("По строке на клавишу: ИМЯ=КОД. КОД — клавиша Android (например HOME, TAB, ESCAPE, F5 "
                + "или число) или любой текст, который будет вставлен. Пустая строка — обычное действие.\n"
                + "Имена клавиш: " + names());
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinLines(6);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setTypeface(Typeface.MONOSPACE);
        e.setText(prefs.getString(Prefs.TOOLBAR_CUSTOM_CODES, ""));
        e.setHint("COPY=TAB\nSETTINGS=Привет!");
        root.addView(e, matchWrap());
        button("Сохранить", v -> { prefs.setString(Prefs.TOOLBAR_CUSTOM_CODES, e.getText().toString()); finish(); });
    }

    private static String names() {
        StringBuilder sb = new StringBuilder();
        for (ToolKey k : ToolKey.values()) { if (sb.length() > 0) sb.append(", "); sb.append(k.name()); }
        return sb.toString();
    }

    private void buildPunctuation() {
        title("Набор знаков препинания");
        note("Знаки через пробел, в порядке показа на полосе подсказок.");
        EditText e = new EditText(this);
        e.setText(prefs.punctuationList());
        root.addView(e, matchWrap());
        button("Сохранить", v -> { prefs.setString(Prefs.PUNCTUATION_LIST, e.getText().toString().trim()); finish(); });
        button("Вернуть по умолчанию", v -> { prefs.setString(Prefs.PUNCTUATION_LIST, Prefs.DEFAULT_PUNCTUATION); finish(); });
    }

    // ------------------------------------------------------------------ status

    private void updateStatus() {
        if (status == null) return;
        boolean enabled = false;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            for (InputMethodInfo info : imm.getEnabledInputMethodList())
                if (getPackageName().equals(info.getPackageName())) enabled = true;
        }
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");
        status.setText(selected ? R.string.status_ready : enabled ? R.string.status_enabled : R.string.status_disabled);
        if (blurStatus != null) {
            String mode = prefs.blurStatus();
            blurStatus.setText(getString(R.string.blur_status, mode.isEmpty() ? getString(R.string.blur_unknown) : mode));
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_CONTACTS && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            prefs.set(Prefs.USE_CONTACTS, false);
            rebuild();
        }
    }

    // ------------------------------------------------------------------ widgets

    private void open(String target) {
        startActivity(new Intent(this, SettingsActivity.class).putExtra(SCREEN, target));
    }

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        root.addView(t);
    }

    private void header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAllCaps(true);
        t.setAlpha(.7f);
        t.setPadding(0, dp(24), 0, dp(4));
        root.addView(t);
    }

    private void note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAlpha(.7f);
        t.setPadding(0, dp(6), 0, dp(10));
        root.addView(t);
    }

    private void button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        root.addView(b, matchWrap());
    }

    private void link(String titleText, String summary, Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));
        box.setClickable(true);
        box.setFocusable(true);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true))
            box.setBackgroundResource(tv.resourceId);
        TextView t = new TextView(this);
        t.setText(titleText + "  ›");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        box.addView(t);
        if (summary != null) {
            TextView s = new TextView(this);
            s.setText(summary);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setAlpha(.7f);
            box.addView(s);
        }
        box.setOnClickListener(v -> action.run());
        root.addView(box, matchWrap());
    }

    /** Switch bound to a boolean pref; returns its value. {@code rebuildOnChange} shows/hides dependents. */
    private boolean toggle(String key, String titleText, String summary, boolean def, boolean rebuildOnChange) {
        Switch s = toggleSwitch(key, titleText, summary, def);
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.set(key, on);
            if (rebuildOnChange) rebuild();
        });
        return prefs.get(key, def);
    }

    private Switch toggleSwitch(String key, String titleText, String summary, boolean def) {
        Switch s = new Switch(this);
        s.setText(summary == null ? titleText : titleText + "\n" + summary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.get(key, def));
        s.setPadding(0, dp(10), 0, dp(10));
        root.addView(s, matchWrap());
        return s;
    }

    private void choice(String key, String titleText, String[] labels, int value) {
        link(titleText, labels[Math.max(0, Math.min(labels.length - 1, value))], () -> new AlertDialog.Builder(this)
                .setTitle(titleText)
                .setSingleChoiceItems(labels, value, (d, which) -> {
                    prefs.setInt(key, which);
                    d.dismiss();
                    rebuild();
                }).show());
    }

    private void slider(String key, String label, int min, int max, String unit, int value) {
        TextView titleView = new TextView(this);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setPadding(0, dp(12), 0, 0);
        titleView.setText(label + ": " + value + unit);
        root.addView(titleView);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                titleView.setText(label + ": " + (progress + min) + unit);
                if (fromUser) prefs.setInt(key, progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        root.addView(bar, matchWrap());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
