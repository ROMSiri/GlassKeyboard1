package dev.glasskeyboard.ime;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Emoji by keyword (Unicode CLDR annotations, built by tools/emoji in CI). */
final class EmojiDictionary {
    private final String[] keys;     // sorted, lowercase, ё → е
    private final String[][] emojis;

    private EmojiDictionary(String[] keys, String[][] emojis) { this.keys = keys; this.emojis = emojis; }

    static EmojiDictionary load(Context context, String lang) {
        try { return load(context.getAssets().open("emoji_" + lang + ".dat")); }
        catch (Exception missing) { return null; }
    }

    static EmojiDictionary load(InputStream stream) throws Exception {
        List<String> k = new ArrayList<>();
        List<String[]> e = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(stream), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                k.add(line.substring(0, tab));
                e.add(line.substring(tab + 1).split(" "));
            }
        }
        return new EmojiDictionary(k.toArray(new String[0]), e.toArray(new String[0][]));
    }

    private static String key(String word) { return word.toLowerCase().replace('ё', 'е'); }

    /** Emojis for exactly this word. */
    String[] exact(String word) {
        int i = Arrays.binarySearch(keys, key(word));
        return i >= 0 ? emojis[i] : null;
    }

    /** Emojis whose keyword starts with {@code prefix} (inline ":query" search). */
    List<String> search(String prefix, int max) {
        Set<String> out = new LinkedHashSet<>();
        String p = key(prefix);
        String[] exact = exact(p);
        if (exact != null) out.addAll(Arrays.asList(exact));
        int i = Arrays.binarySearch(keys, p);
        if (i < 0) i = -i - 1;
        for (; i < keys.length && keys[i].startsWith(p) && out.size() < max; i++)
            for (String e : emojis[i]) { if (out.size() >= max) break; out.add(e); }
        return new ArrayList<>(out);
    }
}
