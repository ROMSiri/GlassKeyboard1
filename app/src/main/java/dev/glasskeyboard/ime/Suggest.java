package dev.glasskeyboard.ime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Word completion, typo correction and next-word prediction on top of {@link WordDictionary},
 * {@link UserDictionary} and {@link ExtraWords}. Typo cost uses key distance on the current
 * layout, so slips to a neighbouring key cost less than random substitutions; е/ё are equal.
 */
final class Suggest {

    /** User settings that change suggestions. */
    static final class Options {
        boolean autocorrect = true;
        int threshold = 0;          // 0 modest, 1 aggressive, 2 very aggressive
        boolean blockOffensive = true;
        boolean shortcuts = true;
        int chips = 3;              // 3 or 5
        boolean emojis = true;
    }

    /** Strip slots, left to right; any may be null. */
    static final class Result {
        final String[] slots;
        int literalSlot = -1;      // slot showing the typed text in quotes
        boolean autocorrect;       // centre slot replaces the typed word on space
        boolean typedIsWord;
        Result(int n) { slots = new String[n]; }
        int center() { return slots.length / 2; }
        String centerWord() { return slots[center()]; }
    }

    private static final class Cand {
        String word; double score, cost; boolean correction;
        Cand(String w, double s, boolean c, double cost) { word = w; score = s; correction = c; this.cost = cost; }
    }

    private static final int CHARS = 0x500; // Latin + Cyrillic
    private final float[] keyX = new float[CHARS], keyY = new float[CHARS];
    private final boolean[] hasPos = new boolean[CHARS];
    private static final double INDEL = .8;        // missing / extra letter
    private static final double TYPO_PENALTY = 80; // frequency points per unit of edit cost

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

