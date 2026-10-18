package dev.glasskeyboard.ime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gesture ("swipe") typing: compares the finger path with the ideal path through the key centres
 * of dictionary words. Candidates must start near the first point, end near the last point and
 * have every letter close to the path; the rest is ranked by how well the resampled paths line up
 * plus word frequency.
 */
final class GestureDecoder {
    private static final int CHARS = 0x500;
    private static final int N = 40;                 // resampling points

    private final float[] kx = new float[CHARS], ky = new float[CHARS];
    private final boolean[] has = new boolean[CHARS];
    private float keyW = 1f;

    /** Key centres in px (letters, including long-press letters on their host key). */
    void setKeys(Map<Character, float[]> centers, float keyWidth) {
        java.util.Arrays.fill(has, false);
        for (Map.Entry<Character, float[]> e : centers.entrySet()) {
            char c = Suggest.norm(e.getKey());
            if (c >= CHARS) continue;
            kx[c] = e.getValue()[0]; ky[c] = e.getValue()[1]; has[c] = true;
        }
        keyW = Math.max(1f, keyWidth);
    }

    private static final class Scored { String word; double score; Scored(String w, double s) { word = w; score = s; } }

    List<String> decode(float[] xs, float[] ys, int n, WordDictionary dict, UserDictionary user,
                        Suggest.Options o, int max) {
        List<String> out = new ArrayList<>();
        if (n < 2 || dict == null) return out;
        float[][] g = resample(xs, ys, n, N);
        float gLen = length(xs, ys, n);

        List<Character> starts = near(xs[0], ys[0], 1.1f);
        boolean[] endOk = new boolean[CHARS];
        for (char c : near(xs[n - 1], ys[n - 1], 1.1f)) endOk[c] = true;

        Map<String, Scored> best = new HashMap<>();
        float[] tx = new float[64], ty = new float[64];
        for (char s : starts) {
            for (char first : s == 'е' ? new char[]{'е', 'ё'} : new char[]{s}) {
                int from = dict.lowerBound(String.valueOf(first));
                int to = dict.lowerBound(String.valueOf((char) (first + 1)));
                for (int i = from; i < to; i++) {
                    int f = Suggest.freqFor(dict, i, o);
                    if (f == 0) continue;
                    int len = dict.length(i);
                    if (len < 2 || len > 30) continue;
                    char last = Suggest.norm(dict.lowerCharAt(i, len - 1));
                    if (last >= CHARS || !endOk[last]) continue;
                    int m = template(dict, i, tx, ty);
                    if (m < 2) continue;
                    double sc = score(g, gLen, xs, ys, n, tx, ty, m, f);
                    if (sc < Double.MAX_VALUE) keep(best, dict.word(i), sc);
                }
            }
        }
        if (user != null) {
            for (UserDictionary.Entry e : user.words.values()) {
                if (e.count < 2) continue;
                String w = e.word.toLowerCase();
                int m = template(w, tx, ty);
                if (m < 2 || !starts.contains(Suggest.norm(w.charAt(0)))) continue;
                char last = Suggest.norm(w.charAt(w.length() - 1));
                if (last >= CHARS || !endOk[last]) continue;
                double sc = score(g, gLen, xs, ys, n, tx, ty, m, 140 + 10 * Math.min(e.count, 8));
                if (sc < Double.MAX_VALUE) keep(best, e.word, sc);
            }
        }
        List<Scored> list = new ArrayList<>(best.values());
        list.sort((a, b) -> Double.compare(a.score, b.score));
        for (Scored s : list) { out.add(s.word); if (out.size() >= max) break; }
        return out;
    }

    private static void keep(Map<String, Scored> best, String word, double score) {
        String k = word.toLowerCase();
        Scored s = best.get(k);
        if (s == null) best.put(k, new Scored(word, score));
        else if (score < s.score) { s.score = score; s.word = word; }
    }

