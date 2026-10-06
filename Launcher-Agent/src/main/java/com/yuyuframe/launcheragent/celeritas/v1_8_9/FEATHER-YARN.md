# Noms du jeu : Feather vers Yarn legacy (1.8.9)

L'adaptateur de Celeritas (ce dossier) est écrit en noms **Feather** (mappings d'Ornithe,
`feather-1.8.9+build.28`). Notre agent écrit en **Yarn legacy** (`yarn-1.8.9-mergedv2.jar`).
Cette table fait le pont, par le nom obfusqué que les deux partagent.

Produite par croisement des deux fichiers de mappings et du jar 1.8.9 — pas à la main.
À refaire si l'un des deux change.

## Classes (36)

| Feather | Obf. | Yarn legacy | Genre |
|---|---|---|---|
| `com.mojang.blaze3d.platform.Lighting` | `avc` | `net.minecraft.client.render.DiffuseLighting` | classe |
| `com.mojang.blaze3d.platform.MemoryTracker` | `avd` | `net.minecraft.client.util.GlAllocationUtils` | classe |
| `com.mojang.blaze3d.vertex.BufferBuilder` | `bfd` | `net.minecraft.client.render.BufferBuilder` | classe |
| `com.mojang.blaze3d.vertex.DefaultVertexFormat` | `bms` | `net.minecraft.client.render.VertexFormats` | classe |
| `net.minecraft.block.Block` | `afh` | `net.minecraft.block.Block` | classe |
| `net.minecraft.block.Blocks` | `afi` | `net.minecraft.block.Blocks` | classe |
| `net.minecraft.block.entity.BlockEntity` | `akw` | `net.minecraft.block.entity.BlockEntity` | classe abstraite |
| `net.minecraft.client.Minecraft` | `ave` | `net.minecraft.client.MinecraftClient` | classe |
| `net.minecraft.client.gui.GameGui` | `avo` | `net.minecraft.client.gui.hud.InGameHud` | classe |
| `net.minecraft.client.gui.overlay.DebugOverlay` | `avv` | `net.minecraft.client.gui.hud.DebugHud` | classe |
| `net.minecraft.client.options.GameOptions` | `avh` | `net.minecraft.client.option.GameOptions` | classe |
| `net.minecraft.client.render.Culler` | `bia` | `net.minecraft.client.render.CameraView` | interface |
| `net.minecraft.client.render.FrustumCuller` | `bic` | `net.minecraft.client.render.CullingCameraView` | classe |
| `net.minecraft.client.render.FrustumData` | `bid` | `net.minecraft.client.render.BaseFrustum` | classe |
| `net.minecraft.client.render.GameRenderer` | `bfk` | `net.minecraft.client.render.GameRenderer` | classe |
| `net.minecraft.client.render.TextRenderer` | `avn` | `net.minecraft.client.font.TextRenderer` | classe |
| `net.minecraft.client.render.Window` | `avr` | `net.minecraft.client.util.Window` | classe |
| `net.minecraft.client.render.block.BlockLayer` | `adf` | `net.minecraft.client.render.RenderLayer` | énumération |
| `net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher` | `bhc` | `net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher` | classe |
| `net.minecraft.client.render.entity.EntityRenderDispatcher` | `biu` | `net.minecraft.client.render.entity.EntityRenderDispatcher` | classe |
| `net.minecraft.client.render.world.WorldRenderer` | `bfr` | `net.minecraft.client.render.WorldRenderer` | classe |
| `net.minecraft.client.world.ClientWorld` | `bdb` | `net.minecraft.client.world.ClientWorld` | classe |
| `net.minecraft.client.world.chunk.ClientChunkCache` | `bcz` | `net.minecraft.world.chunk.ClientChunkProvider` | classe |
| `net.minecraft.entity.Entity` | `pk` | `net.minecraft.entity.Entity` | classe abstraite |
| `net.minecraft.entity.living.LivingEntity` | `pr` | `net.minecraft.entity.LivingEntity` | classe abstraite |
| `net.minecraft.entity.projectile.WitherSkullEntity` | `xd` | `net.minecraft.entity.projectile.WitherSkullEntity` | classe |
| `net.minecraft.server.world.ServerWorld` | `le` | `net.minecraft.server.world.ServerWorld` | classe |
| `net.minecraft.server.world.chunk.ServerChunkCache` | `ld` | `net.minecraft.world.chunk.ServerChunkProvider` | classe |
| `net.minecraft.text.Formatting` | `a` | `net.minecraft.util.Formatting` | énumération |
| `net.minecraft.util.TypeInstanceMultiMap` | `ne` | `net.minecraft.util.TypeFilterableList` | classe |
| `net.minecraft.util.math.BlockPos` | `cj` | `net.minecraft.util.math.BlockPos` | classe |
| `net.minecraft.util.math.MathHelper` | `ns` | `net.minecraft.util.math.MathHelper` | classe |
| `net.minecraft.util.profiler.Profiler` | `nt` | `net.minecraft.util.profiler.Profiler` | classe |
| `net.minecraft.world.World` | `adm` | `net.minecraft.world.World` | classe abstraite |
| `net.minecraft.world.WorldRegion` | `adv` | `net.minecraft.world.chunk.ChunkCache` | classe |
| `net.minecraft.world.chunk.WorldChunk` | `amy` | `net.minecraft.world.chunk.Chunk` | classe |

