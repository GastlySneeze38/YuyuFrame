package com.yuyuframe.launcheragent.celeritas.v1_8_9;

import net.minecraft.block.material.Material;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.sky.CloudRenderer189;
import net.minecraft.client.render.BaseFrustum;
import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer.WorldRendererAccessor189;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.celeritas.chunk.map.ChunkStatus;
import com.yuyuframe.launcheragent.celeritas.chunk.map.ChunkTrackerHolder;
import com.yuyuframe.launcheragent.celeritas.gl.device.RenderDevice;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.entity.EntityGatherer;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.entity.LabelBatch189;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.CeleritasWorldRenderer;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.fog.GLStateManagerFogService;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.matrix.PrimitiveChunkMatrixGetter;
import com.yuyuframe.launcheragent.celeritas.viewport.Viewport;
import com.yuyuframe.launcheragent.celeritas.viewport.ViewportProvider;
import com.yuyuframe.launcheragent.celeritas.chunk.ChunkRenderMatrices;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.List;

/**
 * Le pont entre les mixins de {@code apimixin/v1_8_9/renderer} et le renderer.
 *
 * <p>Les mixins ne nomment aucun type du jeu : ils passent leurs objets en
 * {@code Object}, et c'est ici — code typé, traduit au chargement
 * ({@link YarnNamed}) — qu'ils retrouvent leur type.
 *
 * <p>Chaque méthode est le portage d'une injection de l'adaptateur
 * {@code ornithe} de Celeritas ({@code WorldRendererMixin}, branche « 1.8 et
 * plus »). Écart voulu : rien n'est remplacé d'office. Tant que
 * {@link RendererSwitch#active()} est faux, les mixins rendent la main à
 * vanilla sans appeler ces méthodes.
 */
@YarnNamed
public final class RendererHooks189 {

    private RendererHooks189() {
    }

    private static final EntityGatherer ENTITY_GATHERER = new EntityGatherer();

    private static int frame;

    private static boolean failureReported;

    /**
     * La distance de rendu au dernier rechargement, tenue ici et non lue dans
     * le {@code WorldRenderer} : son champ ({@code bfr.P}) a un homonyme à la
     * casse près ({@code bfr.p}, même type), et un {@code @Accessor} de Mixin
     * retrouve sa cible sans tenir compte de la casse — il lisait {@code p}.
     * La garde voyait donc une différence à chaque image et rechargeait tout.
     */
    private static int reloadedViewDistance = -1;

    /** Combien de temps la distance de rendu doit rester la même avant qu'on recharge. */
    private static final long VIEW_DISTANCE_SETTLE_NANOS = 400_000_000L;
    private static int pendingViewDistance = -1;
    private static long pendingViewDistanceSince;