    static char norm(char c) {
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

    private static double maxCost(int len) {
        return len <= 2 ? 0 : len <= 4 ? 1.0 : len <= 7 ? 1.6 : 2.2;
    }

    /** Weighted Damerau-Levenshtein, or a value above {@code limit} when clearly too far. */
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
                // A missing apostrophe or hyphen ("dont", "ктото") is almost free.
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

    static int freqFor(WordDictionary dict, int i, Options o) {
        if (dict.isOffensive(i)) return o.blockOffensive ? 0 : Math.max(dict.freq(i), 60);
        return dict.freq(i);
    }

    Result suggest(String typed, WordDictionary dict, UserDictionary user, ExtraWords extra,
                   EmojiDictionary emoji, Options o) {
        Result r = new Result(o.chips);
        if (typed.isEmpty()) return r;
        String lower = typed.toLowerCase();
        StringBuilder nb = new StringBuilder(lower.length());
        for (int k = 0; k < lower.length(); k++) nb.append(norm(lower.charAt(k)));
        String key = nb.toString();

        Map<String, Cand> found = new HashMap<>();
        int typedFreq = dict == null ? -1 : dict.frequencyOf(lower);
        int userCount = user == null ? 0 : user.count(lower);
        boolean extraKnows = extra != null && extra.words.containsKey(lower);
        r.typedIsWord = typedFreq >= 0 || userCount >= 2 || extraKnows;

        if (dict != null) {
            // 1. completions (and the exact word itself)
            int from = dict.lowerBound(lower);
            int scanned = 0;
            for (int i = from; i < dict.size() && dict.startsWith(i, lower) && scanned < 4000; i++, scanned++) {
                int f = freqFor(dict, i, o);
                if (f == 0) continue;
                int extraLen = dict.length(i) - lower.length();
                add(found, dict.word(i), extraLen == 0 ? f + 60 : f - 2.5 * extraLen, false, 0);
            }
            // ё-variants of the typed word ("еще" -> "ещё") count as exact.
            addYoVariants(found, dict, key, o);

            // 2. corrections: same first letter (normalized), similar length
            double limit = maxCost(key.length());
            if (limit > 0) {
                double[][] dp = new double[key.length() + 1][48];
                char first = key.charAt(0);
                scanRange(found, dict, key, first, limit, dp, o);
                if (first == 'е') scanRange(found, dict, key, 'ё', limit, dp, o);
            }
            // 3. shortcuts: "wont" → "won't", "щас" → "сейчас"
            if (o.shortcuts) {
                String[] sc = dict.shortcuts.get(lower);
                if (sc != null) {
                    boolean always = "15".equals(sc[1]);
                    add(found, sc[0], always ? 300 : 150, always, 0);
                    if (always) r.typedIsWord = false;
                }
            }
        }
        // 4. learned words, contacts, apps, system dictionary
        if (user != null) {
            for (UserDictionary.Entry e : user.words.values()) {
                String wl = e.word.toLowerCase();
                if (!wl.startsWith(lower)) continue;
                boolean known = dict != null && dict.frequencyOf(wl) >= 0;
                if (!known && e.count < 2) continue;
                double s = 140 + 12 * Math.min(e.count, 8) - 2 * (wl.length() - lower.length());
                if (wl.equals(lower)) s += 60;
                add(found, e.word, s, false, 0);
            }
        }
        if (extra != null && lower.length() >= 2) {
            for (Map.Entry<String, String> e : extra.words.entrySet()) {
                if (!e.getKey().startsWith(lower)) continue;
                double s = 150 - 2 * (e.getKey().length() - lower.length());
                if (e.getKey().equals(lower)) s += 60;
                add(found, e.getValue(), s, false, 0);
            }
        }

        List<Cand> list = new ArrayList<>(found.values());
        list.sort((a, b) -> Double.compare(b.score, a.score));
        List<Cand> ranked = new ArrayList<>();
        for (Cand c : list) {
            // a valid word is shown on its own; otherwise only its exact spelling is redundant ("i" → "I" stays)
            if (r.typedIsWord ? c.word.equalsIgnoreCase(typed) : c.word.equals(typed)) continue;
            ranked.add(c);
            if (ranked.size() >= o.chips) break;
        }
        String emojiFor = null;
        if (o.emojis && emoji != null) {
            String[] e = emoji.exact(typed);
            if (e != null) emojiFor = e[0];
        }

        List<String> order = new ArrayList<>(); // by rank, centre first
        if (r.typedIsWord) {
            // Keep the user's own capitals ("Спс"), otherwise use the dictionary form ("москва" → "Москва").
            boolean typedHasUpper = !typed.equals(lower);
            String exact = typedHasUpper ? typed : exactForm(dict, lower, typed);
            order.add(exact);
            for (Cand c : ranked) order.add(matchCase(c.word, typed));
            // "i" → "I", "москва" → "Москва": the dictionary only knows the capitalized form.
            r.autocorrect = o.autocorrect && !typedHasUpper && dict != null && !exact.equals(typed)
                    && (lower.equals("i") && "en".equals(dict.lang) || !dict.containsExact(typed))
                    && (user == null || user.count(lower) < 2);
            fill(r, order, -1, emojiFor);
        } else {
            Cand best = ranked.isEmpty() ? null : ranked.get(0);
            for (Cand c : ranked) order.add(matchCase(c.word, typed));
            boolean shortcut = best != null && best.correction && best.cost == 0 && best.score >= 300;
            if (best != null && o.autocorrect && (typed.length() > 1 || shortcut)) {
                if (o.threshold >= 2) {
                    r.autocorrect = true; // very aggressive: always take the top suggestion
                } else {
                    double maxAuto = typed.length() <= 4 ? (o.threshold == 0 ? .75 : 1.0) : maxCost(typed.length());
                    double minScore = o.threshold == 0 ? -30 : -70;
                    r.autocorrect = best.correction && best.score > minScore && best.cost <= maxAuto;
                }
            }
            fill(r, order, 0, emojiFor);
            r.slots[0] = typed;
            r.literalSlot = 0;
        }
        return r;
    }

    /** Places ranked words: centre, then left/right alternately; slot {@code reserved} kept free. */
    private static void fill(Result r, List<String> order, int reserved, String emoji) {
        int n = r.slots.length, c = n / 2;
        List<Integer> positions = new ArrayList<>();
        positions.add(c);
        for (int d = 1; d <= c; d++) { positions.add(c - d); positions.add(c + d); }
        positions.remove(Integer.valueOf(reserved));
        if (emoji != null) positions.remove(Integer.valueOf(n - 1));
        int k = 0;
        for (int pos : positions) {
            if (k >= order.size()) break;
            r.slots[pos] = order.get(k++);
        }
        if (emoji != null) r.slots[n - 1] = emoji;
    }

    private void scanRange(Map<String, Cand> found, WordDictionary dict, String key, char first,
                           double limit, double[][] dp, Options o) {
        int from = dict.lowerBound(String.valueOf(first));
        int to = dict.lowerBound(String.valueOf((char) (first + 1)));
        int len = key.length();
        for (int i = from; i < to; i++) {
            int f = freqFor(dict, i, o);
            if (f == 0) continue;
            int m = dict.length(i);
            if (Math.abs(m - len) > limit) continue;
            double d = distance(key, dict, i, limit, dp);
            if (d <= limit && d > 0) add(found, dict.word(i), f - TYPO_PENALTY * d, true, d);
            else if (d == 0) add(found, dict.word(i), f + 55, false, 0);
        }
    }

    private void addYoVariants(Map<String, Cand> found, WordDictionary dict, String key, Options o) {
        if (key.indexOf('е') < 0) return;
        for (int k = 0; k < key.length(); k++) {
            if (key.charAt(k) != 'е') continue;
            String v = key.substring(0, k) + 'ё' + key.substring(k + 1);
            int idx = dict.indexOf(v);
            if (idx >= 0 && freqFor(dict, idx, o) > 0) add(found, dict.word(idx), freqFor(dict, idx, o) + 58, true, 0);
        }
    }

    private static void add(Map<String, Cand> found, String word, double score, boolean correction, double cost) {
        String k = word.toLowerCase(Locale.ROOT);
        Cand c = found.get(k);
        if (c == null) found.put(k, new Cand(word, score, correction, cost));
        else if (score > c.score) { c.score = score; c.word = word; c.correction = correction; c.cost = cost; }
    }

    private static String exactForm(WordDictionary dict, String lower, String typed) {
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
    String[] predict(String prev, WordDictionary dict, UserDictionary user, int chips) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (user != null) for (UserDictionary.Entry e : user.next(prev)) {
            if (out.size() >= chips) break;
            if (seen.add(e.word.toLowerCase())) out.add(e.word);
        }
        if (dict != null) {
            String[] d = dict.next(prev);
            if (d != null) for (String w : d) {
                if (out.size() >= chips) break;
                if (seen.add(w.toLowerCase())) out.add(w);
            }
        }
        if (out.isEmpty()) return null;
        Result r = new Result(chips);
        fill(r, out, -1, null);
        return r.slots;
    }

    /** Ranked words into strip slots (centre first). */
    static String[] toSlots(List<String> ranked, int chips) {
        if (ranked == null || ranked.isEmpty()) return null;
        Result r = new Result(chips);
        fill(r, ranked, -1, null);
        return r.slots;
    }

    /** Inline emoji search for ":query". */
    static String[] emojiSlots(EmojiDictionary emoji, String query, int chips) {
        if (emoji == null || query.isEmpty()) return null;
        return toSlots(emoji.search(query, chips), chips);
    }
}