## Membres cités par l'adaptateur

Pour chaque classe : tout membre dont le **nom Feather** apparaît dans le code de l'adaptateur.
Un nom surchargé ramène toutes ses surcharges, et l'adaptateur mêle plusieurs versions du jeu :
la liste est donc large, à resserrer en réécrivant chaque fichier.

- **Accès** : seuls les membres *publics* sont dans les stubs. Un membre privé ou protégé
  s'atteint par `@Shadow` ou par un accessor Mixin.
- **`method_…`** : Yarn n'a pas nommé ce membre ; c'est son nom intermédiaire.
- **hérité** : le nom existe chez Feather mais le membre n'est pas déclaré dans cette classe.

### `DiffuseLighting` — `Lighting` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `turnOff` | `a` | `disable` | `() → void` | public, statique |

### `GlAllocationUtils` — `MemoryTracker` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `createFloatBuffer` | `h` | `allocateFloatBuffer` | `(int) → FloatBuffer` | public, statique |

### `BufferBuilder` — `BufferBuilder` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `clear` | `b` | `reset` | `() → void` | public |
| méthode | `getVertexCount` | `h` | `getVertexCount` | `() → int` | public |
| champ | `vertexCount` | `e` | `vertexCount` | `int` | privé |
| champ | `color` | `g` | `currentElementId` | `int` | privé |
| champ | `format` | `m` | `format` | `net.minecraft.client.render.VertexFormat` | privé |

### `VertexFormats` — `DefaultVertexFormat` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| champ | `BLOCK` | `a` | `BLOCK` | `net.minecraft.client.render.VertexFormat` | public, statique |

### `Block` — `Block` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `init` | `S` | `setup` | `() → void` | public, statique |
| méthode | `isOpaqueCube` | `c` | `hasTransparency` | `() → boolean` | public |
| méthode | `getRenderLayer` | `m` | `getRenderLayerType` | `() → RenderLayer` | public |
| méthode | `hasBlockEntity` | `z` | `hasBlockEntity` | `() → boolean` | public |
| champ | `hasBlockEntity` | `A` | `blockEntity` | `boolean` | protégé |
| champ | `minX` | `B` | `boundingBoxMinX` | `double` | protégé |
| champ | `minY` | `C` | `boundingBoxMinY` | `double` | protégé |
| champ | `minZ` | `D` | `boundingBoxMinZ` | `double` | protégé |
| champ | `maxX` | `E` | `boundingBoxMaxX` | `double` | protégé |
| champ | `maxY` | `F` | `boundingBoxMaxY` | `double` | protégé |
| champ | `maxZ` | `G` | `boundingBoxMaxZ` | `double` | protégé |

### `Blocks` — `Blocks` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| champ | `AIR` | `a` | `AIR` | `net.minecraft.block.Block` | public, statique |