    /**
     * La distance de rendu sur laquelle le renderer doit tourner : celle des
     * options, mais seulement une fois qu'elle a cessé de bouger.
     *
     * <p>Faire glisser le curseur de 12 à 32 fait passer l'option par toutes
     * les valeurs intermédiaires, une par image ou presque. Recharger à chacune
     * (ce que fait vanilla, et ce que nous faisions) arrête et relance les
     * fils de construction et jette tout le terrain une vingtaine de fois de
     * suite. On garde donc l'ancienne distance tant que la nouvelle n'a pas
     * tenu 0,4 seconde, et on ne recharge qu'une fois.
     */
    public static int settledViewDistance() {
        int wanted = MinecraftClient.getInstance().options.viewDistance;
        if (reloadedViewDistance < 0 || wanted == reloadedViewDistance) {
            pendingViewDistance = -1;
            return wanted;
        }
        long now = System.nanoTime();
        if (wanted != pendingViewDistance) {
            pendingViewDistance = wanted;
            pendingViewDistanceSince = now;
        }
        return now - pendingViewDistanceSince >= VIEW_DISTANCE_SETTLE_NANOS ? wanted : reloadedViewDistance;
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    /**
     * Fin de {@code WorldRenderer.reload()} : seul moment où l'on change de
     * renderer. Vanilla vient de jeter tous ses chunks ; on attache ou on
     * détache le nôtre selon ce que demande le module.
     */
    public static void onReload(Object worldRenderer) {
        // Sans les bibliothèques du moteur, aucune de ses classes ne doit être touchée.
        if (!RendererSwitch.available()) {
            return;
        }

        boolean wanted = RendererSwitch.wanted();
        RendererSwitch.latch(wanted);
        reloadedViewDistance = MinecraftClient.getInstance().options.viewDistance;
        // Vanilla vient de reposer ce qui dépend des réglages graphiques (feuilles opaques ou non).
        com.yuyuframe.launcheragent.celeritas.v1_8_9.world.BlockFlags189.invalidate();

        if (wanted) {
            // reload() ne vide pas la liste des chunks visibles de vanilla, qui ne
            // sera plus recalculée : sans ça, vanilla continuerait de dessiner les
            // entités de ces chunks, en double des nôtres.
            ((WorldRendererAccessor189) worldRenderer).la$visibleChunks().clear();
        }

        RenderDevice.enterManagedCode();
        try {
            CeleritasWorldRenderer renderer = CeleritasWorldRenderer.INSTANCE;
            World world = wanted ? MinecraftClient.getInstance().world : null;

            if (renderer.attachedWorld() != world) {
                renderer.setWorld(world);
            } else if (world != null) {
                renderer.reload();
            }
        } catch (Throwable t) {
            disableAfterFailure("rechargement", t);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /** Fin de {@code WorldRenderer.setWorld(world)} — {@code world} vaut {@code null} en quittant une partie. */
    public static void onWorldChanged(Object world) {
        if (!RendererSwitch.active()) {
            return;
        }

        RenderDevice.enterManagedCode();
        try {
            CeleritasWorldRenderer.INSTANCE.setWorld((World) world);
        } catch (Throwable t) {
            disableAfterFailure("changement de monde", t);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /**
     * Une erreur du renderer ne doit pas emporter le jeu : on le coupe, et le
     * prochain rechargement des chunks (demandé ici) rend la main à vanilla.
     */
    private static void disableAfterFailure(String step, Throwable t) {
        RendererSwitch.setWanted(false);
        RendererSwitch.latch(false);
        if (!failureReported) {
            failureReported = true;
            LauncherLog.err("[Renderer] échec (" + step + "), retour au rendu vanilla", t);
        }
        try {
            CeleritasWorldRenderer.INSTANCE.setWorld(null);
        } catch (Throwable ignored) {
            // Déjà en échec : on ne cherche qu'à libérer ce qui peut l'être.
        }
    }

    /** Demandé par le module quand son réglage change : recharge les chunks, donc change de renderer. */
    public static void requestReload() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null && client.worldRenderer != null) {
            client.worldRenderer.reload();
        }
    }

    // ── Terrain ──────────────────────────────────────────────────────────────

    /** À la place de {@code WorldRenderer.setupTerrain} : visibilité et planification des constructions. */
    public static void setupTerrain(Object worldRenderer, Object cameraView, double tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();

        // Même garde que vanilla en tête de setupTerrain, mais sur la distance
        // une fois le curseur posé — voir settledViewDistance.
        if (settledViewDistance() != reloadedViewDistance) {
            ((WorldRenderer) worldRenderer).reload();
            if (!RendererSwitch.active()) {
                return;
            }
        }

        RenderDevice.enterManagedCode();
        try {
            Entity player = client.player;
            CeleritasWorldRenderer.INSTANCE.setupTerrain(((ViewportProvider) cameraView).sodium$createViewport(),
                    CeleritasWorldRenderer.captureCameraState(tickDelta),
                    frame++, player.noClip, false);
        } finally {
            RenderDevice.exitManagedCode();
        }

        // Brouillard et matrices : relus une seule fois par image, à la première
        // couche de blocs — vanilla ne les a pas encore posés à ce stade.
        GLStateManagerFogService.invalidate();
        PrimitiveChunkMatrixGetter.invalidate();

        CeleritasWorldRenderer.INSTANCE.trimSnapshots();

        logMetrics();
    }

    private static long lastMetricsNanos;
    private static int lastMetricsFrame;

    /**
     * Toutes les cinq secondes, l'état du renderer dans le journal (niveau 1 :
     * fichier seulement, sauf {@code log.renderer=1}). Sert à suivre dans le
     * temps la vitesse de construction des chunks, que l'écran F3 ne montre
     * qu'à l'instant.
     */
    private static void logMetrics() {
        long now = System.nanoTime();
        if (now - lastMetricsNanos < 5_000_000_000L) {
            return;
        }
        int frames = frame - lastMetricsFrame;
        double seconds = (now - lastMetricsNanos) / 1.0e9;
        boolean first = lastMetricsNanos == 0L;
        lastMetricsNanos = now;
        lastMetricsFrame = frame;
        if (first) {
            return;
        }
        CeleritasWorldRenderer renderer = CeleritasWorldRenderer.INSTANCE;
        LauncherLog.renderer(1, "métriques : " + Math.round(frames / seconds) + " fps | "
            + renderer.getChunksDebugString() + " | " + String.join(" | ", renderer.getDebugStrings()));
    }

    /** À la place de {@code WorldRenderer.renderLayer} : dessine une couche de blocs. */
    public static void renderLayer(Object layer, double tickDelta, Object viewEntityObject) {
        Entity viewEntity = (Entity) viewEntityObject;

        RenderDevice.enterManagedCode();

        DiffuseLighting.disable();

        double x = viewEntity.prevTickX + (viewEntity.x - viewEntity.prevTickX) * tickDelta;
        // Pas de hauteur des yeux ici : c'est l'origine du monde, pas la caméra.
        double y = viewEntity.prevTickY + (viewEntity.y - viewEntity.prevTickY) * tickDelta;
        double z = viewEntity.prevTickZ + (viewEntity.z - viewEntity.prevTickZ) * tickDelta;

        originX = x;
        originY = y;
        originZ = z;

        GameRenderer gameRenderer = MinecraftClient.getInstance().gameRenderer;
        gameRenderer.enableLightmap();

        try {
            CeleritasWorldRenderer.INSTANCE.drawChunkLayer(layer, x, y, z);
        } finally {
            RenderDevice.exitManagedCode();
        }

        gameRenderer.disableLightmap();
    }

    // ── Ciel et nuages ───────────────────────────────────────────────────────

    /** À la place de {@code WorldRenderer.renderFancyClouds} — voir {@link CloudRenderer189}. */
    public static void renderFancyClouds(Object worldRenderer, float tickDelta) {
        CloudRenderer189.render(((WorldRendererAccessor189) worldRenderer).la$ticks(), tickDelta);
    }

    /** Identifiant numérique de la cécité : la 1.8.9 désigne ses effets ainsi. */
    private static final int BLINDNESS_ID = 15;

    /**
     * Vrai quand le brouillard est rapproché : œil dans l'eau ou la lave, ou
     * cécité. Les mêmes cas que {@code GameRenderer.updateFog} de vanilla. Le
     * moteur n'envoie pas les chunks cachés par ce brouillard ; le ciel dessiné
     * derrière se verrait à leur place.
     */
    public static boolean isSkyHidden(float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        Entity camera = client.getCameraEntity();
        World world = client.world;
        if (camera == null || world == null) {
            return false;
        }
        if (camera instanceof LivingEntity && ((LivingEntity) camera).hasStatusEffect(BLINDNESS_ID)) {
            return true;
        }
        Material material = Camera.getSubmergedBlock(world, camera, tickDelta).getMaterial();
        return material == Material.WATER || material == Material.LAVA;
    }

    // ── Particules ───────────────────────────────────────────────────────────

    /** L'origine du rendu de l'image : la position interpolée de l'entité de vue, sans la hauteur des yeux. */
    private static double originX, originY, originZ;

    /**
     * À part, pour que JOML ne soit touché qu'au premier test : cette classe-ci
     * se charge même quand le moteur est indisponible (suivi des chunks).
     * Aucun type du jeu, donc pas de marqueur {@link YarnNamed}.
     */
    private static final class ParticleFrustum {
        static final FrustumIntersection PLANES = new FrustumIntersection();
        static final Matrix4f CLIP = new Matrix4f();
        static ChunkRenderMatrices source;

        static boolean testSphere(ChunkRenderMatrices matrices, float x, float y, float z, float radius) {
            if (matrices != source) {
                source = matrices;
                // true : plans normalisés, nécessaires au test de sphère.
                PLANES.set(matrices.projection().mul(matrices.modelView(), CLIP), true);
            }
            return PLANES.testSphere(x, y, z, radius);
        }
    }

    /**
     * Marge autour du centre d'une particule, en blocs. Large exprès : une
     * explosion dessine un quad de plusieurs blocs, et une particule écartée à
     * tort se verrait, alors qu'une particule gardée à tort ne coûte presque rien.
     */
    private static final float PARTICLE_RADIUS = 4.0F;

    /**
     * Vrai si la particule est entièrement hors du champ de la caméra. Les
     * plans viennent des matrices que le terrain a déjà lues pour cette image ;
     * tant qu'il n'a rien dessiné, rien n'est écarté.
     */
    public static boolean isParticleCulled(Object particleObject) {
        ChunkRenderMatrices matrices = PrimitiveChunkMatrixGetter.peek();
        if (matrices == null) {
            return false;
        }
        Entity particle = (Entity) particleObject;
        return !ParticleFrustum.testSphere(matrices, (float) (particle.x - originX), (float) (particle.y - originY),
            (float) (particle.z - originZ), PARTICLE_RADIUS);
    }

    /** À la place de {@code WorldRenderer.updateBlock} : des blocs ont changé. */
    public static void scheduleRebuild(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        CeleritasWorldRenderer.INSTANCE.scheduleRebuildForBlockArea(minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    public static String chunksDebugString() {
        return CeleritasWorldRenderer.INSTANCE.getChunksDebugString();
    }

    // ── Suivi des chunks chargés ─────────────────────────────────────────────


    /** Un chunk du client vient d'être chargé ({@code true}) ou déchargé. */
    public static void onClientChunk(int x, int z, boolean loaded) {
        // La carte des chunks repose sur fastutil : rien à tenir sans lui.
        if (!RendererSwitch.available()) {
            return;
        }
        World world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }
        // Les copies figées de cette colonne datent d'avant : elles sont jetées.
        CeleritasWorldRenderer.INSTANCE.invalidateColumn(x, z);
        if (loaded) {
            ChunkTrackerHolder.get(world).onChunkStatusAdded(x, z, ChunkStatus.FLAG_ALL);
        } else {
            ChunkTrackerHolder.get(world).onChunkStatusRemoved(x, z, ChunkStatus.FLAG_ALL);
        }
    }

    // ── Caméra ───────────────────────────────────────────────────────────────

    /**
     * Le volume de vue courant, pour le culling du moteur. {@code x/y/z} : la
     * position de la caméra tenue par le {@code CullingCameraView} de vanilla.
     */
    public static Viewport createViewport(double x, double y, double z) {
        // Les plans de coupe que vanilla vient de calculer pour cette image.
        BaseFrustum frustum = Frustum.getInstance();

        Matrix4f modelMatrix = new Matrix4f();
        modelMatrix.set(frustum.modelMatrix);
        modelMatrix.invert();
        Vector3f offset = new Vector3f();
        modelMatrix.transformPosition(offset);

        // Deux repères : vanilla travaille autour de l'ORIGINE du rendu (les pieds
        // de l'entité de vue), le moteur autour de l'ŒIL. « offset » est l'œil vu
        // depuis l'origine : hauteur des yeux, plus le recul en troisième personne.
        //
        // La matrice sert au culling par rastérisation. Celle de vanilla
        // (projection × vue) part de l'origine ; on la fait partir de l'œil.
        Matrix4f viewProjection = new Matrix4f();
        viewProjection.set(frustum.clipMatrix);
        viewProjection.translate(offset.x, offset.y, offset.z);

        return new Viewport(new FrustumTester(frustum, offset.x, offset.y, offset.z),
                new Vector3d(x + offset.x, y + offset.y, z + offset.z), viewProjection);
    }

    /** Classe nommée et non lambda : elle tient un type du jeu. */
    @YarnNamed
    private static final class FrustumTester implements com.yuyuframe.launcheragent.celeritas.viewport.frustum.Frustum {
        private final BaseFrustum frustum;
        private final float offsetX, offsetY, offsetZ;

        FrustumTester(BaseFrustum frustum, float offsetX, float offsetY, float offsetZ) {
            this.frustum = frustum;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }

        /**
         * Le moteur donne des coordonnées autour de l'œil, les plans de vanilla
         * sont autour de l'origine du rendu : on revient à celle-ci. Écart
         * corrigé par rapport à l'amont (2026-10-06), qui passait les
         * coordonnées telles quelles — tout le test était décalé de la hauteur
         * des yeux, soit 1,62 bloc de sections coupées trop tôt en haut de l'écran.
         */
        @Override
        public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return this.frustum.isInFrustum(minX + this.offsetX, minY + this.offsetY, minZ + this.offsetZ,
                    maxX + this.offsetX, maxY + this.offsetY, maxZ + this.offsetZ);
        }
    }

    // ── Entités et block entities ────────────────────────────────────────────

    public static void renderBlockEntities(float tickDelta) {
        CeleritasWorldRenderer.INSTANCE.renderBlockEntities(tickDelta);
    }

    /**
     * Dessine les entités. Vanilla les prend dans les chunks visibles de son
     * propre renderer de terrain, vides quand le nôtre dessine.
     */
    public static void renderEntities(Object worldRenderer, Object cameraObject, Object cameraViewObject, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        Entity camera = (Entity) cameraObject;
        CameraView cameraView = (CameraView) cameraViewObject;
        World world = client.world;
        EntityRenderDispatcher dispatcher = client.getEntityRenderManager();
        WorldRendererAccessor189 counters = (WorldRendererAccessor189) worldRenderer;

        // Position interpolée de la caméra, comme vanilla la calcule en tête de renderEntities.
        double camX = camera.prevTickX + (camera.x - camera.prevTickX) * (double) tickDelta;
        double camY = camera.prevTickY + (camera.y - camera.prevTickY) * (double) tickDelta;
        double camZ = camera.prevTickZ + (camera.z - camera.prevTickZ) * (double) tickDelta;

        Entity viewEntity = client.getCameraEntity();
        Entity player = client.player;
        boolean viewSleeping = viewEntity instanceof LivingEntity && ((LivingEntity) viewEntity).isSleeping();

        ENTITY_GATHERER.clear();
        List<Object> entities = ENTITY_GATHERER.getLoadedEntityList(world);

        BlockPos.Mutable entityBlockPos = new BlockPos.Mutable();

        // Les étiquettes de nom sont notées pendant la boucle et dessinées ensemble après.
        LabelBatch189.begin(tickDelta);
        try {
            renderEntityList(entities, dispatcher, cameraView, camX, camY, camZ, viewEntity, player, viewSleeping,
                client, world, entityBlockPos, counters, tickDelta);
        } finally {
            LabelBatch189.end();
        }
    }

    private static void renderEntityList(List<Object> entities, EntityRenderDispatcher dispatcher, CameraView cameraView,
                                         double camX, double camY, double camZ, Entity viewEntity, Entity player,
                                         boolean viewSleeping, MinecraftClient client, World world,
                                         BlockPos.Mutable entityBlockPos, WorldRendererAccessor189 counters,
                                         float tickDelta) {
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = (Entity) entities.get(i);

            if (!dispatcher.shouldRender(entity, cameraView, camX, camY, camZ) && entity.rider != player) {
                if (entity instanceof WitherSkullEntity) {
                    dispatcher.method_10204(entity, tickDelta);
                }
                continue;
            }

            // En première personne, on ne se dessine pas soi-même (sauf endormi).
            if (entity == viewEntity && client.options.perspective == 0 && !viewSleeping) {
                continue;
            }

            // Culling d'occlusion : une entité dont toutes les sections sont cachées
            // n'est pas dessinée.
            //
            // Règle de Sodium : jamais pour une entité dont le nom s'affiche — les
            // joueurs, et les entités à nom toujours visible (hologrammes). Le
            // culling par rastérisation cache ce qui est derrière un mur ; sans
            // cette règle, on verrait moins de pseudos à travers les murs qu'en
            // vanilla, ce qui compte en PvP.
            boolean nameShown = entity instanceof PlayerEntity || entity.isCustomNameVisible();
            if (!nameShown && entity != viewEntity && !entity.ignoreCameraFrustum && entity.rider != player) {
                Box box = entity.getBoundingBox();
                if (!CeleritasWorldRenderer.INSTANCE.isBoxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
                    continue;
                }
            }

            if (entity.y >= 0.0 && entity.y < 256.0) {
                entityBlockPos.setPosition(MathHelper.floor(entity.x), MathHelper.floor(entity.y), MathHelper.floor(entity.z));
                if (!world.blockExists(entityBlockPos)) {
                    continue;
                }
            }

            counters.la$setHiddenEntityCount(counters.la$getHiddenEntityCount() + 1);
            // Un hologramme n'a que son nom à dessiner : on saute le reste du parcours de vanilla.
            if (!LabelBatch189.hologram(entity, camX, camY, camZ)) {
                // Chaque entité part d'un état sûr. Vanilla ne repose ni la
                // couleur ni l'écriture de profondeur avant le corps d'une
                // entité : il compte sur ce qu'a laissé celle d'avant (voir
                // LabelBatch189.leaveVanillaState). Or certaines couches sortent
                // sans rétablir la profondeur — les yeux d'une araignée
                // invisible ({@code depthMask(false)}, mesuré le 2026-10-08 :
                // fusion 1/1 et écriture coupée au moment du corps). Le corps du
                // joueur suivant était alors dessiné sans profondeur et
                // recouvert par tout ce qui venait après, derrière lui, alors
                // que son armure — dessinée après que vanilla a rétabli
                // l'écriture — restait pleine. Notre ordre de parcours n'est
                // pas celui de vanilla : on ne dépend plus de qui passe avant.
                com.mojang.blaze3d.platform.GlStateManager.depthMask(true);
                com.mojang.blaze3d.platform.GlStateManager.clearColor();
                com.mojang.blaze3d.platform.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                dispatcher.renderEntity(entity, tickDelta);
            }
        }
    }
}
