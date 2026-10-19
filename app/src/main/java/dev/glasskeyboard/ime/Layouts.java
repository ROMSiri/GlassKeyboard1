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

    /** Letters: no comma key — comma is the first long-press choice on the period; wide Enter. */
    private static Key[] letterBottomRow() {
        return new Key[]{
                fn("?123", SYMBOLS, 1.45f),
                fn("", LANG, 1.0f),
                new Key("", SPACE, "", 4.55f, Kind.SPACE),
                ch(".", ",?!…:;"),
                new Key("", ENTER, "", 2.0f, Kind.ACCENT),
        };
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
     * Same grid as QWERTY: 10 / 9 / 7 letters, Shift and Delete 1.5 wide, middle row centred.
     * The rarest letter(s) of each ЙЦУКЕН row go under a neighbour (long-press, hint in the corner):
     *   щ → ш,  ф → в,  э → ж (and е),  ё → е,  ч → с,  ю → б,  ъ → ь
     */
    static Layout russian() {
        return new Layout("ru")
                .row(ch("й", "1"), ch("ц", "2"), ch("у", "3"), ch("к", "4"), ch("е", "ёэ5"),
                        ch("н", "6"), ch("г", "7"), ch("ш", "щ8"), ch("з", "9"), ch("х", "0"))
                .row(ch("ы"), ch("в", "ф"), ch("а"), ch("п"), ch("р"), ch("о"), ch("л"),
                        ch("д"), ch("ж", "э"))
                .row(fn("", SHIFT, 1.5f), ch("я"), ch("с", "ч"), ch("м"), ch("и"), ch("т"),
                        ch("ь", "ъ"), ch("б", "ю"), fn("", DELETE, 1.5f))
                .row(letterBottomRow());
    }

    static Layout english() {
        return new Layout("en")
                .row(chars("qwertyuiop", "1234567890"))
                .row(chars("asdfghjkl", null))
                .row(concat(prepend(fn("", SHIFT, 1.5f), chars("zxcvbnm", null)), fn("", DELETE, 1.5f)))
                .row(letterBottomRow());
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

    /** Big digits; opened from the toolbar. */
    static Layout numpad() {
        return new Layout("num")
                .row(ch("1"), ch("2"), ch("3"), ch("-", "+−±"))
                .row(ch("4"), ch("5"), ch("6"), ch(",", ";:"))
                .row(ch("7"), ch("8"), ch("9"), fn("", DELETE, 1f))
                .row(fn("АБВ", ALPHA, 1f), ch("0"), ch(".", "/%"), new Key("", ENTER, "", 1f, Kind.ACCENT));
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
