# -*- coding: utf-8 -*-
"""Confronte les stubs compile-only aux VRAIES classes du jeu.

    python tools/audit_stubs.py

Pourquoi cet outil existe
-------------------------
Les stubs de `src/stubs/` servent uniquement a compiler : au runtime, c'est la
vraie classe du jeu qui repond. Si un stub declare une signature FAUSSE, rien
n'echoue a la compilation — la panne arrive en jeu, sous forme de
`NoSuchMethodError`, et elle est souvent avalee par un `catch (Throwable)`.
Trois bugs de ce type ont ete trouves d'un coup le 2026-08-27, apres la
suppression des replis reflexifs qui les masquaient :

  * `SoundManager.play`   declare `void`, reel `SoundEngine$PlayResult` (ping du chat muet)
  * `Holder.getKey`       n'existe pas, reel `unwrapKey`                  (biome vide)
  * `ResourceKey.getValue` n'existe pas, reel `identifier`                (biome vide)
  * `LocalPlayer.removeEffect`   declare `void`, reel `boolean`
  * `LocalPlayer.getGameProfile` declare `Object`, reel `GameProfile`

Trois modes de panne detectes
-----------------------------
  A. le NOM n'existe nulle part dans la hierarchie reelle ;
  B. le nom existe mais le TYPE DE RETOUR differe — il fait partie du
     descripteur d'un invokevirtual, donc un retour faux = methode introuvable ;
  C. le stub declare `interface` la ou le jeu a une `class` (ou l'inverse) —
     javac emet alors invokeinterface au lieu d'invokevirtual, d'ou un
     `IncompatibleClassChangeError: Found class X, but interface was expected`.
     Ajoute le 2026-08-27 apres que `ResourceKey`, declare interface alors que
     c'est une classe, ait fait echouer le biome une SECONDE fois — le meme
     jour, apres correction de ses noms de methodes.

Les PARAMETRES ne sont pas encore compares (surcharges + generiques a
resoudre) : un stub peut donc encore mentir sur eux. A ajouter si un bug de
ce genre apparait.
"""
import os, re, struct, sys, zipfile

APPDATA = os.environ.get("APPDATA", "")
JARS = [
    os.path.join(APPDATA, r"YuyuFrame\.minecraft\versions\26.1.2\26.1.2.jar"),
    os.path.join(APPDATA, r"YuyuFrame\.minecraft\libraries\com\mojang\authlib\9.0.75\authlib-9.0.75.jar"),
    # brigadier n'est PAS dans le jar du jeu (bibliotheque a part, et absente
    # du classpath de compilation de l'agent — voir build.bat). Sans cette
    # entree, les stubs de l'arbre de commandes utilises par MacroModule
    # seraient ranges dans "classes absentes des jars", donc jamais verifies.
    os.path.join(APPDATA, r"YuyuFrame\.minecraft\libraries\com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar"),
]
HERE = os.path.dirname(os.path.abspath(__file__))
STUBS = os.path.normpath(os.path.join(HERE, "..", "src", "stubs"))


# ----------------------------------------------------------------- .class ---
def parse_class(data):
    off = 10
    cp_count = struct.unpack_from(">H", data, 8)[0]
    cp, i = {}, 1
    while i < cp_count:
        tag = data[off]; off += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, off)[0]; off += 2
            cp[i] = data[off:off + ln].decode("utf-8", "replace"); off += ln
        elif tag == 7:
            cp[i] = ("class", struct.unpack_from(">H", data, off)[0]); off += 2
        elif tag in (8, 16, 19, 20): off += 2
        elif tag == 15: off += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): off += 4
        elif tag in (5, 6): off += 8; i += 1
        else: raise ValueError("constant pool tag %d inattendu" % tag)
        i += 1
    access, _, super_i = struct.unpack_from(">HHH", data, off); off += 6
    n_if = struct.unpack_from(">H", data, off)[0]; off += 2
    ifaces = [struct.unpack_from(">H", data, off + 2 * k)[0] for k in range(n_if)]
    off += 2 * n_if

    def cname(idx):
        e = cp.get(idx) if idx else None
        return cp.get(e[1]) if isinstance(e, tuple) else None

    def skip_attrs(o):
        n = struct.unpack_from(">H", data, o)[0]; o += 2
        for _ in range(n):
            o += 6 + struct.unpack_from(">I", data, o + 2)[0]
        return o

    methods = []
    for kind in range(2):                       # champs puis methodes
        n = struct.unpack_from(">H", data, off)[0]; off += 2
        for _ in range(n):
            _, ni, di = struct.unpack_from(">HHH", data, off); off += 6
            off = skip_attrs(off)
            if kind == 1:
                methods.append((cp.get(ni), cp.get(di)))
    return {"super": cname(super_i),
            "ifaces": [x for x in (cname(y) for y in ifaces) if x],
            "methods": methods,
            "is_interface": bool(access & 0x0200)}


class Game:
    """Acces en lecture aux vraies classes, tous jars confondus."""

    def __init__(self, jars):
        self.zips = []
        for j in jars:
            if os.path.exists(j):
                self.zips.append(zipfile.ZipFile(j))
            else:
                print("  (jar absent, ignore) %s" % j)
        self.cache = {}

    def load(self, internal):
        if internal in self.cache:
            return self.cache[internal]
        info = None
        for z in self.zips:
            try:
                info = parse_class(z.read(internal + ".class")); break
            except KeyError:
                continue
        self.cache[internal] = info
        return info

    def lookup(self, internal, name):
        """Descripteurs de `name` dans toute la hierarchie (superclasses + interfaces)."""
        seen, todo, found = set(), [internal], []
        while todo:
            cur = todo.pop()
            if not cur or cur in seen:
                continue
            seen.add(cur)
            info = self.load(cur)
            if not info:
                continue
            found += [d for n, d in info["methods"] if n == name]
            if info["super"]:
                todo.append(info["super"])
            todo += info["ifaces"]
        return found


