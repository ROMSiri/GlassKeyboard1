package dev.glasskeyboard.ime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Word completion, typo correction and next-word prediction on top of {@link WordDictionary}
 * and {@link UserDictionary}. Typo cost uses key distance on the current layout, so "ghbdtn"-style
 * slips to a neighbouring key cost less than random substitutions, and е/ё are treated as equal.
 */
final class Suggest {

    /** Three strip slots (left, centre, right); any may be null. */
    static final class Result {
        final String[] slots = new String[3];
        int literalSlot = -1;      // slot showing the typed text in quotes
        boolean autocorrect;       // centre slot will replace the typed word on space
        boolean typedIsWord;
    }

    private static final class Cand {
        String word; double score, cost; boolean correction;
        Cand(String w, double s, boolean c, double cost) { word = w; score = s; correction = c; this.cost = cost; }
    }

    private static final int CHARS = 0x500; // Latin + Cyrillic
    private final float[] keyX = new float[CHARS], keyY = new float[CHARS];
    private final boolean[] hasPos = new boolean[CHARS];

    /** Key centres (in key units) for proximity-aware substitution cost. */
    void setLayout(Layouts.Layout layout) {
        java.util.Arrays.fill(hasPos, false);
        float y = 0f;
        for (List<Layouts.Key> row : layout.rows) {
            float w = 0f;
            for (Layouts.Key k : row) w += k.weight;
            float x = (layout.maxWeight - w) / 2f;
            for (Layouts.Key k : row) {
                float cx = x + k.weight / 2f;
                if (k.isLetter()) {
                    put(Character.toLowerCase(k.label.charAt(0)), cx, y);
                    // Hidden letters (long-press) sit on their host key.
                    for (int i = 0; i < k.alts.length(); i++) {
                        char a = k.alts.charAt(i);
                        if (Character.isLetter(a) && a < CHARS && !hasPos[a]) put(a, cx, y);
                    }
                }
                x += k.weight;
            }
            y += 1f;
        }
    }

    private void put(char c, float x, float y) {
        if (c >= CHARS) return;
        keyX[c] = x; keyY[c] = y; hasPos[c] = true;
    }

    private static char norm(char c) {
        c = Character.toLowerCase(c);
        return c == 'ё' ? 'е' : c;
    }

