import sys, zlib, struct, math

def mulberry(seed):
    s = [seed & 0xffffffff]
    def imul(a, b): return ((a & 0xffffffff) * (b & 0xffffffff)) & 0xffffffff
    def r():
        s[0] = (s[0] + 0x6D2B79F5) & 0xffffffff
        t = s[0]
        t = imul(t ^ (t >> 15), 1 | t)
        t = ((t + imul(t ^ (t >> 7), 61 | t)) & 0xffffffff) ^ t
        return ((t ^ (t >> 14)) & 0xffffffff) / 4294967296
    return r

def hexc(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))

def texture(paint, seed):
    r = mulberry(seed)
    return [[hexc(paint(x, y, r)) for x in range(16)] for y in range(16)]

def pick(r, l):
    return l[int(r() * len(l))]

def cells(count, seed):
    r = mulberry(seed)
    pts = [(r() * 16, r() * 16) for _ in range(count)]
    def owner(x, y):
        best, d = 0, 1e9
        for i, (px, py) in enumerate(pts):
            dx = min(abs(px - x), 16 - abs(px - x))
            dy = min(abs(py - y), 16 - abs(py - y))
            q = dx * dx + dy * dy
            if q < d:
                d, best = q, i
        return best
    return lambda x, y: (owner(x, y), owner(x + 1, y) != owner(x, y) or owner(x, y + 1) != owner(x, y))

def block(p):
    # Sans le cadre du bloc : la tuile a déjà son biseau, et le cadre faisait un angle dans le coin arrondi.
    def f(x, y, r):
        if p.get('facet') and ((x + y) % 7 == 0 or (x - y + 16) % 9 == 0): return p['light'] if r() < .6 else p['mid']
        if y % 5 == 0 and 2 < x < 13: return p['mid']
        return pick(r, p['fill'])
    return f

stone, amethyst = cells(10, 7), cells(8, 11)
SEAM = [3, 11, 6, 13]

