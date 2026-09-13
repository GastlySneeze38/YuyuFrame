"""Contrôle de CONFORMITÉ des stubs 1.21.11 face au jar réellement exécuté.

Deux contrôles, pour deux pièges vécus — tous deux INVISIBLES à la compilation
ET au banc ``RemapCheck`` :

1. **Genre déclaré** (classe vs interface). Un stub en CLASSE dont le type réel
   est une INTERFACE fait émettre à javac un ``invokevirtual`` là où la JVM
   attend un ``invokeinterface`` → ``IncompatibleClassChangeError`` au premier
   appel. Vécu en v1095 sur ``WorldView`` (le biome ne se lisait jamais),
   ``RegistryEntry`` (effets de potion), ``ComponentsAccess`` et
   ``ResourceFactory``.

2. **Visibilité des méthodes déclarées.** Une méthode de stub dont la cible
   réelle est PRIVÉE se traduit correctement puis lève un
   ``IllegalAccessError``. Vécu en v1095 sur ``ChatHud.refresh()`` — privée
   dans le jeu, son équivalent public s'appelant ``reset()`` : la fusion des
   messages répétés échouait à mi-chemin, se relançait à chaque passe
   (compteurs jusqu'à x5 pour deux envois) et déclenchait un auto-ping.

Pourquoi ni javac ni le banc ne voient ces deux-là : le premier ne compare le
stub qu'à lui-même, le second vérifie que le membre EXISTE — pas la façon dont
on l'appelle, ni le droit de l'appeler.

Usage
-----
    python tools/check_stub_kinds.py <client-intermediary.jar> <mappings.tiny> [racine_stubs]

Sortie : une ligne par problème, code de retour 1 s'il y en a.
"""

import io
import os
import re
import struct
import sys
import zipfile

ACC_INTERFACE = 0x0200
ACC_PRIVATE = 0x0002

# Tailles des entrées du pool de constantes, par tag (JVMS 4.4).
# Les tags 5 (Long) et 6 (Double) occupent DEUX entrées — piège classique.
_FIXED = {7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 15: 3,
          3: 4, 4: 4, 9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4,
          5: 8, 6: 8}

# Méthodes qu'un stub déclare légitimement sans que Yarn les connaisse : elles
# viennent d'une bibliothèque externe NON obfusquée, que le remappeur laisse
# telle quelle à juste titre.
_HORS_YARN = {("net/minecraft/text/Text", "getString")}

_SIGNATURE = re.compile(
    r"^\s*(?:public|protected)\s+(?:static\s+)?(?:final\s+)?[\w.$<>\[\], ?]+\s+(\w+)\s*\(")


def _read_pool(data):
    """(table des chaînes utf8 du pool, offset juste après le pool)."""
    off = 8  # magic(4) + minor(2) + major(2)
    count = struct.unpack_from(">H", data, off)[0]
    off += 2
    utf8 = {}
    index = 1
    while index < count:
        tag = data[off]
        off += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, off)[0]
            utf8[index] = data[off + 2:off + 2 + length].decode("utf-8", "replace")
            off += 2 + length
        elif tag in _FIXED:
            off += _FIXED[tag]
            if tag in (5, 6):
                index += 1
        else:
            raise ValueError("tag de pool inconnu : %d" % tag)
        index += 1
    return utf8, off


def read_class(data):
    """(est_interface, {nom de méthode: [drapeaux d'accès]})."""
    utf8, off = _read_pool(data)
    class_flags = struct.unpack_from(">H", data, off)[0]
    off += 6  # access_flags, this_class, super_class
    off += 2 + 2 * struct.unpack_from(">H", data, off)[0]  # interfaces

    def members():
        nonlocal off
        found = {}
        count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(count):
            flags, name_index = struct.unpack_from(">HH", data, off)
            off += 6  # access_flags, name_index, descriptor_index
            attr_count = struct.unpack_from(">H", data, off)[0]
            off += 2
            for _ in range(attr_count):
                length = struct.unpack_from(">I", data, off + 2)[0]
                off += 6 + length
            found.setdefault(utf8.get(name_index, ""), []).append(flags)
        return found

    members()  # champs : sautés
    return bool(class_flags & ACC_INTERFACE), members()


