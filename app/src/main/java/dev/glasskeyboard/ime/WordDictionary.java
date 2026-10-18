package dev.glasskeyboard.ime;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Read-only word list built from the AOSP dictionaries (see tools/dicttool).
 * Words are stored back to back in one String, sorted by their lowercase form, so the whole
 * Russian list (220k words) costs a few MB and lookups are binary searches.
 */
final class WordDictionary {
    final String lang;
    private final String data;      // all words, concatenated
    private final int[] start;       // start of word i in data; start[n] = data.length()
    private final byte[] freq;       // 0..255 stored as signed byte
    private final Map<String, String[]> bigrams;
    private final java.util.BitSet offensive;
    /** lowercase source → {target, "15" for always-autocorrect} */
    final Map<String, String[]> shortcuts = new HashMap<>();

    private WordDictionary(String lang, String data, int[] start, byte[] freq, java.util.BitSet offensive,
                           Map<String, String[]> bigrams) {
        this.lang = lang; this.data = data; this.start = start; this.freq = freq;
        this.offensive = offensive; this.bigrams = bigrams;
    }

    boolean isOffensive(int i) { return offensive.get(i); }

    int size() { return freq.length; }
    String word(int i) { return data.substring(start[i], start[i + 1]); }
    int freq(int i) { return freq[i] & 0xff; }
    int length(int i) { return start[i + 1] - start[i]; }
    char lowerCharAt(int i, int k) { return Character.toLowerCase(data.charAt(start[i] + k)); }

    /** Next-word predictions from the dictionary, or null. */
    String[] next(String word) {
        if (bigrams == null || word == null) return null;
        String[] r = bigrams.get(word);
        return r != null ? r : bigrams.get(word.toLowerCase());
    }

    /** Index of the first word whose lowercase form is >= {@code lowerKey}. */
    int lowerBound(String lowerKey) {
        int lo = 0, hi = size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(mid, lowerKey) < 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Compares word i (lowercased) with key. */
    int compare(int i, String key) {
        int len = length(i), n = Math.min(len, key.length());
        for (int k = 0; k < n; k++) {
            int d = lowerCharAt(i, k) - key.charAt(k);
            if (d != 0) return d;
        }
        return len - key.length();
    }

    boolean startsWith(int i, String lowerPrefix) {
        if (length(i) < lowerPrefix.length()) return false;
        for (int k = 0; k < lowerPrefix.length(); k++)
            if (lowerCharAt(i, k) != lowerPrefix.charAt(k)) return false;
        return true;
    }

    /** Best frequency among entries equal to {@code lower} ignoring case, or -1 if absent. */
    int frequencyOf(String lower) {
        int best = -1;
        for (int i = lowerBound(lower); i < size() && compare(i, lower) == 0; i++) best = Math.max(best, freq(i));
        return best;
    }

    boolean containsExact(String word) {
        String lower = word.toLowerCase();
        for (int i = lowerBound(lower); i < size() && compare(i, lower) == 0; i++)
            if (word.equals(word(i))) return true;
        return false;
    }

    /** Index of the most frequent entry equal to {@code lower} ignoring case, or -1. */
    int indexOf(String lower) {
        int best = -1;
        for (int i = lowerBound(lower); i < size() && compare(i, lower) == 0; i++)
            if (best < 0 || freq(i) > freq(best)) best = i;
        return best;
    }

    static WordDictionary load(Context context, String lang) throws Exception {
        InputStream bigramStream = null;
        try { bigramStream = context.getAssets().open("bigrams_" + lang + ".dat"); }
        catch (java.io.IOException noBigrams) { /* none for this language */ }
        WordDictionary d = load(lang, context.getAssets().open("dict_" + lang + ".dat"), bigramStream);
        try { d.loadShortcuts(context.getAssets().open("shortcuts_" + lang + ".dat")); }
        catch (java.io.IOException none) { /* optional */ }
        return d;
    }

    void loadShortcuts(InputStream stream) throws java.io.IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(stream), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length == 3) shortcuts.put(p[0].toLowerCase(), new String[]{p[1], p[2]});
            }
        }
    }

    /** Loads from gzip streams (closes them). */
    static WordDictionary load(String lang, InputStream dictStream, InputStream bigramStream) throws Exception {
        StringBuilder sb = new StringBuilder(1 << 21);
        int[] starts = new int[1 << 16];
        byte[] freqs = new byte[1 << 16];
        java.util.BitSet offensive = new java.util.BitSet();
        int n = 0;
        try (InputStream raw = dictStream;
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     new GZIPInputStream(raw, 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = in.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                if (n + 1 >= starts.length) {
                    starts = java.util.Arrays.copyOf(starts, starts.length * 2);
                    freqs = java.util.Arrays.copyOf(freqs, freqs.length * 2);
                }
                int f = 0, k0 = 0;
                if (line.charAt(0) == '!') { offensive.set(n); k0 = 1; }
                for (int k = k0; k < tab; k++) f = f * 10 + (line.charAt(k) - '0');
                freqs[n] = (byte) f;
                starts[n] = sb.length();
                sb.append(line, tab + 1, line.length());
                n++;
            }
        }
        starts[n] = sb.length();
        Map<String, String[]> bigrams = null;
        if (bigramStream != null) try (InputStream raw = bigramStream;
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     new GZIPInputStream(raw, 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            bigrams = new HashMap<>(1 << 18);
            String line;
            while ((line = in.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) bigrams.put(line.substring(0, tab), line.substring(tab + 1).split(" "));
            }
        }
        return new WordDictionary(lang, sb.toString(), java.util.Arrays.copyOf(starts, n + 1),
                java.util.Arrays.copyOf(freqs, n), offensive, bigrams);
    }
}