    private double subCost(char a, char b) {
        if (a == b) return 0;
        if (a < CHARS && b < CHARS && hasPos[a] && hasPos[b]) {
            float dx = keyX[a] - keyX[b], dy = keyY[a] - keyY[b];
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < .01) return .35;          // same key (hidden letter)
            if (d < 1.6) return .55;          // neighbour
        }
        return 1.0;
    }

    private static final double INDEL = .8;      // missing / extra letter
    private static final double TYPO_PENALTY = 80; // frequency points per unit of edit cost

    private static double maxCost(int len) {
        return len <= 2 ? 0 : len <= 4 ? 1.0 : len <= 7 ? 1.6 : 2.2;
    }

    /**
     * Weighted Damerau-Levenshtein between typed (normalized lowercase) and dictionary word i,
     * or a value above {@code limit} when it is clearly too far.
     */
    private double distance(String typed, WordDictionary dict, int i, double limit, double[][] dp) {
        int n = typed.length(), m = dict.length(i);
        if (m >= dp[0].length || n >= dp.length) return limit + 1;
        for (int b = 0; b <= m; b++) dp[0][b] = b * INDEL;
        for (int a = 1; a <= n; a++) {
            dp[a][0] = a * INDEL;
            double rowMin = dp[a][0];
            char ca = typed.charAt(a - 1);
            for (int b = 1; b <= m; b++) {
                char cb = norm(dict.lowerCharAt(i, b - 1));
                // A missing apostrophe or hyphen ("dont", "кудато") is almost free.
                double ins = cb == '\'' || cb == '-' ? .2 : INDEL;
                double v = Math.min(dp[a - 1][b] + INDEL, dp[a][b - 1] + ins);
                v = Math.min(v, dp[a - 1][b - 1] + subCost(ca, cb));
                if (a > 1 && b > 1 && ca == norm(dict.lowerCharAt(i, b - 2))
                        && typed.charAt(a - 2) == cb) v = Math.min(v, dp[a - 2][b - 2] + .7);
                dp[a][b] = v;
                if (v < rowMin) rowMin = v;
            }
            if (rowMin > limit) return limit + 1;
        }
        return dp[n][m];
    }

    Result suggest(String typed, WordDictionary dict, UserDictionary user, boolean allowAutocorrect) {
        Result r = new Result();
        if (typed.isEmpty()) return r;
        String lower = typed.toLowerCase();
        StringBuilder nb = new StringBuilder(lower.length());
        for (int k = 0; k < lower.length(); k++) nb.append(norm(lower.charAt(k)));
        String key = nb.toString();

        Map<String, Cand> found = new HashMap<>();
        int typedFreq = dict == null ? -1 : dict.frequencyOf(lower);
        int userCount = user == null ? 0 : user.count(lower);
        r.typedIsWord = typedFreq >= 0 || userCount >= 2;

        if (dict != null) {
            // 1. completions (and the exact word itself)
            int from = dict.lowerBound(lower);
            int scanned = 0;
            for (int i = from; i < dict.size() && dict.startsWith(i, lower) && scanned < 4000; i++, scanned++) {
                int f = dict.freq(i);
                if (f == 0) continue;
                int extra = dict.length(i) - lower.length();
                add(found, dict.word(i), extra == 0 ? f + 60 : f - 2.5 * extra, false);
            }
            // ё-variants of the typed word ("еще" -> "ещё") count as exact.
            if (!key.equals(lower) || lower.indexOf('е') >= 0) addYoVariants(found, dict, key);

            // 2. corrections: same first letter (normalized), similar length
            double limit = maxCost(key.length());
            if (limit > 0) {
                double[][] dp = new double[key.length() + 1][48];
                char first = key.charAt(0);
                scanRange(found, dict, key, first, limit, dp);
                if (first == 'е') scanRange(found, dict, key, 'ё', limit, dp);
            }
        }
        // 3. learned words
        if (user != null) {
            for (UserDictionary.Entry e : user.words.values()) {
                String wl = e.word.toLowerCase();
                if (!wl.startsWith(lower)) continue;
                boolean known = dict != null && dict.frequencyOf(wl) >= 0;
                if (!known && e.count < 2) continue;
                double s = 140 + 12 * Math.min(e.count, 8) - 2 * (wl.length() - lower.length());
                if (wl.equals(lower)) s += 60;
                add(found, e.word, s, false);
            }
        }

        List<Cand> list = new ArrayList<>(found.values());
        list.sort((a, b) -> Double.compare(b.score, a.score));
        List<String> others = new ArrayList<>();
        Cand best = null;
        for (Cand c : list) {
            if (c.word.equalsIgnoreCase(typed)) continue;
            if (best == null) best = c;
            else others.add(c.word);
            if (others.size() >= 2) break;
        }

        if (r.typedIsWord) {
            // Valid word: keep it in the middle, alternatives around it.
            // Keep the user's own capitals ("Спс"), otherwise use the dictionary form ("москва" → "Москва").
            boolean typedHasUpper = !typed.equals(lower);
            r.slots[1] = typedHasUpper ? typed : exactForm(dict, user, lower, typed);
            // "i" → "I", "москва" → "Москва": the dictionary only knows the capitalized form.
            r.autocorrect = allowAutocorrect && !typedHasUpper && dict != null
                    && (lower.equals("i") || lower.length() > 1)
                    && !r.slots[1].equals(typed) && (lower.equals("i") && "en".equals(dict.lang) || !dict.containsExact(typed)) && (user == null || user.count(lower) < 2);
            if (best != null) r.slots[0] = matchCase(best.word, typed);
            if (!others.isEmpty()) r.slots[2] = matchCase(others.get(0), typed);
        } else {
            r.slots[0] = typed;
            r.literalSlot = 0;
            if (best != null) {
                r.slots[1] = matchCase(best.word, typed);
                // Short words only for a slip to a neighbouring key or swapped letters.
                double maxAuto = typed.length() <= 4 ? .75 : maxCost(typed.length());
                r.autocorrect = allowAutocorrect && best.correction && typed.length() > 1
                        && best.score > -30 && best.cost <= maxAuto;
            }
            if (!others.isEmpty()) r.slots[2] = matchCase(others.get(0), typed);
        }
        return r;
    }

    private void scanRange(Map<String, Cand> found, WordDictionary dict, String key, char first,
                           double limit, double[][] dp) {
        int from = dict.lowerBound(String.valueOf(first));
        int to = dict.lowerBound(String.valueOf((char) (first + 1)));
        int len = key.length();
        for (int i = from; i < to; i++) {
            int f = dict.freq(i);
            if (f == 0) continue;
            int m = dict.length(i);
            if (Math.abs(m - len) > limit) continue;
            double d = distance(key, dict, i, limit, dp);
            if (d <= limit && d > 0) add(found, dict.word(i), f - TYPO_PENALTY * d, true, d);
            else if (d == 0) add(found, dict.word(i), f + 55, false);
        }
    }

    private void addYoVariants(Map<String, Cand> found, WordDictionary dict, String key) {
        if (key.indexOf('е') < 0) return;
        // try replacing each е with ё (words rarely have more than one)
        for (int k = 0; k < key.length(); k++) {
            if (key.charAt(k) != 'е') continue;
            String v = key.substring(0, k) + 'ё' + key.substring(k + 1);
            int idx = dict.indexOf(v);
            if (idx >= 0 && dict.freq(idx) > 0) add(found, dict.word(idx), dict.freq(idx) + 58, true);
        }
    }

    private static void add(Map<String, Cand> found, String word, double score, boolean correction) {
        add(found, word, score, correction, 0);
    }

    private static void add(Map<String, Cand> found, String word, double score, boolean correction, double cost) {
        String k = word.toLowerCase(Locale.ROOT);
        Cand c = found.get(k);
        if (c == null) found.put(k, new Cand(word, score, correction, cost));
        else if (score > c.score) { c.score = score; c.word = word; c.correction = correction; c.cost = cost; }
    }

    private static String exactForm(WordDictionary dict, UserDictionary user, String lower, String typed) {
        if (dict != null) {
            int i = dict.indexOf(lower);
            if (i >= 0) return dict.word(i);
        }
        return typed;
    }

    /** Typed "Привет" → capitalize; "ПРИВЕТ" → upper; dictionary proper nouns keep their capital. */
    static String matchCase(String word, String typed) {
        if (word == null || typed.isEmpty()) return word;
        boolean firstUpper = Character.isUpperCase(typed.charAt(0));
        boolean allUpper = typed.length() > 1 && typed.equals(typed.toUpperCase()) && !typed.equals(typed.toLowerCase());
        if (allUpper) return word.toUpperCase();
        if (firstUpper) return word.substring(0, 1).toUpperCase() + word.substring(1);
        return word;
    }

    /** Next-word predictions after {@code prev}: learned pairs first, then the dictionary's. */
    String[] predict(String prev, WordDictionary dict, UserDictionary user) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (user != null) for (UserDictionary.Entry e : user.next(prev)) {
            if (out.size() >= 3) break;
            if (seen.add(e.word.toLowerCase())) out.add(e.word);
        }
        if (dict != null) {
            String[] d = dict.next(prev);
            if (d != null) for (String w : d) {
                if (out.size() >= 3) break;
                if (seen.add(w.toLowerCase())) out.add(w);
            }
        }
        if (out.isEmpty()) return null;
        String[] slots = new String[3];
        // most likely in the centre
        slots[1] = out.get(0);
        if (out.size() > 1) slots[0] = out.get(1);
        if (out.size() > 2) slots[2] = out.get(2);
        return slots;
    }
}