### `BlockEntity` — `BlockEntity` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `getBlock` | `w` | `getBlock` | `() → Block` | public |
| méthode | `isRemoved` | `x` | `isRemoved` | `() → boolean` | public |
| champ | `world` | `b` | `world` | `net.minecraft.world.World` | protégé |
| champ | `block` | `e` | `block` | `net.minecraft.block.Block` | protégé |

### `MinecraftClient` — `Minecraft` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `getInstance` | `A` | `getInstance` | `() → MinecraftClient` | public, statique |
| méthode | `setWorld` | `a` | `connect` | `(ClientWorld) → void` | public |
| méthode | `setWorld` | `a` | `connect` | `(ClientWorld, String) → void` | public |
| méthode | `getCamera` | `ac` | `getCameraEntity` | `() → Entity` | public |
| méthode | `getBlockRenderDispatcher` | `ae` | `getBlockRenderManager` | `() → BlockRenderManager` | public |
| méthode | `getEntityRenderDispatcher` | `af` | `getEntityRenderManager` | `() → EntityRenderDispatcher` | public |
| méthode | `init` | `am` | `initializeGame` | `() → void` | privé |
| méthode | `initDisplay` | `ap` | `setPixelFormat` | `() → void` | privé |
| champ | `INSTANCE` | `S` | `instance` | `net.minecraft.client.MinecraftClient` | privé, statique |
| champ | `tasks` | `aM` | `tasks` | `java.util.Queue` | privé |
| champ | `entityRenderDispatcher` | `aa` | `entityRenderDispatcher` | `net.minecraft.client.render.entity.EntityRenderDispatcher` | privé |
| champ | `camera` | `ad` | `cameraEntity` | `net.minecraft.entity.Entity` | privé |
| champ | `world` | `f` | `world` | `net.minecraft.client.world.ClientWorld` | public |
| champ | `player` | `h` | `player` | `net.minecraft.entity.player.ClientPlayerEntity` | public |
| champ | `gameRenderer` | `o` | `gameRenderer` | `net.minecraft.client.render.GameRenderer` | public |
| champ | `options` | `t` | `options` | `net.minecraft.client.option.GameOptions` | public |

### `InGameHud` — `GameGui` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `render` | `a` | `render` | `(float) → void` | public |
| champ | `minecraft` | `j` | `client` | `net.minecraft.client.MinecraftClient` | privé |

### `DebugHud` — `DebugOverlay` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `getSystemInfo` | `c` | `getRightText` | `() → List` | protégé |
| champ | `minecraft` | `a` | `client` | `net.minecraft.client.MinecraftClient` | privé |

### `GameOptions` — `GameOptions` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| champ | `minecraft` | `ay` | `client` | `net.minecraft.client.MinecraftClient` | protégé |
| champ | `fpsLimit` | `g` | `maxFramerate` | `int` | public |
| champ | `fancyGraphics` | `i` | `fancyGraphics` | `boolean` | public |
| champ | `vsync` | `t` | `vsync` | `boolean` | public |

### `CameraView` — `Culler` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `set` | `a` | `setPos` | `(double, double, double) → void` | public |

### `CullingCameraView` — `FrustumCuller` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| champ | `frustum` | `a` | `clipper` | `net.minecraft.client.render.BaseFrustum` | privé |
| champ | `x` | `b` | `x` | `double` | privé |
| champ | `y` | `c` | `y` | `double` | privé |
| champ | `z` | `d` | `z` | `double` | privé |

### `BaseFrustum` — `FrustumData` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `contains` | `b` | `isInFrustum` | `(double, double, double, double, double, double) → boolean` | public |
| champ | `frustum` | `a` | `homogeneousCoordinates` | `float[][]` | public |
| champ | `modelMatrix` | `c` | `modelMatrix` | `float[]` | public |

