"""Verifie chaque reference emise par nos classes 26.1.2 contre le vrai jar."""
import struct, sys, zipfile, os

def parse(data):
    off = 8
    count = struct.unpack_from(">H", data, off)[0]; off += 2
    raw = {}
    i = 1
    while i < count:
        tag = data[off]; off += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, off)[0]
            raw[i] = ("utf8", data[off+2:off+2+n].decode("utf-8", "replace")); off += 2+n
        elif tag == 7:
            raw[i] = ("class", struct.unpack_from(">H", data, off)[0]); off += 2
        elif tag == 12:
            raw[i] = ("nat", struct.unpack_from(">HH", data, off)); off += 4
        elif tag in (9, 10, 11):
            kind = {9: "field", 10: "method", 11: "imethod"}[tag]
            raw[i] = (kind, struct.unpack_from(">HH", data, off)); off += 4
        elif tag in (3, 4, 18, 17):
            off += 4
        elif tag in (5, 6):
            off += 8; i += 1
        elif tag in (8, 16, 19, 20):
            off += 2
        elif tag == 15:
            off += 3
        else:
            raise ValueError(tag)
        i += 1
    def utf8(ix): return raw[ix][1]
    refs = []
    for kind, val in raw.values():
        if kind in ("field", "method", "imethod"):
            cls_ix, nat_ix = val
            owner = utf8(raw[cls_ix][1])
            n_ix, d_ix = raw[nat_ix][1]
            refs.append((kind, owner, utf8(n_ix), utf8(d_ix)))
    return refs

FLAGS_PRIVATE = 0x0002
FLAGS_PUBLIC = 0x0001
ACC_INTERFACE = 0x0200

def members(data):
    off = 8
    count = struct.unpack_from(">H", data, off)[0]; off += 2
    utf8 = {}; i = 1
    FIX = {7:2,8:2,16:2,19:2,20:2,15:3,3:4,4:4,9:4,10:4,11:4,12:4,17:4,18:4,5:8,6:8}
    while i < count:
        tag = data[off]; off += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, off)[0]
            utf8[i] = data[off+2:off+2+n].decode("utf-8","replace"); off += 2+n
        else:
            off += FIX[tag]
            if tag in (5,6): i += 1
        i += 1
    cf = struct.unpack_from(">H", data, off)[0]; off += 2
    off += 4
    ni = struct.unpack_from(">H", data, off)[0]; off += 2 + 2*ni
    out = {"fields": {}, "methods": {}}
    for key in ("fields", "methods"):
        c = struct.unpack_from(">H", data, off)[0]; off += 2
        for _ in range(c):
            fl, n_ix, d_ix = struct.unpack_from(">HHH", data, off); off += 6
            ac = struct.unpack_from(">H", data, off)[0]; off += 2
            for _ in range(ac):
                ln = struct.unpack_from(">I", data, off+2)[0]; off += 6+ln
            out[key][(utf8[n_ix], utf8[d_ix])] = fl
    out["interface"] = bool(cf & ACC_INTERFACE)
    return out

jar = zipfile.ZipFile(sys.argv[1])
cache = {}
def load(owner):
    if owner not in cache:
        try: cache[owner] = members(jar.read(owner + ".class"))
        except KeyError: cache[owner] = None
    return cache[owner]

GAME = ("com/mojang/blaze3d/", "net/minecraft/")
problems = 0
checked = 0
for path in sys.argv[2:]:
    data = open(path, "rb").read()
    for kind, owner, name, desc in sorted(set(parse(data))):
        if not owner.startswith(GAME): continue
        info = load(owner)
        if info is None:
            print("  CLASSE ABSENTE   %s" % owner); problems += 1; continue
        table = info["fields"] if kind == "field" else info["methods"]
        checked += 1
        flags = table.get((name, desc))
        if flags is None:
            print("  MEMBRE ABSENT    %s.%s %s" % (owner, name, desc)); problems += 1; continue
        if flags & FLAGS_PRIVATE:
            print("  MEMBRE PRIVE     %s.%s %s" % (owner, name, desc)); problems += 1
        elif not (flags & FLAGS_PUBLIC):
            print("  NON PUBLIC       %s.%s %s" % (owner, name, desc)); problems += 1
        if kind in ("method", "imethod"):
            expect = "imethod" if info["interface"] else "method"
            if kind != expect:
                print("  GENRE D'APPEL    %s.%s : emis=%s reel=%s" % (owner, name, kind, expect))
                problems += 1
print("=> %d reference(s) verifiee(s), %d probleme(s)" % (checked, problems))
sys.exit(1 if problems else 0)
