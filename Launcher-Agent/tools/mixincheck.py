# -*- coding: utf-8 -*-
"""Confronte les CHAINES des annotations Mixin d'un dossier de version au vrai jar du jeu.

    python tools/mixincheck.py <client.jar> <dossier apimixin/vX_Y>

Pourquoi cet outil existe
-------------------------
`refcheck261.py` verifie le bytecode compile : il voit les appels, pas les
cibles d'un mixin, qui ne sont que des chaines dans les annotations
(`method = "..."`, `@At(target = "...")`, `@Accessor("...")`). Une chaine
fausse compile parfaitement ; en jeu, Mixin rejette l'injection — souvent
en silence avec `require = 0`. Ecrit le 2026-09-29 pour le portage 26.2,
ou l'ecart avec 26.1.2 touche ~2 600 classes.

Ce qui est verifie, dans la classe cible (et sa hierarchie quand Mixin la remonte) :
  * `@Mixin(targets = ...)` / `@Mixin(X.class)`      -> la classe existe ;
  * `method = "nom(desc)"` ou `method = "nom"`        -> methode DECLAREE dans la cible ;
  * `@At(target = "Lowner;nom(desc)")` / `nom:desc`   -> membre de owner (hierarchie) ;
  * `@Accessor("x")` / getter-setter sans valeur      -> champ declare dans la cible ;
  * `@Invoker("x")`, `@Shadow`                        -> methode/champ declare (nom seul).
Limites : les selecteurs regex/wildcard ne sont pas interpretes, et le
descripteur d'un @Invoker/@Shadow n'est pas compare (nom seulement).
"""
import os
import re
import struct
import sys
import zipfile


# ----------------------------------------------------------------- .class ---
def parse_class(data):
    off = 10
    count = struct.unpack_from(">H", data, 8)[0]
    utf8, classes = {}, {}
    i = 1
    while i < count:
        tag = data[off]; off += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, off)[0]
            utf8[i] = data[off + 2:off + 2 + n].decode("utf-8", "replace"); off += 2 + n
        elif tag == 7:
            classes[i] = struct.unpack_from(">H", data, off)[0]; off += 2
        elif tag in (9, 10, 11, 12, 3, 4, 17, 18):
            off += 4
        elif tag in (5, 6):
            off += 8; i += 1
        elif tag in (8, 16, 19, 20):
            off += 2
        elif tag == 15:
            off += 3
        else:
            raise ValueError("tag %d" % tag)
        i += 1

    def cname(ix):
        return utf8[classes[ix]] if ix else None

    access = struct.unpack_from(">H", data, off)[0]
    this, sup, n_if = struct.unpack_from(">HHH", data, off + 2); off += 8
    ifaces = [cname(struct.unpack_from(">H", data, off + 2 * k)[0]) for k in range(n_if)]
    off += 2 * n_if

    def members():
        nonlocal off
        out = []
        n = struct.unpack_from(">H", data, off)[0]; off += 2
        for _ in range(n):
            acc, name_ix, desc_ix, attrs = struct.unpack_from(">HHHH", data, off); off += 8
            for _ in range(attrs):
                ln = struct.unpack_from(">I", data, off + 2)[0]; off += 6 + ln
            out.append((utf8[name_ix], utf8[desc_ix], acc))
        return out

    fields = members()
    methods = members()
    return {"super": cname(sup), "ifaces": ifaces, "fields": fields, "methods": methods,
            "interface": bool(access & 0x0200)}


class Game:
    def __init__(self, jar):
        self.z = zipfile.ZipFile(jar)
        self.names = set(self.z.namelist())
        self.cache = {}

    def load(self, internal):
        if internal not in self.cache:
            path = internal + ".class"
            self.cache[internal] = parse_class(self.z.read(path)) if path in self.names else None
        return self.cache[internal]

    def hierarchy(self, internal):
        seen, todo = [], [internal]
        while todo:
            c = todo.pop(0)
            if not c or c in seen:
                continue
            seen.append(c)
            info = self.load(c)
            if info:
                todo.append(info["super"])
                todo.extend(info["ifaces"])
        return seen


# ------------------------------------------------------------- sources ---
COMMENTS = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
INJECTORS = ("Inject", "Redirect", "WrapOperation", "WrapWithCondition", "ModifyArg", "ModifyArgs",
             "ModifyVariable", "ModifyExpressionValue", "ModifyReturnValue", "ModifyConstant")


def annotation_body(text, start):
    """Texte entre les parentheses equilibrees qui suivent `start` (position du '(')."""
    depth, i = 0, start
    while i < len(text):
        c = text[i]
        if c == '"':
            i = text.index('"', i + 1)
            while text[i - 1] == "\\":
                i = text.index('"', i + 1)
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i], i
        i += 1
    return text[start + 1:], len(text)


def strings_of(expr):
    return [m.group(1) for m in STRING.finditer(expr)]


def key_strings(body, key):
    m = re.search(r"\b" + key + r"\s*=\s*(\{[^}]*\}|\"(?:[^\"\\]|\\.)*\")", body)
    return strings_of(m.group(1)) if m else []


