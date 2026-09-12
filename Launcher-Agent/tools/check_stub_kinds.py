"""Contrôle de CONFORMITÉ des stubs 1.21.11 : classe vs interface.

Pourquoi cet outil existe
-------------------------
Un stub déclaré en CLASSE alors que le jeu en fait une INTERFACE fait émettre à
javac un ``invokevirtual`` là où la JVM attend un ``invokeinterface``. Rien ne
le signale :

* la compilation passe — le stub est cohérent avec lui-même ;
* le banc ``RemapCheck`` passe — le membre visé existe bien dans le jar ;
* le jeu lève un ``IncompatibleClassChangeError`` au PREMIER appel.

Symptôme vécu (v1095) : le biome ne se lisait jamais en 1.21.11, et les effets
de potion non plus, pour quatre stubs déclarés en classe (``WorldView``,
``RegistryEntry``, ``ComponentsAccess``, ``ResourceFactory``).

Ce que le contrôle fait
-----------------------
Pour chaque stub de ``src/stubs_1_21_11`` sous ``net/minecraft`` : résout son
nom Yarn vers le nom intermédiaire, lit les DRAPEAUX D'ACCÈS de la vraie classe
dans le jar que Fabric exécute, et compare au genre déclaré.

Les drapeaux sont lus directement dans le ``.class`` (pas de ``javap`` à
trouver), ce qui demande de sauter le pool de constantes — d'où le petit
décodeur ci-dessous.

Usage
-----
    python tools/check_stub_kinds.py <client-intermediary.jar> <mappings.tiny>

Sortie : une ligne par divergence, code de retour 1 s'il y en a.
"""

import io
import os
import struct
import sys
import zipfile

ACC_INTERFACE = 0x0200

# Tailles des entrées du pool de constantes, par tag (JVMS 4.4).
# Les tags 5 (Long) et 6 (Double) occupent DEUX entrées — piège classique.
_FIXED = {7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 15: 3,
          3: 4, 4: 4, 9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4,
          5: 8, 6: 8}


def is_interface(data):
    """Les drapeaux d'accès d'un .class, lus après le pool de constantes."""
    off = 8  # magic(4) + minor(2) + major(2)
    count = struct.unpack_from(">H", data, off)[0]
    off += 2
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8 : longueur variable
            off += 2 + struct.unpack_from(">H", data, off)[0]
        elif tag in _FIXED:
            off += _FIXED[tag]
            if tag in (5, 6):
                i += 1  # occupe deux entrées
        else:
            raise ValueError("tag de pool inconnu : %d" % tag)
        i += 1
    return bool(struct.unpack_from(">H", data, off)[0] & ACC_INTERFACE)


def yarn_to_intermediary(mappings_path):
    table = {}
    with io.open(mappings_path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("c\t"):
                parts = line.rstrip("\n").split("\t")
                table[parts[3]] = parts[2]
    return table


def main(jar_path, mappings_path, stubs_root):
    table = yarn_to_intermediary(mappings_path)
    jar = zipfile.ZipFile(jar_path)
    problems = 0
    checked = 0

    for dirpath, _, files in os.walk(stubs_root):
        for name in files:
            if not name.endswith(".java"):
                continue
            full = os.path.join(dirpath, name)
            yarn = os.path.relpath(full, stubs_root).replace("\\", "/")[:-5]
            # Hors net/minecraft : com.mojang.blaze3d (non obfusqué) et
            # brigadier n'ont pas d'entrée Yarn, et n'en ont pas besoin.
            if not yarn.startswith("net/minecraft"):
                continue
            intermediary = table.get(yarn)
            if intermediary is None:
                print("  PAS DANS YARN   %s" % yarn)
                problems += 1
                continue
            try:
                data = jar.read(intermediary + ".class")
            except KeyError:
                print("  PAS DANS LE JAR %s (%s)" % (yarn, intermediary))
                problems += 1
                continue

            source = io.open(full, encoding="utf-8").read()
            stub_is_interface = ("public interface " + name[:-5]) in source
            real_is_interface = is_interface(data)
            checked += 1
            if stub_is_interface != real_is_interface:
                print("  DIVERGENCE      %-52s stub=%-9s reel=%s" % (
                    yarn,
                    "interface" if stub_is_interface else "classe",
                    "interface" if real_is_interface else "classe"))
                problems += 1

    print("=> %d stub(s) verifie(s), %d probleme(s)" % (checked, problems))
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    root = sys.argv[3] if len(sys.argv) > 3 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "src", "stubs_1_21_11")
    sys.exit(main(sys.argv[1], sys.argv[2], root))
