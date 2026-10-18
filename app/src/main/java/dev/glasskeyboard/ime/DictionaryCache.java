package dev.glasskeyboard.ime;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** One copy of each word list per process, shared by the keyboard and the spell checker. */
final class DictionaryCache {
    private static final Map<String, WordDictionary> cache = new HashMap<>();

    /** Loads on first use (blocking; call off the main thread). */
    static synchronized WordDictionary get(Context context, String lang) {
        if (cache.containsKey(lang)) return cache.get(lang);
        WordDictionary d = null;
        try { d = WordDictionary.load(context.getApplicationContext(), lang); }
        catch (Throwable error) { Log.w("DictionaryCache", "load " + lang, error); }
        cache.put(lang, d);
        return d;
    }

    private DictionaryCache() { }
}
