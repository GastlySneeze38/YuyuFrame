# Celeritas — moteur repris de l'amont

Base du renderer 1.8.9 (décision D5, `docs/LauncherAgent/v1.8.9/README.md`).

**Branché depuis le 2026-10-06 (build v1229), éteint par défaut, jamais
encore vu en jeu.** Il s'allume par le réglage « Renderer de terrain
(expérimental) » du module Optimisation, sur la 1.8.9 seulement.

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

La LGPL demande de signaler les modifications. Quatre, toutes mécaniques — le
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
4. **Journalisation par `LauncherLog`** au lieu de log4j, dans neuf fichiers
   (catégorie `RENDERER`, seuil `log.renderer`). Mêmes messages ; les
   avertissements et erreurs passent par `LauncherLog.warn` / `err`, donc
   toujours affichés. `shader/CeleritasShaders`, qui ne portait qu'un
   journal inutilisé, est supprimée.

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
| `v1_8_9/` | L'adaptateur 1.8.9, porté en noms Yarn legacy (voir « Comment c'est branché ») |
| `RendererSwitch.java` | L'interrupteur — notre code, pas celui de Celeritas |
| `v1_8_9/sky/CloudRenderer189.java` | Les nuages détaillés réécrits — notre code (2026-10-06) : maillage gardé sur la carte graphique, 2 dessins par image au lieu de 128 |

Notre code aussi, dans `v1_8_9/RendererHooks189` et actif seulement avec le
renderer (2026-10-06) : le ciel caché quand le brouillard est rapproché
(correctif repris de Sodium), et les particules hors champ non calculées.

Les shaders sont dans `src/main/resources/assets/celeritas/shaders/` (chez
l'amont : `assets/sodium/shaders/`). Le chemin de chargement sera à faire
suivre au branchement.

## Comment c'est branché

- **Compilation** — le moteur part avec la passe principale de `build.bat`,
  l'adaptateur `v1_8_9/` avec l'unité 1.8.9 (contre les stubs Yarn legacy).
  Bibliothèques : **JOML 1.10.5**, **fastutil 7.1.0** et les **annotations
  JetBrains** (ces dernières à la compilation seulement), déposées dans
  `lib/` par `build.bat`.
- **Lancement** — JOML et fastutil manquent à la 1.8.9 : le launcher les
  ajoute à ses bibliothèques (`Backend/…/legacy_lwjgl3_libraries.json`).
  Sans elles (launcher plus ancien que l'agent), `RendererSwitch.available()`
  est faux : le renderer reste éteint et aucune classe du moteur n'est touchée.
- **Adaptateur** — `v1_8_9/` est le portage de l'adaptateur `ornithe` de
  Celeritas, réduit à sa branche « 1.8 et plus » et réécrit en noms Yarn
  legacy (table : `v1_8_9/FEATHER-YARN.md`). Code typé, marqué `@YarnNamed`,
  traduit au chargement.
- **Mixins** — `apimixin/v1_8_9/renderer/`, onze fichiers, enregistrés dans
  `MixinHookPointRegistry` (toujours tissés) et dans la table de refmap de
  `LauncherMixinService`. Ils ne nomment aucun type du jeu : tout passe par
  `v1_8_9/RendererHooks189`.
- **Interrupteur** — `RendererSwitch` (notre code). Il ne change d'état qu'au
  rechargement des chunks, et une erreur du renderer le coupe et rend la main
  au rendu vanilla.

### Écarts voulus avec l'adaptateur d'amont

- **Aucun `@Overwrite`.** L'amont remplace cinq méthodes du `WorldRenderer`
  sans retour possible ; ici ce sont des injections en tête de méthode,
  annulées seulement quand le renderer est actif. C'est ce qui permet de
  l'éteindre en jeu.
- **Une instance unique** (`CeleritasWorldRenderer.INSTANCE`) au lieu d'un
  champ greffé sur le `WorldRenderer` : le jeu n'en a qu'un.
- **Position de caméra recalculée** dans `renderEntities` au lieu de capturer
  les variables locales de vanilla.
- **Liste des chunks visibles de vanilla vidée** à l'activation : son
  rechargement ne la vide pas, et vanilla dessinerait les entités en double.
- **Non portés** : le suivi des chunks du serveur intégré (inutile au client),
  l'optimisation de `TypeFilterableList.forEach`, les retouches de fenêtre de
  `MinecraftMixin` (notre couche LWJGL crée déjà la fenêtre), le suivi de
  transparence des textures (vide à partir de la 1.5).
- **Block entities soumis au culling** (2026-10-06) : l'amont les range tous
  dans la liste « globale », dessinée à chaque image quelle que soit la
  visibilité (mesuré : 1,48 ms par image contre 0,99 ms pour vanilla sur un
  lobby). Ici, seuls ceux dont le rendu déclare `rendersOutsideBoundingBox`
  (faisceau de balise) y vont ; les autres suivent la visibilité de leur section.
- **Entités soumises au culling d'occlusion** (2026-10-06) : une entité dont la
  boîte ne touche aucune section visible n'est pas dessinée — comme vanilla,
  qui ne parcourt que ses chunks visibles. Exceptions : l'entité de vue, celles
  marquées `ignoreCameraFrustum`, et la monture du joueur.
- **Culling par rastérisation allumé** (2026-10-06), éteint chez l'amont sur
  cette version : le maillage fournit les boîtes opaques de chaque section
  (`SectionVisibilityBuilder`, mêmes blocs opaques que le graphe de
  visibilité), et le volume de vue sa matrice projection × vue, ramenée de
  l'origine du rendu à l'œil. Règle de Sodium pour les entités : une entité
  dont le nom s'affiche (joueur, nom toujours visible) n'est jamais cachée
  par l'occlusion.
- **Test de frustum recalé sur l'origine du rendu** (2026-10-06) : le moteur
  donne des coordonnées autour de l'œil, les plans de vanilla sont autour des
  pieds de l'entité de vue. L'amont les passait telles quelles, d'où un test
  décalé de la hauteur des yeux (1,62 bloc).
- **Une borne corrigée** dans `populateTileEntities` (`>=` au lieu de `>`).

### Vérifié hors jeu, pas en jeu

- Tout compile avec le JDK 25 du launcher.
- Les 94 stubs 1.8.9 sont confrontés au vrai jar (existence, signature,
  statique, visibilité, classe ou interface) : aucun écart.
- Le code typé et les mixins du renderer (31 classes, 142 références au jeu) :
  chaque membre existe dans Yarn et dans le jar, chaque classe qui cite un
  type du jeu porte `@YarnNamed`, aucun mixin n'en cite.
- Les cibles des injections sont relevées dans le bytecode de `bfr`
  (`WorldRenderer`), dont les deux étapes du profileur « entities » et
  « blockentities ».

**Rien de tout cela ne dit que l'image est juste** : l'état OpenGL laissé au
jeu, la cohabitation avec notre interface, les entités et les block entities
ne se jugent qu'en jeu.

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