# ------------------------------------------------------------------ stubs ---
CLASS_DECL = re.compile(
    r"\b(?:public|private|protected|static|final|abstract|sealed)\s+"
    r"(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
# `public` est OPTIONNEL : dans une interface, les membres sont implicitement
# publics et les stubs l'omettent (Component, Holder, ResourceKey...). L'exiger
# faisait sauter TOUTES les methodes d'interface — c'est ce qui avait laisse
# passer `Component.literal` declare `Component` alors qu'il renvoie
# `MutableComponent` (2026-08-27, 3e aller-retour sur le meme bug).
METHOD_DECL = re.compile(
    r"^[ \t]+(?:public\s+|static\s+|final\s+|abstract\s+|native\s+|default\s+)+"
    r"([A-Za-z_$][\w.$<>,\[\]\s?]*?)\s+([a-zA-Z_$][\w$]*)\s*\(", re.M)

# Mots-cles qui produiraient un faux positif s'ils etaient pris pour un type.
NOT_A_TYPE = {"class", "interface", "enum", "record", "new", "return", "if",
              "for", "while", "switch", "catch", "throw", "else", "do"}

PRIM = {"V": "void", "Z": "boolean", "B": "byte", "C": "char", "S": "short",
        "I": "int", "J": "long", "F": "float", "D": "double"}


def simple(t):
    return t.split("<")[0].strip().replace("[]", "").split(".")[-1]


def desc_return(desc):
    r = desc[desc.index(")") + 1:].lstrip("[")
    return r[1:-1].split("/")[-1].split("$")[-1] if r.startswith("L") else PRIM.get(r, r)


def stub_methods(path, top_internal):
    """[(internal_owner, return_type, method_name)] en suivant l'imbrication."""
    src = open(path, encoding="utf-8").read()
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)

    # Pile des classes ouvertes, par profondeur d'accolade.
    out, stack, depth, i = [], [], 0, 0
    pending, kinds = None, {}
    for m in re.finditer(r"[{}]|" + CLASS_DECL.pattern + r"|" + METHOD_DECL.pattern,
                         src, re.M):
        tok = m.group(0)
        if tok == "{":
            depth += 1
            if pending:
                stack.append((pending, depth)); pending = None
        elif tok == "}":
            if stack and stack[-1][1] == depth:
                stack.pop()
            depth -= 1
        else:
            cm = CLASS_DECL.match(tok)
            if cm:
                pending = cm.group(2)
                kinds[pending] = cm.group(1)
                continue
            mm = METHOD_DECL.match(tok if tok.startswith(" ") else "    " + tok)
            if mm:
                rtype, name = mm.group(1).strip(), mm.group(2)
                if rtype in NOT_A_TYPE or name in NOT_A_TYPE:
                    continue
                owner = top_internal
                if len(stack) > 1:
                    owner = top_internal + "$" + "$".join(n for n, _ in stack[1:])
                if name == stack[-1][0] if stack else False:
                    continue                                  # constructeur
                out.append((owner, rtype, name))
    return out, kinds


def main():
    game = Game(JARS)
    miss_name, bad_return, bad_kind, unverifiable = [], [], [], []

    for root, _, files in os.walk(STUBS):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            top = os.path.relpath(path, STUBS).replace("\\", "/")[:-5]
            real = game.load(top)
            if real is None:
                unverifiable.append(top)
                continue
            methods, kinds = stub_methods(path, top)
            declared_iface = kinds.get(top.split("/")[-1]) == "interface"
            if declared_iface != real["is_interface"]:
                bad_kind.append((top,
                                 "interface" if declared_iface else "class",
                                 "interface" if real["is_interface"] else "class"))
            for owner, rtype, name in methods:
                if game.load(owner) is None:
                    continue                # classe imbriquee absente du jar
                sigs = game.lookup(owner, name)
                if not sigs:
                    miss_name.append((owner, rtype, name))
                elif simple(rtype) not in [desc_return(d) for d in sigs]:
                    bad_return.append((owner, name, simple(rtype),
                                       sorted({desc_return(d) for d in sigs})))

    print("\n### A. METHODE INEXISTANTE dans la hierarchie reelle")
    for c, r, n in miss_name:
        print("   %-58s %s %s()" % (c, r, n))
    if not miss_name:
        print("   (aucune)")

    print("\n### B. TYPE DE RETOUR DIVERGENT")
    for c, n, stub, real in bad_return:
        print("   %-50s %-22s stub=%-14s reel=%s" % (c, n + "()", stub, ",".join(real)))
    if not bad_return:
        print("   (aucune)")

    print("\n### C. GENRE DIVERGENT (interface vs class -> IncompatibleClassChangeError)")
    for c, stub, real_kind in bad_kind:
        print("   %-56s stub=%-10s reel=%s" % (c, stub, real_kind))
    if not bad_kind:
        print("   (aucun)")

    print("\n### classes absentes des jars (stubs internes au projet, non verifiables) : %d"
          % len(unverifiable))
    for s in sorted(unverifiable):
        print("   " + s)

    return 1 if (miss_name or bad_return or bad_kind) else 0


if __name__ == "__main__":
    sys.exit(main())
