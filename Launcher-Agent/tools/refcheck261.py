"""Verifie chaque reference emise par nos classes 26.1.2 contre le vrai jar.

Controle, pour chaque reference vers une classe du jeu :
- membre (champ/methode) : existence avec le descripteur EXACT, visibilite
  publique, genre d'appel (invokevirtual vs invokeinterface). Le membre est
  cherche en REMONTANT la hierarchie (superclasses puis interfaces) : javac
  ecrit le type STATIQUE du receveur comme proprietaire (LocalPlayer.getHealth
  alors que la methode est declaree sur LivingEntity), et la JVM remonte de la
  meme facon. Sans cette remontee, chaque methode heritee sortait en faux
  positif (28 sur AccessorBindings261 le 2026-09-13).
- classe seule (instanceof, checkcast, new, tableaux) : existence.

Usage : python tools/refcheck261.py <26.1.2.jar> <fichier.class>...
"""
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
        if kind == "class":
            name = utf8(val)
            # Tableau ("[Lnet/...;") : on ne garde que le type d'element objet.
            if name.startswith("["):
                name = name.lstrip("[")
                if not (name.startswith("L") and name.endswith(";")):
                    continue
                name = name[1:-1]
            refs.append(("class", name, "", ""))
        elif kind in ("field", "method", "imethod"):
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
    utf8 = {}; classes = {}; i = 1
    FIX = {7:2,8:2,16:2,19:2,20:2,15:3,3:4,4:4,9:4,10:4,11:4,12:4,17:4,18:4,5:8,6:8}
    while i < count:
        tag = data[off]; off += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, off)[0]
            utf8[i] = data[off+2:off+2+n].decode("utf-8","replace"); off += 2+n
        else:
            if tag == 7:
                classes[i] = struct.unpack_from(">H", data, off)[0]
            off += FIX[tag]
            if tag in (5,6): i += 1
        i += 1
    def class_name(ix): return utf8[classes[ix]] if ix in classes else None
    cf = struct.unpack_from(">H", data, off)[0]; off += 2
    super_ix = struct.unpack_from(">H", data, off + 2)[0]; off += 4
    ni = struct.unpack_from(">H", data, off)[0]; off += 2
    interfaces = [class_name(struct.unpack_from(">H", data, off + 2*k)[0]) for k in range(ni)]
    off += 2*ni
    out = {"fields": {}, "methods": {}, "super": class_name(super_ix), "interfaces": interfaces}
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

def find_member(owner, key, name, desc):
    """Drapeaux du membre, cherche dans owner puis ses superclasses et interfaces."""
    seen = set()
    pending = [owner]
    while pending:
        cls = pending.pop(0)
        if cls is None or cls in seen:
            continue
        seen.add(cls)
        info = load(cls)
        if info is None:
            # Hors du jar client (java/lang/Object, bibliotheque) : on ne peut
            # rien affirmer — le membre n'est simplement pas trouve ici.
            continue
        flags = info[key].get((name, desc))
        if flags is not None:
            return flags
        pending.append(info["super"])
        pending.extend(info["interfaces"])
    return None

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
        checked += 1
        if kind == "class":
            continue
        key = "fields" if kind == "field" else "methods"
        flags = find_member(owner, key, name, desc)
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
