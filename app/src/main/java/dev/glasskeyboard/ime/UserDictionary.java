package dev.glasskeyboard.ime;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Words and word pairs learned from what the user types. Stored only on the device. */
final class UserDictionary {
    static final class Entry {
        String word;  // display form
        int count;
        Entry(String word, int count) { this.word = word; this.count = count; }
    }

    private static final int MAX_WORDS = 6000, MAX_PAIRS = 12000;

    final String lang;
    private final File file;
    final Map<String, Entry> words = new HashMap<>();              // lowercase -> entry
    private final Map<String, Map<String, Entry>> pairs = new HashMap<>(); // prev lowercase -> next
    private int pairCount;
    private boolean dirty;

    private UserDictionary(String lang, File file) { this.lang = lang; this.file = file; }

    static UserDictionary load(Context context, String lang) {
        UserDictionary d = new UserDictionary(lang, new File(context.getFilesDir(), "user_words_" + lang + ".txt"));
        if (!d.file.exists()) return d;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(d.file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.split("\t");
                try {
                    if (p.length == 3 && p[0].equals("w")) {
                        d.words.put(p[2].toLowerCase(), new Entry(p[2], Integer.parseInt(p[1])));
                    } else if (p.length == 4 && p[0].equals("b")) {
                        d.pairs.computeIfAbsent(p[2], k -> new HashMap<>())
                                .put(p[3].toLowerCase(), new Entry(p[3], Integer.parseInt(p[1])));
                        d.pairCount++;
                    }
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) {
            // a broken file only loses learned words
        }
        return d;
    }

    int count(String lower) {
        Entry e = words.get(lower);
        return e == null ? 0 : e.count;
    }

    void learn(String prev, String word) {
        if (word == null || word.isEmpty()) return;
        String lower = word.toLowerCase();
        Entry e = words.get(lower);
        if (e == null) {
            if (words.size() >= MAX_WORDS) trimWords();
            words.put(lower, new Entry(word, 1));
        } else {
            e.count++;
            // Prefer the casing typed most recently unless it is a sentence-start capital.
            if (!word.equals(e.word) && !Character.isUpperCase(word.charAt(0))) e.word = word;
        }
        if (prev != null && !prev.isEmpty()) {
            Map<String, Entry> next = pairs.computeIfAbsent(prev.toLowerCase(), k -> new HashMap<>());
            Entry p = next.get(lower);
            if (p == null) {
                if (pairCount >= MAX_PAIRS) trimPairs();
                next.put(lower, new Entry(word, 1));
                pairCount++;
            } else p.count++;
        }
        dirty = true;
    }

    void forget(String word) {
        if (word == null) return;
        if (words.remove(word.toLowerCase()) != null) dirty = true;
    }

    /** Learned next words after {@code prev}, most frequent first. */
    List<Entry> next(String prev) {
        List<Entry> out = new ArrayList<>();
        if (prev == null) return out;
        Map<String, Entry> m = pairs.get(prev.toLowerCase());
        if (m == null) return out;
        out.addAll(m.values());
        out.sort((a, b) -> b.count - a.count);
        return out;
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Entry e : words.values()) w.write("w\t" + e.count + "\t" + e.word + "\n");
            for (Map.Entry<String, Map<String, Entry>> m : pairs.entrySet())
                for (Entry e : m.getValue().values())
                    w.write("b\t" + e.count + "\t" + m.getKey() + "\t" + e.word + "\n");
        } catch (Exception error) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }

    void clear() {
        words.clear();
        pairs.clear();
        pairCount = 0;
        dirty = true;
        save();
    }

    private void trimWords() {
        // Drop the rarest third.
        List<Map.Entry<String, Entry>> all = new ArrayList<>(words.entrySet());
        all.sort((a, b) -> a.getValue().count - b.getValue().count);
        for (int i = 0; i < all.size() / 3; i++) words.remove(all.get(i).getKey());
    }

    private void trimPairs() {
        pairs.values().forEach(m -> m.values().removeIf(e -> e.count <= 1));
        pairs.values().removeIf(Map::isEmpty);
        pairCount = 0;
        for (Map<String, Entry> m : pairs.values()) pairCount += m.size();
        if (pairCount >= MAX_PAIRS) { pairs.clear(); pairCount = 0; }
    }
}
