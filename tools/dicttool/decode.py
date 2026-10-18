"""Decode an AOSP/HeliBoard v202 binary dictionary into words, unigram probabilities and bigrams."""
import struct, sys, json

def decode(path):
    d = open(path, 'rb').read()
    assert d[:4] == bytes.fromhex('9bc13afe')
    hsize = struct.unpack('>I', d[8:12])[0]

    def read_cp(buf, pos, table):
        b = buf[pos]
        if b < 0x20:
            if b == 0x1F: return None, pos + 1
            return (b << 16) | (buf[pos+1] << 8) | buf[pos+2], pos + 3
        return (table[b - 0x20] if table else b), pos + 1

    # header attributes (no code point table for header strings)
    attrs = {}; pos = 12
    while pos < hsize:
        k = []; 
        while True:
            c, pos = read_cp(d, pos, None)
            if c is None: break
            k.append(c)
        v = []
        while True:
            c, pos = read_cp(d, pos, None)
            if c is None: break
            v.append(c)
        attrs[''.join(map(chr, k))] = v
    table = attrs.get('codePointTable')
    body = d[hsize:]
    words = {}      # node pos -> (word, prob)
    bigram_src = [] # (node pos, [(target pos, prob4)])

    stack = [(0, '')]
    while stack:
        apos, prefix = stack.pop()
        p = apos
        n = body[p]; p += 1
        if n >= 0x80: n = ((n & 0x7F) << 8) | body[p]; p += 1
        for _ in range(n):
            node = p
            flags = body[p]; p += 1
            chars = []
            if flags & 0x20:
                while True:
                    c, p = read_cp(body, p, table)
                    if c is None: break
                    chars.append(c)
            else:
                c, p = read_cp(body, p, table); chars.append(c)
            w = prefix + ''.join(map(chr, chars))
            prob = None
            if flags & 0x10: prob = body[p]; p += 1
            t = flags & 0xC0
            child = None
            if t:
                base = p
                size = {0x40: 1, 0x80: 2, 0xC0: 3}[t]
                off = int.from_bytes(body[p:p+size], 'big'); p += size
                child = base + off
            if flags & 0x08:
                sz = struct.unpack('>H', body[p:p+2])[0]; p += sz
            bl = []
            if flags & 0x04:
                while True:
                    bf = body[p]; origin = p + 1; p += 1
                    size = {0x10: 1, 0x20: 2, 0x30: 3}.get(bf & 0x30, 0)
                    off = int.from_bytes(body[p:p+size], 'big'); p += size
                    tgt = origin - off if bf & 0x40 else origin + off
                    bl.append((tgt, bf & 0x0F))
                    if not bf & 0x80: break
            if prob is not None and not flags & 0x02:
                words[node] = (w, prob, bool(flags & 0x01))
            if bl and node in words: bigram_src.append((node, bl))
            elif bl: bigram_src.append((node, bl))
            if child is not None: stack.append((child, w))
    bigrams = {}
    for node, bl in bigram_src:
        if node not in words: continue
        src = words[node][0]
        tl = [(words[t][0], pr) for t, pr in bl if t in words]
        if tl: bigrams[src] = tl
    return attrs, words, bigrams

if __name__ == '__main__':
    attrs, words, bigrams = decode(sys.argv[1])
    ws = sorted(words.values(), key=lambda x: -x[1])
    print(len(ws), 'words', len(bigrams), 'bigram sources', sum(len(v) for v in bigrams.values()), 'pairs')
    print(ws[:30])
    print(list(bigrams.items())[:3])
    print('offensive', sum(1 for w in ws if w[2]))