### `GameRenderer` — `GameRenderer` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `render` | `a` | `render` | `(float, long) → void` | public |
| méthode | `render` | `a` | `renderWorld` | `(int, float, long) → void` | privé |
| méthode | `renderWorld` | `b` | `renderWorld` | `(float, long) → void` | public |
| méthode | `setupFog` | `i` | `updateFog` | `(float) → void` | privé |
| champ | `fogRed` | `Q` | `fogRed` | `float` | privé |
| champ | `fogGreen` | `R` | `fogGreen` | `float` | privé |
| champ | `fogBlue` | `S` | `fogBlue` | `float` | privé |
| champ | `minecraft` | `h` | `client` | `net.minecraft.client.MinecraftClient` | privé |

### `TextRenderer` — `TextRenderer` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `getWidth` | `a` | `getCharWidth` | `(char) → int` | public |
| méthode | `getWidth` | `a` | `getStringWidth` | `(String) → int` | public |
| méthode | `drawWithShadow` | `a` | `drawWithShadow` | `(String, float, float, int) → int` | public |

### `Window` — `Window` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `getWidth` | `a` | `getWidth` | `() → int` | public |
| champ | `width` | `c` | `width` | `int` | privé |

### `RenderLayer` — `BlockLayer` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| champ | `SOLID` | `a` | `SOLID` | `net.minecraft.client.render.RenderLayer` | public, statique |
| champ | `CUTOUT_MIPPED` | `b` | `CUTOUT_MIPPED` | `net.minecraft.client.render.RenderLayer` | public, statique |
| champ | `CUTOUT` | `c` | `CUTOUT` | `net.minecraft.client.render.RenderLayer` | public, statique |
| champ | `TRANSLUCENT` | `d` | `TRANSLUCENT` | `net.minecraft.client.render.RenderLayer` | public, statique |

### `BlockEntityRenderDispatcher` — `BlockEntityRenderDispatcher` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `render` | `a` | `renderBlockEntity` | `(BlockEntity, double, double, double, float) → void` | public |
| méthode | `render` | `a` | `renderEntity` | `(BlockEntity, double, double, double, float, int) → void` | public |
| méthode | `render` | `a` | `renderEntity` | `(BlockEntity, float, int) → void` | public |
| méthode | `getRenderer` | `a` | `render` | `(Class) → BlockEntityRenderer` | public |
| méthode | `getRenderer` | `b` | `getRenderer` | `(BlockEntity) → BlockEntityRenderer` | public |
| champ | `INSTANCE` | `a` | `INSTANCE` | `net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher` | public, statique |
| champ | `world` | `f` | `world` | `net.minecraft.world.World` | public |
| champ | `camera` | `g` | `entity` | `net.minecraft.entity.Entity` | public |

### `EntityRenderDispatcher` — `EntityRenderDispatcher` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `setWorld` | `a` | `setWorld` | `(World) → void` | public |
| méthode | `render` | `a` | `render` | `(Entity, double, double, double, float, float) → boolean` | public |
| méthode | `render` | `a` | `method_6913` | `(Entity, double, double, double, float, float, boolean) → boolean` | public |
| méthode | `renderSecondPass` | `a` | `renderEntity` | `(Entity, float) → boolean` | public |
| méthode | `render` | `a` | `method_6915` | `(Entity, float, boolean) → boolean` | public |
| méthode | `shouldRender` | `a` | `shouldRender` | `(Entity, CameraView, double, double, double) → boolean` | public |
| méthode | `renderNameTag` | `b` | `method_10204` | `(Entity, float) → void` | public |
| champ | `world` | `b` | `world` | `net.minecraft.world.World` | public |
| champ | `options` | `g` | `options` | `net.minecraft.client.option.GameOptions` | public |

