package dev.glasskeyboard.ime;

import java.util.ArrayList;
import java.util.List;

/** Key definitions and the ЙЦУКЕН / QWERTY / symbol layouts. */
final class Layouts {
    static final int SHIFT = -1;
    static final int SYMBOLS = -2;
    static final int ALPHA = -3;
    static final int LANG = -4;
    static final int DELETE = -5;
    static final int SYMBOLS_MORE = -6;
    static final int ENTER = -10;
    static final int SPACE = ' ';

    enum Kind { CHAR, FUNCTION, ACCENT, SPACE }

    static final class Key {
        final String label;     // lowercase label for letters
        final int code;         // char code, or one of the negative codes above
        final String alts;      // long-press alternatives, may be empty
        final float weight;
        final Kind kind;

        Key(String label, int code, String alts, float weight, Kind kind) {
            this.label = label; this.code = code; this.alts = alts == null ? "" : alts;
            this.weight = weight; this.kind = kind;
        }

        boolean isLetter() { return kind == Kind.CHAR && label.length() == 1 && Character.isLetter(label.charAt(0)); }
    }

    static final class Layout {
        final String id;
        final List<List<Key>> rows = new ArrayList<>();
        float maxWeight;
        Layout(String id) { this.id = id; }

        Layout row(Key... keys) {
            List<Key> row = new ArrayList<>();
            float w = 0f;
            for (Key k : keys) { row.add(k); w += k.weight; }
            rows.add(row);
            maxWeight = Math.max(maxWeight, w);
            return this;
        }
    }

    static Key ch(String s) { return new Key(s, s.codePointAt(0), "", 1f, Kind.CHAR); }
    static Key ch(String s, String alts) { return new Key(s, s.codePointAt(0), alts, 1f, Kind.CHAR); }
    static Key fn(String label, int code, float weight) { return new Key(label, code, "", weight, Kind.FUNCTION); }

    private static Key[] chars(String letters, String digits) {
        Key[] keys = new Key[letters.length()];
        for (int i = 0; i < letters.length(); i++) {
            String alt = digits != null && i < digits.length() ? String.valueOf(digits.charAt(i)) : "";
            keys[i] = ch(String.valueOf(letters.charAt(i)), alt);
        }
        return keys;
    }

    private static Key[] concat(Key[] a, Key... b) {
        Key[] out = new Key[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static Key[] prepend(Key first, Key[] rest) {
        Key[] out = new Key[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private static Key[] bottomRow(String toggleLabel, int toggleCode, float scale) {
        return new Key[]{
                fn(toggleLabel, toggleCode, 1.45f * scale),
                fn("", LANG, 1.0f * scale),
                ch(",", ",;:"),
                new Key("", SPACE, "", 4.2f * scale + (scale - 1f) * 2f, Kind.SPACE),
                ch(".", ".?!…"),
                new Key("", ENTER, "", 1.35f * scale, Kind.ACCENT),
        };
    }

    static Layout russian() {
        Key[] r1 = chars("йцукенгшщзх", "1234 67890");
        r1[4] = ch("е", "ё5");
        r1[10] = ch("х", "ъ");
        Key[] r3 = chars("ячсмитьбю", null);
        r3[6] = ch("ь", "ъ");
        return new Layout("ru")
                .row(r1)
                .row(chars("фывапролджэ", null))
                .row(concat(prepend(fn("", SHIFT, 1.25f), r3), fn("", DELETE, 1.25f)))
                .row(bottomRow("?123", SYMBOLS, 1.15f));
    }

    static Layout english() {
        return new Layout("en")
                .row(chars("qwertyuiop", "1234567890"))
                .row(chars("asdfghjkl", null))
                .row(concat(prepend(fn("", SHIFT, 1.5f), chars("zxcvbnm", null)), fn("", DELETE, 1.5f)))
                .row(bottomRow("?123", SYMBOLS, 1f));
    }

    static Layout symbols() {
        return new Layout("sym")
                .row(chars("1234567890", "¹²³⁴⁵⁶⁷⁸⁹⁰"))
                .row(new Key[]{ch("@"), ch("#"), ch("₽", "$€£¥"), ch("_"), ch("&"), ch("-", "–—"),
                        ch("+", "±"), ch("("), ch(")"), ch("/", "\\")})
                .row(concat(prepend(fn("=\\<", SYMBOLS_MORE, 1.5f),
                        new Key[]{ch("*"), ch("\"", "«»„“"), ch("'", "‘’"), ch(":"), ch(";"),
                                ch("!", "¡"), ch("?", "¿")}), fn("", DELETE, 1.5f)))
                .row(bottomRow("АБВ", ALPHA, 1f));
    }

    static Layout symbolsMore() {
        return new Layout("sym2")
                .row(chars("~`|•√π÷×¶∆", null))
                .row(chars("£€$¥^°={}\\", null))
                .row(concat(prepend(fn("?123", SYMBOLS, 1.5f),
                        new Key[]{ch("%", "‰"), ch("©"), ch("®"), ch("™"), ch("✓"), ch("["), ch("]")}),
                        fn("", DELETE, 1.5f)))
                .row(bottomRow("АБВ", ALPHA, 1f));
    }

    private Layouts() { }
}