def load_mappings(path):
    """(classe Yarn → intermédiaire, (classe Yarn, méthode Yarn) → {intermédiaires})."""
    classes = {}
    methods = {}
    current = None
    with io.open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("c\t"):
                parts = line.rstrip("\n").split("\t")
                classes[parts[3]] = parts[2]
                current = parts[3]
            elif current and line.startswith("\tm\t"):
                parts = line.rstrip("\n").split("\t")
                methods.setdefault((current, parts[5]), set()).add(parts[4])
    return classes, methods


def declared_methods(source):
    """Méthodes déclarées par le stub (constructeurs exclus par la casse)."""
    names = set()
    for line in source.splitlines():
        match = _SIGNATURE.match(line)
        if match:
            names.add(match.group(1))
    return names


def main(jar_path, mappings_path, stubs_root):
    classes, methods = load_mappings(mappings_path)
    jar = zipfile.ZipFile(jar_path)
    problems = 0
    checked_types = 0
    checked_methods = 0

    for dirpath, _, files in os.walk(stubs_root):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            full = os.path.join(dirpath, name)
            yarn = os.path.relpath(full, stubs_root).replace("\\", "/")[:-5]
            # com.mojang.blaze3d et brigadier ne sont pas obfusqués : aucune
            # entrée Yarn, et aucun besoin d'en avoir.
            if not yarn.startswith("net/minecraft"):
                continue

            intermediary = classes.get(yarn)
            if intermediary is None:
                print("  PAS DANS YARN    %s" % yarn)
                problems += 1
                continue
            try:
                data = jar.read(intermediary + ".class")
            except KeyError:
                print("  PAS DANS LE JAR  %s (%s)" % (yarn, intermediary))
                problems += 1
                continue

            source = io.open(full, encoding="utf-8").read()
            real_interface, real_methods = read_class(data)
            checked_types += 1

            stub_interface = ("public interface " + name[:-5]) in source
            if stub_interface != real_interface:
                print("  GENRE            %-46s stub=%-9s reel=%s" % (
                    yarn,
                    "interface" if stub_interface else "classe",
                    "interface" if real_interface else "classe"))
                problems += 1

            for method in sorted(declared_methods(source)):
                if (yarn, method) in _HORS_YARN:
                    continue
                targets = methods.get((yarn, method))
                if not targets:
                    # Non déclarée par CETTE classe dans Yarn : soit elle vient
                    # d'une parente (le stub reproduit alors la hiérarchie
                    # ailleurs, et l'appel portera le bon propriétaire), soit
                    # c'est un nom inventé — que le banc RemapCheck, lui,
                    # attrape sur les références réelles.
                    continue
                checked_methods += 1
                # Signalée seulement si TOUTES les cibles de ce nom sont
                # privées. Sans cette nuance, une surcharge privée suffirait à
                # accuser un stub qui vise la surcharge publique — le cas
                # d'addMessage, qui en a trois dont une seule privée. Le prix
                # est une précision moindre (on ne distingue pas les
                # surcharges par descripteur), mais un outil qui crie au loup
                # cesse d'être lu.
                seen = [flags
                        for target in targets
                        for flags in real_methods.get(target, [])]
                if seen and all(flags & ACC_PRIVATE for flags in seen):
                    print("  METHODE PRIVEE   %s.%s -> %s"
                          % (yarn, method, ", ".join(sorted(targets))))
                    problems += 1

    print("=> %d type(s) et %d methode(s) verifie(s), %d probleme(s)"
          % (checked_types, checked_methods, problems))
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    root = sys.argv[3] if len(sys.argv) > 3 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "src", "stubs", "v1_21_11")
    sys.exit(main(sys.argv[1], sys.argv[2], root))
