"""Emoji keyword index from Unicode CLDR annotations (cldr-json, annotations/<lang>/annotations.json).
Writes emoji_<lang>.dat (gzip): "<keyword>\t<emoji> <emoji> ..." sorted by keyword; emojis in
the order of order.txt (common ones first). Run by CI: CLDR is downloaded at build time.
usage: build_emoji.py <dir with ru.json, en.json> <assets dir>
"""
import gzip, json, os, re, sys

here = os.path.dirname(os.path.abspath(__file__))
order = {}
for i, line in enumerate(open(os.path.join(here, 'order.txt'), encoding='utf-8')):
    e = line.strip()
    if e and e not in order: order[e] = i

def norm(e): return e.replace('️', '')

rank = {norm(e): i for e, i in order.items()}
src, out = sys.argv[1], sys.argv[2]
for lang in ('ru', 'en'):
    path = os.path.join(src, f'{lang}.json')
    if not os.path.exists(path):
        print('missing', path); continue
    data = json.load(open(path, encoding='utf-8'))['annotations']['annotations']
    index = {}
    names = {}  # emoji -> its spoken name
    for emoji, ann in data.items():
        tts = (ann.get('tts') or [''])[0].lower().replace('ё', 'е').strip()
        names[emoji] = tts
        if norm(emoji) not in rank: continue          # only emojis the keyboard knows (no rare symbols)
        words = set()
        # CLDR keywords as they are; the spoken name only when it is a single word
        for phrase in ann.get('default', []):
            phrase = phrase.lower().replace('ё', 'е').strip()
            if len(phrase) >= 2: words.add(phrase)
        for phrase in ann.get('tts', []):
            phrase = phrase.lower().replace('ё', 'е').strip()
            if len(phrase) >= 2 and ' ' not in phrase: words.add(phrase)
        for w in words:
            if w: index.setdefault(w, set()).add(emoji)
    with gzip.open(os.path.join(out, f'emoji_{lang}.dat'), 'wt', encoding='utf-8', compresslevel=9) as fh:
        for w in sorted(index):
            def prio(e):
                n = names.get(e, '')
                # own name is the word > name starts with it > single symbol > rest
                p = 0 if n == w else 1 if n.startswith(w) or n.endswith(' ' + w) else 2
                if p == 2 and len(norm(e)) == 1: p = 1.5
                return (p, rank.get(norm(e), 1 << 20))
            es = sorted(index[w], key=prio)[:8]
            fh.write(w + '\t' + ' '.join(es) + '\n')
    print(lang, len(index), 'keywords')
