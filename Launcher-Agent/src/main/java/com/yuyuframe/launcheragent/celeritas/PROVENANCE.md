# Celeritas — moteur repris de l'amont

Base du renderer 1.8.9 (décision D5, `docs/LauncherAgent/v1.8.9/README.md`).

**Rangé ici sans être branché** : rien dans l'agent ne l'appelle, et
`build.bat` écarte ce dossier de toutes ses passes de compilation.

## D'où ça vient

| | |
|---|---|
| Dépôt | https://github.com/GTNewHorizons/Celeritas (miroir de https://git.taumc.org/embeddedt/celeritas) |
| Branche | `nh-main` |
| Commit | `989ebf2e52ceed052ec112a569e0c1d172095756` (2026-09-27) |
| Repris le | 2026-10-06 |
| Licence | LGPL-3.0 — `COPYING` et `COPYING.LESSER`, à garder avec le code |

`raster/` vient de Canvas (grondag) par Celeritas : sa licence est dans
`raster/LICENSE`.

## Ce qui a été modifié par rapport à l'amont (2026-10-06)

La LGPL demande de signaler les modifications. Trois, toutes mécaniques — le
comportement du code n'a pas changé :

1. **Paquets renommés** pour suivre les dossiers (table ci-dessous), dans
   tous les fichiers : ligne `package`, imports, noms complets.
2. **Lombok retiré** de 18 fichiers : chaque annotation est remplacée par le
   code Java qu'elle produisait (accesseurs, constructeurs, et pour
   `TerrainRenderPass` son constructeur par étapes, `equals` et `hashCode`).
   Notre `build.bat` interdit les processeurs d'annotations.
3. **Classes `GL11` à `GL46` ajoutées** dans `gl/binding/`. L'amont les
   fabrique à la construction (`GenerateLWJGLAbstraction.kt`, dans son
   `buildSrc/`) ; ici elles sont produites une fois, depuis
   `org.lwjgl.opengl.GL*` de LWJGL 3.4.1, et rangées comme des fichiers
   ordinaires. Ce ne sont que des constantes.

### Table des paquets

Base : `com.yuyuframe.launcheragent.celeritas`. Les sous-paquets suivent.

| Amont | Ici |
|---|---|
| `org.embeddedt.embeddium.impl.gl` | `gl` |
| `org.taumc.celeritas.lwjgl` | `gl.binding` |
| `org.embeddedt.embeddium.impl.render.frame` | `gl.sync` |
| `org.embeddedt.embeddium.impl.render.chunk` | `chunk` |
| `org.embeddedt.embeddium.impl.render.terrain` | `chunk.terrain` |
| `org.embeddedt.embeddium.impl.biome` | `chunk.biome` |
| `org.embeddedt.embeddium.impl.model` | `model` |
| `org.embeddedt.embeddium.impl.render.viewport` | `viewport` |
| `org.embeddedt.embeddium.impl.render.shader`, `org.taumc.celeritas.shaders` | `shader` |
| `grondag.bitraster` | `raster` |
| `org.embeddedt.embeddium.impl.util`, `impl.common.util`, `impl.texture` | `util` |
| `org.embeddedt.embeddium.api.util` | `util.color` |
| `org.taumc.celeritas.impl` (adaptateur `ornithe`) | `v1_8_9` |
| `org.taumc.celeritas.impl.render.terrain` | `v1_8_9.terrain` |
| `org.taumc.celeritas.impl.render.entity` | `v1_8_9.entity` |
| `org.taumc.celeritas.impl.render.util` | `v1_8_9.util` |
| `org.taumc.celeritas.impl.world.cloned` | `v1_8_9.world` |
| `org.taumc.celeritas.impl.extensions` | `v1_8_9.extensions` |
| `org.taumc.celeritas.impl.debug` | `v1_8_9.debug` |
| `org.taumc.celeritas.mixin.core` | `v1_8_9.mixin` |

## Ce qui est là

| Dossier | Contenu |
|---|---|
| `gl/` | Objets OpenGL : tampons, arènes mémoire, shaders, états, tessellation |
| `gl/binding/` | Accès à LWJGL 3 (`LWJGLService`, `lwjgl3/`) et constantes `GL11`…`GL46`. Sans rapport avec `lwjgl2compat` |
| `chunk/` | Sections, régions, construction, listes de rendu, occlusion, tri translucide, lots de dessin |
| `model/` | Lumière et quads |
| `viewport/` | Caméra et frustum |
| `shader/` | Chargement des sources de shaders |
| `raster/` | Rastérisation d'occlusion |
| `util/` | Collections, tri, itérateurs, couleurs |
| `v1_8_9/` | L'adaptateur des anciennes versions, **pas encore réécrit** (voir plus bas) |

Les shaders sont dans `src/main/resources/assets/celeritas/shaders/` (chez
l'amont : `assets/sodium/shaders/`). Le chemin de chargement sera à faire
suivre au branchement.

## État de la compilation

- **Le moteur (tout sauf `v1_8_9/`) compile**, vérifié le 2026-10-06 avec le
  JDK 25 du launcher : 267 fichiers, aucune erreur. Il lui faut sur le
  classpath, en plus de LWJGL 3 : **JOML 1.10.5**, **fastutil 7.1.0**,
  **log4j-api** et les **annotations JetBrains** (compilation seulement).
  JOML et fastutil ne sont pas dans la 1.8.9 : à ajouter au build et au
  lancement quand on branchera.
- **`v1_8_9/` ne compile pas** et ne le peut pas en l'état : il est écrit pour
  le chargeur Ornithe, en noms Feather, avec des directives Stonecutter
  (`//? if >=1.8 {`) qui mêlent plusieurs versions du jeu dans les mêmes
  fichiers. C'est une référence à réécrire en Mixins Yarn legacy (décisions D2
  et D4) ; ses mixins iront alors dans `apimixin/v1_8_9/renderer/`.
  La correspondance des noms est dans `v1_8_9/FEATHER-YARN.md`, et les stubs
  Yarn legacy des 36 classes du jeu qu'il cite sont dans `src/stubs/v1_8_9`.

## Ce qui n'a pas été repris

- l'écran d'options de Sodium et son API (`impl/gui`, `org.taumc.celeritas.api`) ;
- la variante LWJGL 2 de la couche d'accès ;
- les bancs d'essai (`jmh`) et le test — à reprendre de l'amont à l'étape de mesure ;
- `ShaderModBridge` (pont vers Iris et Nvidium), `MixinClassValidator`,
  `ProxyClassGenerator`, le plugin Mixin et le point d'entrée Fabric d'`ornithe` ;
- `modern/`, `forge122/`, `forge1710/` et tout l'outillage Gradle.

## Restes connus, à traiter au branchement

- `gl/binding/LWJGLServiceProvider` choisit son service par `Class.forName`
  et réflexion : à remplacer par une construction directe (règle D4).
- Noms hérités dans le code : classes `Sodium…`, préfixe `Primitive…` de
  l'adaptateur, espace de ressources `sodium` dans les chemins de shaders.

## Pour mettre à jour depuis l'amont

Ce n'est plus un simple remplacement. Comparer l'amont entre le commit noté
ci-dessus et le nouveau, puis reporter les changements à la main en suivant la
table des paquets. Noter le nouveau commit ici.
