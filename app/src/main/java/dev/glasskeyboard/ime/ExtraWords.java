package dev.glasskeyboard.ime;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.UserDictionary;

import java.util.HashMap;
import java.util.Map;

/**
 * Extra words for suggestions: contact names, app names and the system personal dictionary
 * (Settings → Languages → Personal dictionary). Read on a background thread.
 */
final class ExtraWords {
    /** lowercase → display form */
    final Map<String, String> words = new HashMap<>();

    static ExtraWords load(Context context, boolean contacts, boolean apps) {
        ExtraWords w = new ExtraWords();
        w.readSystemDictionary(context);
        if (contacts) w.readContacts(context);
        if (apps) w.readApps(context);
        return w;
    }

    private void add(String text) {
        if (text == null) return;
        for (String part : text.split("[\\s,.;:()\\[\\]/\"«»]+")) {
            if (part.length() < 2 || !Character.isLetter(part.codePointAt(0))) continue;
            words.putIfAbsent(part.toLowerCase(), part);
        }
    }

    private void readSystemDictionary(Context context) {
        try (Cursor c = context.getContentResolver().query(UserDictionary.Words.CONTENT_URI,
                new String[]{UserDictionary.Words.WORD}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String word = c.getString(0);
                if (word != null && !word.isEmpty()) words.putIfAbsent(word.toLowerCase(), word);
            }
        } catch (Exception ignored) {
            // only the current input method may read it; fine to skip
        }
    }

    private void readContacts(Context context) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
        try (Cursor c = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts.DISPLAY_NAME_PRIMARY}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) add(c.getString(0));
        } catch (Exception ignored) { }
    }

    private void readApps(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) add(String.valueOf(ri.loadLabel(pm)));
        } catch (Exception ignored) { }
    }

    /** Adds a word to the system personal dictionary. */
    static void addToSystem(Context context, String word, String locale) {
        try {
            UserDictionary.Words.addWord(context, word, 250, null,
                    locale == null ? null : java.util.Locale.forLanguageTag(locale));
        } catch (Exception ignored) { }
    }
}
