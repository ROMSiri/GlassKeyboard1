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

    /**
     * 26 letters like QWERTY (9 / 9 / 8). The seven rarest letters live under a neighbour:
     * long-press shows them, the small hint in the key corner says which.
     *   ё э → е,  ю → у,  щ → ш,  ф → в,  ц → ч,  ъ → ь
     */
    static Layout russian() {
        float wide = 10f / 9f; // rows of nine keys fill the full width
        return new Layout("ru")
                .row(w(wide, ch("й", "1"), ch("у", "ю2"), ch("к", "3"), ch("е", "ёэ4"), ch("н", "5"),
                        ch("г", "6"), ch("ш", "щ7"), ch("з", "8"), ch("х", "90")))
                .row(w(wide, ch("ы"), ch("в", "ф"), ch("а"), ch("п"), ch("р"), ch("о"), ch("л"),
                        ch("д"), ch("ж")))
                .row(fn("", SHIFT, 1f), ch("я"), ch("ч", "ц"), ch("с"), ch("м"), ch("и"), ch("т"),
                        ch("ь", "ъ"), ch("б"), fn("", DELETE, 1f))
                .row(bottomRow("?123", SYMBOLS, 1f));
    }

    private static Key[] w(float weight, Key... keys) {
        Key[] out = new Key[keys.length];
        for (int i = 0; i < keys.length; i++) {
            Key k = keys[i];
            out[i] = new Key(k.label, k.code, k.alts, weight, k.kind);
        }
        return out;
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