### `WorldRenderer` — `WorldRenderer` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `reload` | `a` | `reload` | `() → void` | public |
| méthode | `compileChunksUntil` | `a` | `updateChunks` | `(long) → void` | public |
| méthode | `render` | `a` | `renderLayer` | `(RenderLayer) → void` | privé |
| méthode | `render` | `a` | `renderLayer` | `(RenderLayer, double, int, Entity) → int` | public |
| méthode | `setWorld` | `a` | `setWorld` | `(ClientWorld) → void` | public |
| méthode | `setupRender` | `a` | `setupTerrain` | `(Entity, double, CameraView, int, boolean) → void` | public |
| méthode | `renderEntities` | `a` | `renderEntities` | `(Entity, CameraView, float) → void` | public |
| méthode | `markDirty` | `b` | `updateBlock` | `(int, int, int, int, int, int) → void` | privé |
| méthode | `getChunkDebugInfo` | `f` | `getChunksDebugString` | `() → String` | public |
| méthode | `clear` | `k` | `cleanUp` | `() → void` | public |
| champ | `viewDistance` | `P` | `renderDistance` | `int` | privé |
| champ | `globalEntityCount` | `S` | `hiddenEntityCount` | `int` | privé |
| champ | `minecraft` | `h` | `client` | `net.minecraft.client.MinecraftClient` | privé |
| champ | `entityRenderDispatcher` | `j` | `entityRenderDispatcher` | `net.minecraft.client.render.entity.EntityRenderDispatcher` | privé |
| champ | `world` | `k` | `world` | `net.minecraft.client.world.ClientWorld` | privé |
| champ | `globalBlockEntities` | `n` | `noCullingBlockEntities` | `java.util.Set` | privé |
| champ | `ticks` | `w` | `ticks` | `int` | privé |

### `ClientWorld` — `ClientWorld` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| champ | `minecraft` | `I` | `client` | `net.minecraft.client.MinecraftClient` | privé |

### `ClientChunkProvider` — `ClientChunkCache` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `unloadChunk` | `b` | `unloadChunk` | `(int, int) → void` | public |
| méthode | `generateChunk` | `c` | `getOrGenerateChunk` | `(int, int) → Chunk` | public |
| champ | `chunks` | `d` | `chunks` | `java.util.List` | privé |
| champ | `world` | `e` | `world` | `net.minecraft.world.World` | privé |

### `Entity` — `Entity` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `setWorld` | `a` | `setWorld` | `(World) → void` | public |
| méthode | `getEyeHeight` | `aS` | `getEyeHeight` | `() → float` | public |
| champ | `prevTickX` | `P` | `prevTickX` | `double` | public |
| champ | `prevTickY` | `Q` | `prevTickY` | `double` | public |
| champ | `prevTickZ` | `R` | `prevTickZ` | `double` | public |
| champ | `noClip` | `T` | `noClip` | `boolean` | public |
| champ | `rider` | `l` | `rider` | `net.minecraft.entity.Entity` | public |
| champ | `world` | `o` | `world` | `net.minecraft.world.World` | public |
| champ | `x` | `s` | `x` | `double` | public |
| champ | `y` | `t` | `y` | `double` | public |
| champ | `z` | `u` | `z` | `double` | public |
| champ | `yaw` | `y` | `yaw` | `float` | public |
| champ | `pitch` | `z` | `pitch` | `float` | public |

### `LivingEntity` — `LivingEntity` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `isSleeping` | `bJ` | `isSleeping` | `() → boolean` | public |

### `ServerWorld` — `ServerWorld` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| champ | `server` | `I` | `server` | `net.minecraft.server.MinecraftServer` | privé |

### `ServerChunkProvider` — `ServerChunkCache` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `generateChunk` | `c` | `getOrGenerateChunk` | `(int, int) → Chunk` | public |
| méthode | `loadChunk` | `e` | `loadChunk` | `(int, int) → Chunk` | privé |
| champ | `world` | `i` | `world` | `net.minecraft.server.world.ServerWorld` | privé |

### `Formatting` — `Formatting` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| champ | `GREEN` | `k` | `GREEN` | `net.minecraft.util.Formatting` | public, statique |

### `TypeFilterableList` — `TypeInstanceMultiMap` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `add` | `a` | `method_10803` | `(Object, Class) → void` | privé |
| méthode | `add` | `add` | `add` | `(Object) → boolean` | public |
| champ | `instances` | `e` | `allElements` | `java.util.List` | privé |

### `BlockPos` — `BlockPos` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `add` | `a` | `add` | `(double, double, double) → BlockPos` | public |
| méthode | `add` | `a` | `add` | `(int, int, int) → BlockPos` | public |
| méthode | `offset` | `a` | `offset` | `(Direction) → BlockPos` | public |
| méthode | `offset` | `a` | `offset` | `(Direction, int) → BlockPos` | public |
| méthode | `add` | `a` | `add` | `(Vec3i) → BlockPos` | public |