    /** Lower is better; MAX_VALUE when the word is clearly not what was drawn. */
    private double score(float[][] g, float gLen, float[] xs, float[] ys, int n,
                         float[] tx, float[] ty, int m, int freq) {
        float tLen = length(tx, ty, m);
        if (tLen > gLen * 1.6f + keyW || tLen < gLen * .35f - keyW) return Double.MAX_VALUE;
        // Every letter must be passed reasonably close, in order. The first letter belongs to the
        // start of the gesture and the last to its end; for the others take the first close pass
        // (a later pass of the same key belongs to a later letter).
        double cover = Math.hypot(xs[0] - tx[0], ys[0] - ty[0]) / keyW
                + Math.hypot(xs[n - 1] - tx[m - 1], ys[n - 1] - ty[m - 1]) / keyW;
        int from = 0;
        for (int v = 1; v < m - 1; v++) {
            double bestD = Double.MAX_VALUE;
            int bestJ = from;
            for (int j = from; j < n; j++) {
                double d = Math.hypot(xs[j] - tx[v], ys[j] - ty[v]) / keyW;
                if (d < bestD) { bestD = d; bestJ = j; }
                // close enough and moving away again: this is the pass we want
                if (bestD < .45 && d > bestD + .5) break;
            }
            if (bestD > 1.15) return Double.MAX_VALUE;
            cover += bestD;
            from = bestJ;
        }
        cover /= m;
        float[][] t = resample(tx, ty, m, N);
        double loc = 0;
        for (int k = 0; k < N; k++) loc += Math.hypot(g[0][k] - t[0][k], g[1][k] - t[1][k]);
        loc /= N * keyW;
        return loc * 2.2 + cover * 1.6 - freq / 255.0 * 1.4;
    }

    /** Key centres of the word's letters, consecutive duplicates collapsed. */
    private int template(WordDictionary dict, int i, float[] tx, float[] ty) {
        int m = 0, len = dict.length(i);
        for (int k = 0; k < len; k++) {
            char c = Suggest.norm(dict.lowerCharAt(i, k));
            if (c == '\'' || c == '-' || c == '’') continue;
            if (c >= CHARS || !has[c]) return 0;
            if (m > 0 && tx[m - 1] == kx[c] && ty[m - 1] == ky[c]) continue;
            if (m >= tx.length) return 0;
            tx[m] = kx[c]; ty[m] = ky[c]; m++;
        }
        return m;
    }

    private int template(String w, float[] tx, float[] ty) {
        int m = 0;
        for (int k = 0; k < w.length(); k++) {
            char c = Suggest.norm(w.charAt(k));
            if (c == '\'' || c == '-' || c == '’') continue;
            if (c >= CHARS || !has[c]) return 0;
            if (m > 0 && tx[m - 1] == kx[c] && ty[m - 1] == ky[c]) continue;
            if (m >= tx.length) return 0;
            tx[m] = kx[c]; ty[m] = ky[c]; m++;
        }
        return m;
    }

    private List<Character> near(float x, float y, float radiusKeys) {
        List<Character> out = new ArrayList<>();
        float r = radiusKeys * keyW;
        for (char c = 0; c < CHARS; c++) {
            if (!has[c] || !Character.isLetter(c)) continue;
            if (Math.hypot(kx[c] - x, ky[c] - y) <= r) out.add(c);
        }
        return out;
    }

    static float length(float[] xs, float[] ys, int n) {
        float l = 0;
        for (int i = 1; i < n; i++) l += (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        return l;
    }

    /** {@code count} points evenly spaced along the polyline. */
    static float[][] resample(float[] xs, float[] ys, int n, int count) {
        float[][] out = new float[2][count];
        float total = length(xs, ys, n);
        if (total <= 0f) {
            for (int k = 0; k < count; k++) { out[0][k] = xs[0]; out[1][k] = ys[0]; }
            return out;
        }
        float step = total / (count - 1), acc = 0f;
        int seg = 1, k = 1;
        out[0][0] = xs[0]; out[1][0] = ys[0];
        float px = xs[0], py = ys[0];
        while (k < count - 1 && seg < n) {
            float sx = xs[seg] - px, sy = ys[seg] - py;
            float d = (float) Math.hypot(sx, sy);
            if (acc + d >= step && d > 0f) {
                float t = (step - acc) / d;
                px += sx * t; py += sy * t;
                out[0][k] = px; out[1][k] = py; k++;
                acc = 0f;
            } else {
                acc += d;
                px = xs[seg]; py = ys[seg]; seg++;
            }
        }
        for (; k < count; k++) { out[0][k] = xs[n - 1]; out[1][k] = ys[n - 1]; }
        return out;
    }
}