def bois(x, y, r):
    if y % 4 == 3: return '#7E5D35'
    if x == SEAM[y // 4]: return '#8C6A3E'
    return pick(r, ['#B8945F', '#AF8A55', '#C29D66', '#B08D58', '#A6814F'])

def pierre(x, y, r):
    cid, edge = stone(x, y)
    if edge: return pick(r, ['#5A5A5A', '#525252'])
    return ['#8F8F8F', '#A3A3A3', '#7C7C7C', '#B0B0B0', '#979797'][cid % 5]

def netherite(x, y, r):
    if x % 8 == 0 or y % 8 == 0: return '#29252A'
    if x % 8 == 1 or y % 8 == 1: return '#57505A'
    return pick(r, ['#433D45', '#4B454D', '#3C373E', '#463F48'])

def champion(x, y, r):
    cid, edge = amethyst(x, y)
    if edge: return '#7A2386'
    return ['#C95BD6', '#E07BEA', '#A23DB3', '#F2A7F5', '#D06ADF'][cid % 5]

TEX = {
    'bois': texture(bois, 3),
    'pierre': texture(pierre, 5),
    'fer': texture(block(dict(light='#FFFFFF', dark='#A3A3A3', mid='#C6C6C6', fill=['#DCDCDC', '#E4E4E4', '#D6D6D6'])), 9),
    'or': texture(block(dict(light='#FFF6A8', dark='#C98E12', mid='#E9B52C', fill=['#F7D33E', '#FCE15A', '#F2CA34'])), 13),
    'diamant': texture(block(dict(light='#C9FFFA', dark='#23948F', mid='#3FC6BF', fill=['#5FE0D8', '#74E9E1', '#55D5CD'], facet=True)), 17),
    'netherite': texture(netherite, 19),
    'champion': texture(champion, 23),
}
EDGE = {'bois': '#4E3519', 'pierre': '#3F3F44', 'fer': '#6E747C', 'or': '#8A5C08',
        'diamant': '#156C68', 'netherite': '#18151A', 'champion': '#5A1666'}

def rounded_inside(x, y, w, h, rad):
    if x < 0 or y < 0 or x > w or y > h: return False
    cx = min(max(x, rad), w - rad)
    cy = min(max(y, rad), h - rad)
    return (x - cx) ** 2 + (y - cy) ** 2 <= rad * rad

def dist_edge(x, y, w, h, rad):
    cx = min(max(x, rad), w - rad)
    cy = min(max(y, rad), h - rad)
    if (x < rad or x > w - rad) and (y < rad or y > h - rad):
        return rad - math.hypot(x - cx, y - cy)
    return min(x, y, w - x, h - y)

def render(kind, size, ss=3):
    tex = TEX[kind]
    edge = hexc(EDGE[kind])
    gold = kind == 'champion'
    W = size * ss
    H = int(size * 1.08) * ss
    face = size * ss * 0.86      # côté de la face
    rad = face * 0.22            # coins arrondis
    top_w = face * 0.93          # perspective : le haut un peu plus étroit
    face_h = face * 0.9          # et la face un peu écrasée
    thick = face * 0.09          # la tranche
    oy = (H - face_h - thick) / 2 - ss

    def side(X, Y):
        vv = (Y + .5 - oy - thick) / face_h
        if 0 <= vv <= 1:
            wv = top_w + (face - top_w) * vv
            u = (X + .5 - (W - wv) / 2) / wv
            if rounded_inside(u * face, vv * face, face, face, rad):
                k = 0.72 + 0.25 * (1 - vv)
                return (int(edge[0] * k), int(edge[1] * k), int(edge[2] * k), 255)
        return (0, 0, 0, 0)

    img = [[(0, 0, 0, 0)] * W for _ in range(H)]
    for Y in range(H):
        for X in range(W):
            v = (Y + .5 - oy) / face_h
            if v < 0 or v > 1:
                img[Y][X] = side(X, Y)
                continue
            wv = top_w + (face - top_w) * v
            u = (X + .5 - (W - wv) / 2) / wv
            fx, fy = u * face, v * face
            if not rounded_inside(fx, fy, face, face, rad):
                img[Y][X] = side(X, Y)
                continue
            c = tex[min(15, int(v * 16))][min(15, max(0, int(u * 16)))]
            light = 1.1 - 0.34 * v                      # haut éclairé, bas dans l'ombre
            d = dist_edge(fx, fy, face, face, rad) / face
            slope = 1 - 2 * fy / face                   # +1 en haut, -1 en bas : le biseau tourne en douceur
            bevel = 0
            if d < 0.075:
                bevel = 0.26 * slope                    # le biseau
            elif d < 0.09:
                bevel = 0.1 * slope
            if gold and d < 0.05:                       # le liseré d'or de Champion
                c = (247, 201, 72)
                bevel = 0.14 * slope
            k = max(0, light + bevel)
            img[Y][X] = tuple(min(255, int(ch * k)) for ch in c) + (255,)

    out = []                                            # réduction : bords lissés
    for y in range(H // ss):
        row = []
        for x in range(W // ss):
            acc = [0, 0, 0, 0]
            for dy in range(ss):
                for dx in range(ss):
                    p = img[y * ss + dy][x * ss + dx]
                    a = p[3]
                    acc[0] += p[0] * a; acc[1] += p[1] * a; acc[2] += p[2] * a; acc[3] += a
            a = acc[3]
            row.append((acc[0] // a, acc[1] // a, acc[2] // a, a // (ss * ss)) if a else (0, 0, 0, 0))
        out.append(row)
    return out

def png(path, pixels):
    h = len(pixels); w = len(pixels[0])
    raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in pixels)
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))

out = sys.argv[1]
for kind in TEX:
    png(f'{out}/{kind}.png', render(kind, 256))
    png(f'{out}/texture-{kind}.png', [[c + (255,) for c in row] for row in TEX[kind]])
    print(kind, flush=True)