### `MathHelper` — `MathHelper` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `floor` | `c` | `floor` | `(double) → int` | public, statique |
| méthode | `floor` | `d` | `floor` | `(float) → int` | public, statique |

### `Profiler` — `Profiler` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `swap` | `c` | `swap` | `(String) → void` | public |

### `World` — `World` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `getHeight` | `U` | `getMaxBuildHeight` | `() → int` | public |
| méthode | `getChunkAt` | `a` | `getChunk` | `(int, int) → Chunk` | public |
| méthode | `init` | `a` | `setPropertiesInitialized` | `(LevelInfo) → void` | public |
| méthode | `isChunkLoaded` | `a` | `isLoaded` | `(BlockPos, boolean) → boolean` | public |
| méthode | `init` | `b` | `getWorld` | `() → World` | public |
| méthode | `tick` | `c` | `tick` | `() → void` | public |
| méthode | `isChunkLoaded` | `e` | `blockExists` | `(BlockPos) → boolean` | public |
| méthode | `getHeight` | `m` | `getHighestBlock` | `(BlockPos) → BlockPos` | public |
| méthode | `getBlockEntity` | `m_8048103` | (sans nom Yarn) | `(BlockPos) → BlockEntity` | hérité |
| champ | `data` | `x` | `levelProperties` | `net.minecraft.world.level.LevelProperties` | protégé |

### `ChunkCache` — `WorldRegion` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `isEmpty` | `m_5923188` | (sans nom Yarn) | `() → boolean` | hérité |
| champ | `world` | `e` | `world` | `net.minecraft.world.World` | protégé |

### `Chunk` — `WorldChunk` chez Feather

| | Feather | Obf. | Yarn legacy | Signature (Yarn) | Accès |
|---|---|---|---|---|---|
| méthode | `getBlock` | `a` | `method_9131` | `(int, int, int) → Block` | public |
| méthode | `getBlock` | `a` | `getBlockAtPos` | `(BlockPos) → Block` | public |
| méthode | `getBlockEntity` | `a` | `getBlockEntity` | `(BlockPos, CStatus) → BlockEntity` | public |
| méthode | `getEntities` | `a` | `method_9140` | `(Class, Box, List, Predicate) → void` | public |
| méthode | `getEntities` | `a` | `method_9141` | `(Entity, Box, List, Predicate) → void` | public |
| méthode | `getHeight` | `b` | `getHighestBlockY` | `(int, int) → int` | public |
| méthode | `tick` | `b` | `populateBlockEntities` | `(boolean) → void` | public |
| méthode | `isEmpty` | `c` | `areSectionsEmptyBetween` | `(int, int) → boolean` | public |
| méthode | `unload` | `d` | `unloadFromWorld` | `() → void` | public |
| méthode | `isEmpty` | `f` | `isEmpty` | `() → boolean` | public |
| méthode | `getBlockAt` | `f` | `getBlock` | `(int, int, int) → Block` | privé |
| méthode | `getHeight` | `f` | `getHighestBlockY` | `(BlockPos) → int` | public |
| méthode | `getBlockState` | `g` | `method_9154` | `(BlockPos) → BlockState` | public |
| méthode | `getSections` | `h` | `getBlockStorage` | `() → ChunkSection[]` | public |
| méthode | `getEntities` | `s` | `getEntities` | `() → TypeFilterableList[]` | public |
| champ | `chunkX` | `a` | `chunkX` | `int` | public |
| champ | `chunkZ` | `b` | `chunkZ` | `int` | public |
| champ | `sections` | `d` | `chunkSections` | `net.minecraft.world.chunk.ChunkSection[]` | privé |
| champ | `loaded` | `h` | `loaded` | `boolean` | privé |
| champ | `world` | `i` | `world` | `net.minecraft.world.World` | privé |
| champ | `entities` | `m` | `entities` | `net.minecraft.util.TypeFilterableList[]` | privé |