def java_imports(text):
    return {m.group(2): m.group(1).replace(".", "/") + "/" + m.group(2)
            for m in re.finditer(r"^import\s+([\w.]+)\.(\w+)\s*;", text, re.M)}


def split_member(ref):
    """'Lowner;name(desc)' -> (owner, name, desc, kind) ; 'owner' seul -> classe."""
    m = re.match(r"^L([^;]+);([^(:]+)(\(.*|:.*)?$", ref)
    if not m:
        return (ref.strip("L;"), None, None, "class")
    owner, name, rest = m.groups()
    if rest and rest.startswith(":"):
        return (owner, name, rest[1:], "field")
    return (owner, name, rest, "method")


def check_folder(game, folder):
    problems, checked = [], 0

    def report(path, msg):
        problems.append("%s : %s" % (os.path.relpath(path, folder), msg))

    for root, _, files in os.walk(folder):
        for f in sorted(files):
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            raw = open(path, encoding="utf-8").read()
            text = COMMENTS.sub("", raw)
            imports = java_imports(text)
            m = re.search(r"@Mixin\s*\(", text)
            if not m:
                continue
            body, _ = annotation_body(text, m.end() - 1)
            targets = [s.replace(".", "/") for s in key_strings(body, "targets")]
            for cls in re.findall(r"(\w+)\.class", body):
                targets.append(imports.get(cls, cls))
            for t in targets:
                checked += 1
                if not game.load(t):
                    report(path, "CIBLE ABSENTE %s" % t)
            targets = [t for t in targets if game.load(t)]
            if not targets:
                continue

            def declared(kind, name, desc=None):
                for t in targets:
                    info = game.load(t)
                    for n, d, _ in info[kind]:
                        if n == name and (desc is None or d == desc):
                            return True
                return False

            # Selecteurs de methode des injecteurs + cibles @At.
            for inj in INJECTORS:
                for im in re.finditer(r"@" + inj + r"\s*\(", text):
                    ibody, _ = annotation_body(text, im.end() - 1)
                    for sel in key_strings(ibody, "method"):
                        checked += 1
                        name, _, desc = sel.partition("(")
                        desc = "(" + desc if desc else None
                        if "*" in name or name.startswith("^"):
                            continue
                        if not declared("methods", name, desc):
                            alt = sorted({n + d for t in targets for n, d, _ in game.load(t)["methods"] if n == name})
                            report(path, "@%s method=%s ABSENTE%s" % (
                                inj, sel, (" — existe : " + ", ".join(alt)) if alt else ""))
                    for tgt in key_strings(ibody, "target"):
                        checked += 1
                        owner, name, desc, kind = split_member(tgt)
                        chain = game.hierarchy(owner)
                        if not game.load(owner):
                            report(path, "@At target=%s : CLASSE ABSENTE %s" % (tgt, owner))
                            continue
                        if kind == "class":
                            continue
                        coll = "fields" if kind == "field" else "methods"
                        ok = any(n == name and (not desc or d == desc)
                                 for c in chain if game.load(c) for n, d, _ in game.load(c)[coll])
                        if not ok:
                            alt = sorted({c.split("/")[-1] + "." + n + d for c in chain if game.load(c)
                                          for n, d, _ in game.load(c)[coll] if n == name})
                            report(path, "@At target=%s ABSENT%s" % (
                                tgt, (" — existe : " + ", ".join(alt)) if alt else ""))

            # @Accessor / @Invoker / @Shadow : nom du membre.
            for am in re.finditer(r"@(Accessor|Invoker)\s*(\(\s*\"([^\"]+)\"[^)]*\))?[^;{]*?\b(\w+)\s*\(", text):
                kind, _, value, method_name = am.groups()
                name = value
                if not name:
                    mm = re.match(r"(?:get|set|is|call|invoke)([A-Z]\w*)", method_name)
                    name = (mm.group(1)[0].lower() + mm.group(1)[1:]) if mm else method_name
                checked += 1
                coll = "fields" if kind == "Accessor" else "methods"
                if not declared(coll, name):
                    report(path, "@%s(\"%s\") : %s ABSENT dans %s" % (
                        kind, name, "champ" if coll == "fields" else "methode", ", ".join(targets)))
            for sm in re.finditer(r"@Shadow\b[^;{]*?\b(\w+)\s*(\(|;|=)", text):
                name, what = sm.groups()
                name = re.sub(r"^la\$", "", name)
                checked += 1
                coll = "methods" if what == "(" else "fields"
                if not declared(coll, name):
                    report(path, "@Shadow %s : ABSENT dans %s" % (name, ", ".join(targets)))
    return checked, problems


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    game = Game(sys.argv[1])
    checked, problems = check_folder(game, sys.argv[2])
    for p in problems:
        print("  " + p)
    print("=> %d reference(s) verifiee(s), %d probleme(s)" % (checked, len(problems)))


if __name__ == "__main__":
    main()
