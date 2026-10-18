package dev.glasskeyboard.ime;

import android.service.textservice.SpellCheckerService;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * System spell checker (Settings → Languages → Spell checker): underlines unknown words in other
 * apps and offers corrections from the same dictionaries and engine as the keyboard.
 */
public final class GlassSpellChecker extends SpellCheckerService {
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}'’-]*");

    @Override public Session createSession() { return new CheckerSession(); }

    private final class CheckerSession extends Session {
        private String lang = "ru";
        private final Suggest suggest = new Suggest();
        private final java.util.Map<String, UserDictionary> users = new java.util.HashMap<>();

        @Override public void onCreate() {
            String locale = getLocale();
            lang = locale != null && locale.startsWith("en") ? "en" : "ru";
            suggest.setLayout("en".equals(lang) ? Layouts.english() : Layouts.russian());
        }

        @Override public SuggestionsInfo onGetSuggestions(TextInfo textInfo, int suggestionsLimit) {
            String word = textInfo.getText();
            String l = lang;
            if (word != null && !word.isEmpty()) l = isCyrillic(word) ? "ru" : "en"; // check by script
            WordDictionary dict = DictionaryCache.get(GlassSpellChecker.this, l);
            if (word == null || word.length() < 2 || dict == null || !Character.isLetter(word.charAt(0)))
                return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, new String[0]);
            String lower = word.toLowerCase();
            if (dict.frequencyOf(lower) >= 0)
                return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, new String[0]);
            UserDictionary user = users.get(l);
            if (user == null) { user = UserDictionary.load(GlassSpellChecker.this, l); users.put(l, user); }
            if (user.count(lower) > 0)
                return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, new String[0]);
            if (!l.equals(lang)) suggest.setLayout("en".equals(l) ? Layouts.english() : Layouts.russian());
            Suggest.Options o = new Suggest.Options();
            o.chips = 5;
            o.emojis = false;
            Suggest.Result r = suggest.suggest(word, dict, user, null, null, o);
            List<String> out = new ArrayList<>();
            int c = r.center();
            if (r.slots[c] != null) out.add(r.slots[c]);
            for (int i = 0; i < r.slots.length; i++)
                if (i != c && i != r.literalSlot && r.slots[i] != null) out.add(r.slots[i]);
            while (out.size() > Math.max(1, suggestionsLimit)) out.remove(out.size() - 1);
            int attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO;
            if (!out.isEmpty()) attrs |= SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS;
            return new SuggestionsInfo(attrs, out.toArray(new String[0]));
        }

        @Override public SentenceSuggestionsInfo[] onGetSentenceSuggestionsMultiple(TextInfo[] textInfos,
                                                                                   int suggestionsLimit) {
            SentenceSuggestionsInfo[] result = new SentenceSuggestionsInfo[textInfos.length];
            for (int t = 0; t < textInfos.length; t++) {
                String text = textInfos[t].getText();
                List<SuggestionsInfo> infos = new ArrayList<>();
                List<Integer> offsets = new ArrayList<>(), lengths = new ArrayList<>();
                Matcher m = WORD.matcher(text == null ? "" : text);
                while (m.find()) {
                    String w = m.group();
                    while (w.endsWith("-") || w.endsWith("'") || w.endsWith("’")) w = w.substring(0, w.length() - 1);
                    SuggestionsInfo si = onGetSuggestions(new TextInfo(w), suggestionsLimit);
                    si.setCookieAndSequence(textInfos[t].getCookie(), textInfos[t].getSequence());
                    infos.add(si);
                    offsets.add(m.start());
                    lengths.add(w.length());
                }
                int[] off = new int[offsets.size()], len = new int[lengths.size()];
                for (int i = 0; i < off.length; i++) { off[i] = offsets.get(i); len[i] = lengths.get(i); }
                result[t] = new SentenceSuggestionsInfo(infos.toArray(new SuggestionsInfo[0]), off, len);
            }
            return result;
        }
    }

    private static boolean isCyrillic(String w) {
        for (int i = 0; i < w.length(); i++)
            if (Character.UnicodeBlock.of(w.charAt(i)) == Character.UnicodeBlock.CYRILLIC) return true;
        return false;
    }
}
