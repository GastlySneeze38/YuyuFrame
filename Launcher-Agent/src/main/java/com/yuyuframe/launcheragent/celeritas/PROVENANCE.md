# Celeritas — copie de l'amont

Base du renderer 1.8.9 (décision D5, `docs/LauncherAgent/v1.8.9/README.md`).

**Posé ici sans être branché** : rien dans l'agent ne l'appelle, et
`build.bat` exclut ce dossier de toutes ses passes de compilation.

## D'où ça vient

| | |
|---|---|
| Dépôt | https://github.com/GTNewHorizons/Celeritas (miroir de https://git.taumc.org/embeddedt/celeritas) |
| Branche | `nh-main` |
| Commit | `989ebf2e52ceed052ec112a569e0c1d172095756` (2026-09-27) |
| Copié le | 2026-10-06 |
| Licence | LGPL-3.0 — `COPYING` et `COPYING.LESSER`, à garder avec le code |

Les fichiers sont ceux de l'amont, **sans aucune modification** : mêmes
chemins, mêmes paquets Java (`org.embeddedt.embeddium`, `org.taumc.celeritas`,
`grondag.bitraster`). Les paquets ne correspondent donc pas à l'emplacement
du dossier — c'est voulu tant que rien n'est branché.

## Ce qui est là

- `common/` — le cœur du moteur, détaché de Minecraft (324 fichiers Java) :
  sections et régions, arènes mémoire GPU, lots de dessin, tri translucide,
  occlusion. Sources séparées par variante de LWJGL (`lwjgl2`, `lwjgl3`,
  `lwjglCommon`) et bancs d'essai (`jmh`).
- `ornithe/` — l'adaptateur des anciennes versions, dont la 1.8.9
  (38 fichiers Java) : mixins et rendu du terrain. Écrit pour le chargeur
  Ornithe et les mappings Feather ; à réécrire en Mixins Yarn legacy
  (décisions D2 et D4). Une seule arborescence de sources pour toutes les
  versions, adaptée par Stonecutter (`ornithe/versions/`).
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` — gardés
  pour la liste des dépendances et des versions. Le projet ne se construit pas
  d'ici : il manque `buildSrc/`, `plugins/` et l'enveloppe Gradle.
- `README.md` — celui de l'amont.

## Ce qui n'a pas été copié

`modern/` (1.13 et plus, 915 fichiers Java), `forge122/`, `forge1710/`,
`buildSrc/`, `plugins/`, `scripts/`, `idea/`, `mapping-patches/`, `.github/`,
`gradlew` : sans rapport avec la 1.8.9, ou outillage de construction.

## Pour mettre à jour

Reprendre `common/` et `ornithe/` du même dépôt, noter le nouveau commit ici.
Tant que les fichiers ne sont pas modifiés, une mise à jour est un simple
remplacement.
