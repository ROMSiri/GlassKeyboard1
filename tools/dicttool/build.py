"""Build compact dictionaries for Glass Keyboard from AOSP .dict files.
Output (gzip, UTF-8):
  dict_<lang>.txt.gz : one entry per line "<freq>\t<word>", sorted by lowercase word; freq 0..255,
                       possibly offensive words get freq 0 (valid, never suggested).
  bigrams_<lang>.txt.gz : "<word>\t<next1> <next2> ..." (only when the source has bigrams)
"""
import gzip, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from decode import decode

def build(src, lang, out):
    attrs, words, bigrams = decode(src)
    best = {}
    for w, prob, off in words.values():
        if any(c.isspace() for c in w) or len(w) > 40: continue
        f = 0 if off else prob
        if best.get(w, -1) < f: best[w] = f
    items = sorted(best.items(), key=lambda kv: (kv[0].lower(), kv[0]))
    with gzip.open(f'{out}/dict_{lang}.txt.gz', 'wt', encoding='utf-8', compresslevel=9) as fh:
        for w, f in items: fh.write(f'{f}\t{w}\n')
    nb = 0
    if bigrams:
        with gzip.open(f'{out}/bigrams_{lang}.txt.gz', 'wt', encoding='utf-8', compresslevel=9) as fh:
            for w, tl in sorted(bigrams.items()):
                nxt = [t for t, _ in sorted(tl, key=lambda x: -x[1]) if t in best and best[t] > 0 and ' ' not in t]
                if nxt and best.get(w, 0) > 0:
                    fh.write(w + '\t' + ' '.join(nxt[:3]) + '\n'); nb += 1
    print(lang, len(items), 'words,', nb, 'bigram rows')

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
D = sys.argv[2] if len(sys.argv) > 2 else 'dicts'  # folder with main_ru.dict and main_en-US.dict
build(f'{D}/main_ru.dict', 'ru', out)
build(f'{D}/main_en-US.dict', 'en', out)
