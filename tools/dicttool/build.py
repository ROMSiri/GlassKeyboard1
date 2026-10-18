"""Build compact dictionaries for Glass Keyboard from AOSP .dict files.
Output (gzip, UTF-8; ".dat" because aapt unpacks ".gz" assets):
  dict_<lang>.dat : one entry per line "<freq>\t<word>", sorted by lowercase word; freq 0..255;
                    possibly offensive words are prefixed "!" ("!120\tword").
  shortcuts_<lang>.dat : "<word>\t<target>\t<prob 0..15>" (15 = always autocorrect)
  bigrams_<lang>.dat : "<word>\t<next1> <next2> ..." (only when the source has bigrams)
"""
import gzip, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from decode import decode

def build(src, lang, out):
    attrs, words, bigrams, shortcuts = decode(src)
    best, offensive = {}, set()
    for w, prob, off in words.values():
        if any(c.isspace() for c in w) or len(w) > 40: continue
        if best.get(w, -1) < prob: best[w] = prob
        if off: offensive.add(w)
    items = sorted(best.items(), key=lambda kv: (kv[0].lower(), kv[0]))
    with gzip.open(f'{out}/dict_{lang}.dat', 'wt', encoding='utf-8', compresslevel=9) as fh:
        for w, f in items: fh.write(f'{"!" if w in offensive else ""}{f}\t{w}\n')
    with gzip.open(f'{out}/shortcuts_{lang}.dat', 'wt', encoding='utf-8', compresslevel=9) as fh:
        for w, tl in sorted(shortcuts.items()):
            for t, p in tl: fh.write(f'{w}\t{t}\t{p}\n')
    nb = 0
    best = {w: (0 if w in offensive else f) for w, f in best.items()}  # never predict offensive words
    if bigrams:
        with gzip.open(f'{out}/bigrams_{lang}.dat', 'wt', encoding='utf-8', compresslevel=9) as fh:
            for w, tl in sorted(bigrams.items()):
                nxt = [t for t, _ in sorted(tl, key=lambda x: -x[1]) if t in best and best[t] > 0 and ' ' not in t]
                if nxt and best.get(w, 0) > 0:
                    fh.write(w + '\t' + ' '.join(nxt[:3]) + '\n'); nb += 1
    print(lang, len(items), 'words,', len(offensive), 'offensive,', nb, 'bigram rows,', len(shortcuts), 'shortcuts')

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
D = sys.argv[2] if len(sys.argv) > 2 else 'dicts'  # folder with main_ru.dict and main_en-US.dict
build(f'{D}/main_ru.dict', 'ru', out)
build(f'{D}/main_en-US.dict', 'en', out)
