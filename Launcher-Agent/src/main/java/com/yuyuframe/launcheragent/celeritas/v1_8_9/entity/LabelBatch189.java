package com.yuyuframe.launcheragent.celeritas.v1_8_9.entity;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.text.TextBatch189;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Dessine toutes les étiquettes de nom d'une image ensemble — notre code, pas
 * celui de Celeritas.
 *
 * <p><b>Ce que fait vanilla</b> ({@code EntityRenderer.renderLabelIfPresent}) :
 * pour chaque étiquette, une douzaine de changements d'état OpenGL, le fond
 * sombre, puis le texte deux fois — une fois à travers les murs, très
 * transparent, une fois normalement. Mesuré le 2026-10-06 dans un lobby :
 * 2 à 14 µs par étiquette, et jusqu'à 640 µs par image pour 150 étiquettes.
 *
 * <p><b>Ce que fait cette classe</b> : pendant le dessin des entités, chaque
 * étiquette est seulement notée. À la fin, tous les fonds partent en un
 * dessin, puis tous les textes en deux (un par passe), avec les mêmes états
 * que vanilla posés une seule fois. La géométrie d'un texte est gardée d'une
 * image à l'autre : c'est vanilla qui la produit, la première fois, à travers
 * {@link TextBatch189} — les caractères sont donc exactement les siens.
 *
 * <p><b>Ce qui reste à vanilla</b>, dessiné sur-le-champ comme avant :
 * <ul>
 *   <li>un nom barré, souligné ou brouillé (§m, §n, §k) : ces styles se
 *       dessinent hors des caractères, ou changent à chaque image ;</li>
 *   <li>l'étiquette d'un joueur accroupi, qui ne passe pas par cette méthode.</li>
 * </ul>
 *
 * <p><b>Seul écart d'image</b> : quand deux étiquettes se chevauchent à
 * l'écran. Vanilla dessine fond et texte entité par entité ; ici tous les
 * fonds passent avant tous les textes, donc un fond ne recouvre plus le texte
 * d'une autre étiquette.
 *
 * <p>Fil de rendu seulement. Actif avec le renderer de terrain.
 */
@YarnNamed
public final class LabelBatch189 {

    private LabelBatch189() {
    }

    /** Échelle d'une étiquette : 1,6 × 1/60, comme vanilla. */
    private static final float SCALE = 0.016666668F * 1.6F;

    /** La passe « à travers les murs » de vanilla dessine en 0x20FFFFFF ; la passe normale en blanc opaque. */
    private static final int DIM_ALPHA = 0x20;
    private static final int FULL_ALPHA = 0xFF;

    private static final int FULL_BRIGHT = 15728880;
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);

    private static boolean deferring;
    private static float tickDelta;

    // ── Géométrie gardée d'un texte ──────────────────────────────────────────

    /** Les caractères d'un texte, dans le repère de l'étiquette, groupés par texture. */
    private static final class Geometry {
        int width;
        final ArrayList<GlyphRun> runs = new ArrayList<>(1);
    }

    /** Les caractères d'un texte qui partagent une texture. */
    private static final class GlyphRun {
        int textureId;
        /** x, y, u, v par sommet, quatre sommets par caractère. */
        float[] vertices = new float[16 * 16];
        int[] rgb = new int[16];
        int quads;

        void add(float[] quad, int color) {
            if (this.quads == this.rgb.length) {
                this.vertices = java.util.Arrays.copyOf(this.vertices, this.vertices.length * 2);
                this.rgb = java.util.Arrays.copyOf(this.rgb, this.rgb.length * 2);
            }
            System.arraycopy(quad, 0, this.vertices, this.quads * 16, 16);
            this.rgb[this.quads] = color;
            this.quads++;
        }
    }

    /** Reçoit les caractères que vanilla dessine pendant une capture. Aucun type du jeu. */
    private static final class Collector implements TextBatch189.GlyphSink {
        Geometry target;
        private GlyphRun current;
        private int currentPage;

        void start(Geometry geometry) {
            this.target = geometry;
            this.current = null;
        }

        @Override
        public void quad(int glyphPage, float[] strip, int rgb) {
            if (this.current == null || glyphPage != this.currentPage) {
                // La texture de cette page vient d'être liée par vanilla : on relève son identifiant.
                int textureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                this.current = null;
                for (int i = 0; i < this.target.runs.size(); i++) {
                    if (this.target.runs.get(i).textureId == textureId) {
                        this.current = this.target.runs.get(i);
                    }
                }
                if (this.current == null) {
                    this.current = new GlyphRun();
                    this.current.textureId = textureId;
                    this.target.runs.add(this.current);
                }
                this.currentPage = glyphPage;
            }
            this.current.add(strip, rgb);
        }
    }

    private static final Collector COLLECTOR = new Collector();
    private static final HashMap<String, Geometry> GEOMETRIES = new HashMap<>();
    private static final int MAX_GEOMETRIES = 2048;

    /** Police ou ressources changées : les géométries gardées ne valent plus rien. */
    public static void clearCache() {
        GEOMETRIES.clear();
    }

    private static Geometry geometryOf(String text) {
        Geometry geometry = GEOMETRIES.get(text);
        if (geometry != null) {
            return geometry;
        }
        // Les noms qui changent sans cesse (vie affichée, compte à rebours) s'accumulent : on repart de zéro.
        if (GEOMETRIES.size() >= MAX_GEOMETRIES) {
            GEOMETRIES.clear();
        }
        geometry = new Geometry();
        net.minecraft.client.font.TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        geometry.width = textRenderer.getStringWidth(text);
        COLLECTOR.start(geometry);
        TextBatch189.beginCapture(COLLECTOR);
        try {
            // Mêmes arguments que vanilla, à la hauteur près (ajoutée au dessin).
            textRenderer.draw(text, -geometry.width / 2, 0, -1);
        } finally {
            TextBatch189.endCapture();
        }
        GEOMETRIES.put(text, geometry);
        return geometry;
    }

    // ── Tampons de l'image ───────────────────────────────────────────────────

    private static FloatBuffer backgrounds = BufferUtils.createFloatBuffer(256 * 4 * 3);
    private static int backgroundBuffer;
    private static int backgroundVertices;

    /** Les caractères d'une texture, pour toutes les étiquettes de l'image. */
    private static final class TextureBatch {
        /** Position (3 flottants), texture (2 flottants), lumière (2 entiers courts) : 24 octets. */
        static final int STRIDE = 24;

        final int textureId;
        ByteBuffer vertices = BufferUtils.createByteBuffer(1024 * 4 * STRIDE);
        /** Une couleur par sommet et par passe : seul l'alpha diffère entre les deux. */
        ByteBuffer dimColors = BufferUtils.createByteBuffer(1024 * 4 * 4);
        ByteBuffer fullColors = BufferUtils.createByteBuffer(1024 * 4 * 4);
        int count;
        /** Tampons de la carte graphique, créés au premier dessin. */
        int vertexBuffer, dimBuffer, fullBuffer;

        TextureBatch(int textureId) {
            this.textureId = textureId;
        }

        void reserve(int moreVertices) {
            if ((this.count + moreVertices) * STRIDE <= this.vertices.capacity()) {
                return;
            }
            int capacity = Math.max(this.vertices.capacity() / STRIDE * 2, this.count + moreVertices);
            this.vertices = grow(this.vertices, this.count * STRIDE, capacity * STRIDE);
            this.dimColors = grow(this.dimColors, this.count * 4, capacity * 4);
            this.fullColors = grow(this.fullColors, this.count * 4, capacity * 4);
        }

        private static ByteBuffer grow(ByteBuffer old, int used, int capacity) {
            ByteBuffer grown = BufferUtils.createByteBuffer(capacity);
            old.position(0).limit(used);
            grown.put(old);
            grown.clear();
            return grown;
        }
    }

    private static final ArrayList<TextureBatch> TEXTURES = new ArrayList<>(2);

    private static TextureBatch batchFor(int textureId) {
        for (int i = 0; i < TEXTURES.size(); i++) {
            if (TEXTURES.get(i).textureId == textureId) {
                return TEXTURES.get(i);
            }
        }
        TextureBatch batch = new TextureBatch(textureId);
        TEXTURES.add(batch);
        return batch;
    }

    private static final Matrix4f TRANSFORM = new Matrix4f();

    /** Les deux axes du plan des étiquettes pour l'image en cours — voir {@link #defer}. */
    private static boolean axesReady;
    private static float axisXx, axisXy, axisXz;
    private static float axisYx, axisYy, axisYz;

    // ── Pendant le dessin des entités ────────────────────────────────────────

    /** Avant la boucle des entités : les étiquettes sont désormais notées au lieu d'être dessinées. */
    public static void begin(float delta) {
        tickDelta = delta;
        axesReady = false;
        backgroundVertices = 0;
        DECOR.count = 0;
        for (int i = 0; i < TEXTURES.size(); i++) {
            TEXTURES.get(i).count = 0;
        }
        // Sans le texte regroupé, on ne peut pas capturer les caractères de vanilla.
        deferring = TextBatch189.isEnabled();
    }

    /**
     * En tête de {@code EntityRenderer.renderLabelIfPresent}.
     *
     * @return vrai si l'étiquette est prise en charge : vanilla ne doit alors rien dessiner
     */
    public static boolean defer(Object entityObject, String text, double x, double y, double z, int maxDistance) {
        return defer(entityObject, text, x, y, z, maxDistance, false);
    }

    /**
     * @param styled joueur YuyuFrame : son étiquette prend le fond, le logo et
     *               les couleurs du module « Joueur » (voir {@link PlayerStyle})
     */
    public static boolean defer(Object entityObject, String text, double x, double y, double z, int maxDistance, boolean styled) {
        if (!deferring || hasUnsupportedStyle(text)) {
            return false;
        }

        Entity entity = (Entity) entityObject;
        EntityRenderDispatcher dispatcher = MinecraftClient.getInstance().getEntityRenderManager();

        // Même test de distance que vanilla : au-delà, il ne dessine rien.
        if (entity.squaredDistanceTo(dispatcher.field_11098) > (double) (maxDistance * maxDistance)) {
            return true;
        }

        Geometry geometry = geometryOf(text);
        int offsetY = "deadmau5".equals(text) ? -10 : 0;

        // Toutes les étiquettes d'une image ont la même orientation (face à la
        // caméra) et la même échelle : seule leur position diffère. On calcule
        // donc une fois par image les deux axes de leur plan — où mènent un pas
        // vers la droite et un pas vers le bas du texte — et chaque sommet
        // devient : position de l'étiquette + x × axe X + y × axe Y. Mêmes
        // transformations que vanilla (lacet, tangage, échelle), dans le même ordre.
        if (!axesReady) {
            TRANSFORM.rotationY(-dispatcher.yaw * DEGREES_TO_RADIANS)
                .rotateX(dispatcher.pitch * DEGREES_TO_RADIANS)
                .scale(-SCALE, -SCALE, SCALE);
            axisXx = TRANSFORM.m00();
            axisXy = TRANSFORM.m01();
            axisXz = TRANSFORM.m02();
            axisYx = TRANSFORM.m10();
            axisYy = TRANSFORM.m11();
            axisYz = TRANSFORM.m12();
            axesReady = true;
        }
        float originX = (float) x;
        float originY = (float) y + entity.height + 0.5F;
        float originZ = (float) z;

        // La lumière que vanilla a posée pour cette entité, et qui éclaire aussi son étiquette.
        int light = entity.isOnFire() ? FULL_BRIGHT : entity.getLightmapCoordinates(tickDelta);
        short lightU = (short) (light % 65536);
        short lightV = (short) (light / 65536);

        int halfWidth = geometry.width / 2;
        // Étiquette stylisée : le logo passe devant le pseudo, et l'ensemble
        // reste centré sur le joueur — le texte est donc décalé d'un demi-logo.
        float lead = styled && PlayerStyle.logo ? PlayerStyle.LOGO_WIDTH + PlayerStyle.LOGO_GAP : 0.0F;
        float shift = lead / 2.0F;
        float left = -halfWidth - 1 - shift;
        float right = halfWidth + 1 + shift;
        float now = 0.0F;
        if (styled) {
            now = PlayerStyle.time();
            DECOR.at(originX, originY, originZ, offsetY);
            if (PlayerStyle.logo) {
                PlayerStyle.logo(DECOR, left + 1.0F, now);
            }
            // Pleine lumière : un pseudo coloré qui s'assombrit la nuit perd tout son intérêt.
            lightU = (short) (FULL_BRIGHT % 65536);
            lightV = (short) (FULL_BRIGHT / 65536);
        }
        if (styled && PlayerStyle.plate != PlayerStyle.PLATE_VANILLA) {
            // Plus haute que le fond de vanilla : la place ne manque pas au-dessus d'une tête.
            PlayerStyle.plate(DECOR, left - 1.0F, right + 1.0F, -2.0F, 10.0F, now);
        } else {
            background(originX, originY, originZ, left, -1 + offsetY);
            background(originX, originY, originZ, left, 8 + offsetY);
            background(originX, originY, originZ, right, 8 + offsetY);
            background(originX, originY, originZ, right, -1 + offsetY);
        }

        for (int r = 0; r < geometry.runs.size(); r++) {
            GlyphRun run = geometry.runs.get(r);
            TextureBatch batch = batchFor(run.textureId);
            batch.reserve(run.quads * 4);
            // Écriture directe en mémoire : la place vient d'être réservée, et
            // chaque écriture par ByteBuffer revérifiait ses bornes.
            long at = MemoryUtil.memAddress(batch.vertices, 0) + (long) batch.count * TextureBatch.STRIDE;
            long dimAt = MemoryUtil.memAddress(batch.dimColors, 0) + (long) batch.count * 4L;
            long fullAt = MemoryUtil.memAddress(batch.fullColors, 0) + (long) batch.count * 4L;
            float[] source = run.vertices;
            int from = 0;
            for (int q = 0; q < run.quads; q++) {
                int rgb = run.rgb[q];
                if (styled && PlayerStyle.recolors(rgb)) {
                    // La place de la lettre dans le pseudo, de 0 à gauche à 1 à droite.
                    float t = geometry.width > 0 ? (source[from] + halfWidth) / geometry.width : 0.0F;
                    rgb = PlayerStyle.textColor(t, now);
                }
                // En mémoire : rouge, vert, bleu, alpha.
                int abgr = (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
                int dim = DIM_ALPHA << 24 | abgr;
                int full = FULL_ALPHA << 24 | abgr;
                for (int v = 0; v < 4; v++) {
                    float labelX = source[from] + shift;
                    float labelY = source[from + 1] + offsetY;
                    MemoryUtil.memPutFloat(at, originX + labelX * axisXx + labelY * axisYx);
                    MemoryUtil.memPutFloat(at + 4L, originY + labelX * axisXy + labelY * axisYy);
                    MemoryUtil.memPutFloat(at + 8L, originZ + labelX * axisXz + labelY * axisYz);
                    MemoryUtil.memPutFloat(at + 12L, source[from + 2]);
                    MemoryUtil.memPutFloat(at + 16L, source[from + 3]);
                    MemoryUtil.memPutShort(at + 20L, lightU);
                    MemoryUtil.memPutShort(at + 22L, lightV);
                    MemoryUtil.memPutInt(dimAt, dim);
                    MemoryUtil.memPutInt(fullAt, full);
                    at += TextureBatch.STRIDE;
                    dimAt += 4L;
                    fullAt += 4L;
                    from += 4;
                }
            }
            batch.count += run.quads * 4;
        }
        return true;
    }

    // ── Décor des étiquettes stylisées ───────────────────────────────────────

    /**
     * Fond, liserés et logo des étiquettes des joueurs YuyuFrame : des
     * quadrilatères sans texture, une couleur par sommet. Chaque sommet a deux
     * opacités, une par passe — derrière un mur, puis en vue — comme le texte.
     */
    private static final class Decor implements PlayerStyle.Sink {
        ByteBuffer vertices = BufferUtils.createByteBuffer(512 * 12);
        ByteBuffer hiddenColors = BufferUtils.createByteBuffer(512 * 4);
        ByteBuffer visibleColors = BufferUtils.createByteBuffer(512 * 4);
        int count;
        int vertexBuffer, hiddenBuffer, visibleBuffer;
        private float originX, originY, originZ;
        private int offsetY;

        void at(float x, float y, float z, int offset) {
            this.originX = x;
            this.originY = y;
            this.originZ = z;
            this.offsetY = offset;
        }

        @Override
        public void vertex(float labelX, float labelY, int rgb, int hidden, int visible) {
            if ((this.count + 1) * 12 > this.vertices.capacity()) {
                int capacity = this.vertices.capacity() / 12 * 2;
                this.vertices = TextureBatch.grow(this.vertices, this.count * 12, capacity * 12);
                this.hiddenColors = TextureBatch.grow(this.hiddenColors, this.count * 4, capacity * 4);
                this.visibleColors = TextureBatch.grow(this.visibleColors, this.count * 4, capacity * 4);
            }
            float y = labelY + this.offsetY;
            int at = this.count * 12;
            this.vertices.putFloat(at, this.originX + labelX * axisXx + y * axisYx);
            this.vertices.putFloat(at + 4, this.originY + labelX * axisXy + y * axisYy);
            this.vertices.putFloat(at + 8, this.originZ + labelX * axisXz + y * axisYz);
            // En mémoire : rouge, vert, bleu, alpha.
            int abgr = (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
            this.hiddenColors.putInt(this.count * 4, (hidden & 0xFF) << 24 | abgr);
            this.visibleColors.putInt(this.count * 4, (visible & 0xFF) << 24 | abgr);
            this.count++;
        }
    }

    private static final Decor DECOR = new Decor();

    private static void drawDecor(boolean visible) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, DECOR.vertexBuffer);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 12, 0L);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, visible ? DECOR.visibleBuffer : DECOR.hiddenBuffer);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 4, 0L);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, DECOR.count);
    }

    private static void background(float originX, float originY, float originZ, float labelX, float labelY) {
        if (backgroundVertices * 3 + 3 > backgrounds.capacity()) {
            FloatBuffer grown = BufferUtils.createFloatBuffer(backgrounds.capacity() * 2);
            backgrounds.position(0).limit(backgroundVertices * 3);
            grown.put(backgrounds);
            grown.clear();
            backgrounds = grown;
        }
        int at = backgroundVertices * 3;
        backgrounds.put(at, originX + labelX * axisXx + labelY * axisYx)
            .put(at + 1, originY + labelX * axisXy + labelY * axisYy)
            .put(at + 2, originZ + labelX * axisXz + labelY * axisYz);
        backgroundVertices++;
    }

    // ── Hologrammes ──────────────────────────────────────────────────────────

    /** Portée du nom d'une entité vivante qui n'est pas accroupie, au carré. */
    private static final double NAME_RANGE_SQUARED = 64.0 * 64.0;

    /**
     * Chemin court pour un hologramme : un porte-armure invisible et sans
     * équipement, dont vanilla ne dessine rien d'autre que le nom.
     *
     * <p>Vanilla lui fait pourtant suivre tout le parcours d'une entité —
     * lumière, matrices, animation du modèle, trois couches vides. Mesuré le
     * 2026-10-06 dans un lobby : 3 à 7 µs par porte-armure, dont 1 à 2 pour le
     * nom, et il y en a 150 à 300 par image. Ici on ne garde que le nom, avec
     * les mêmes conditions que vanilla (relevées dans le bytecode de
     * {@code LivingEntityRenderer} et d'{@code ArmorStandEntityRenderer}).
     *
     * <p>Au moindre doute — visible, équipé, en feu, vu par un spectateur,
     * première image de sa vie, nom au style non géré — on rend la main.
     *
     * @return vrai si l'entité est entièrement prise en charge : vanilla ne doit alors pas la dessiner
     */
    public static boolean hologram(Entity entity, double cameraX, double cameraY, double cameraZ) {
        if (!deferring || !(entity instanceof ArmorStandEntity)) {
            return false;
        }
        // À sa première image, vanilla recale la position précédente de l'entité avant de la dessiner.
        if (entity.ticksAlive == 0 || !entity.isInvisible() || entity.isOnFire()) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity viewer = client.player;
        // Un spectateur voit les entités invisibles en transparence.
        if (viewer == null || !entity.isInvisibleTo(viewer)) {
            return false;
        }
        LivingEntity living = (LivingEntity) entity;
        for (int slot = 0; slot < 5; slot++) {
            if (living.getMainSlot(slot) != null) {
                return false;
            }
        }

        // Rien à dessiner du tout : ni modèle, ni équipement, ni nom.
        if (!entity.isCustomNameVisible()) {
            return true;
        }
        EntityRenderDispatcher dispatcher = client.getEntityRenderManager();
        if (entity.squaredDistanceTo(dispatcher.field_11098) >= NAME_RANGE_SQUARED) {
            return true;
        }

        CommandSource named = entity;
        String text = named.getName().asFormattedString();
        if (hasUnsupportedStyle(text)) {
            return false;
        }

        // La position que vanilla passe au dessin : interpolée, relative à la caméra.
        double x = entity.prevTickX + (entity.x - entity.prevTickX) * (double) tickDelta - cameraX;
        double y = entity.prevTickY + (entity.y - entity.prevTickY) * (double) tickDelta - cameraY;
        double z = entity.prevTickZ + (entity.z - entity.prevTickZ) * (double) tickDelta - cameraZ;
        if (living.isBaby()) {
            y -= (double) (entity.height / 2.0F);
        }
        return defer(entity, text, x, y, z, 64);
    }

    /** Barré (§m), souligné (§n), brouillé (§k) : laissés à vanilla. */
    private static boolean hasUnsupportedStyle(String text) {
        int last = text.length() - 1;
        for (int i = 0; i < last; i++) {
            if (text.charAt(i) == '§') {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == 'k' || code == 'm' || code == 'n') {
                    return true;
                }
            }
        }
        return false;
    }

    // ── Après le dessin des entités ──────────────────────────────────────────

    /** Dessine tout ce qui a été noté, avec les états de vanilla posés une seule fois. */
    public static void end() {
        deferring = false;
        if (backgroundVertices == 0 && DECOR.count == 0) {
            return;
        }

        GlStateManager.disableLighting();
        // Vanilla le pose avant chaque nom d'entité vivante ; le chemin court des hologrammes ne passe pas par là.
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.depthMask(false);
        GlStateManager.disableDepthTest();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);

        // Les fonds : noirs, transparents aux trois quarts, visibles à travers les murs.
        // Envoyés à la carte graphique comme le texte, et non dessinés depuis la
        // mémoire : mesuré le 2026-10-06, le dessin depuis la mémoire faisait
        // attendre ici 0 à 345 µs sans rapport avec le nombre d'étiquettes, et
        // l'envoi a fait disparaître cette attente.
        GlStateManager.disableTexture();
        GlStateManager.color(0.0F, 0.0F, 0.0F, 0.25F);
        if (backgroundBuffer == 0) {
            backgroundBuffer = GL15.glGenBuffers();
        }
        backgrounds.position(0).limit(backgroundVertices * 3);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, backgroundBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, backgrounds, GL15.GL_STREAM_DRAW);
        backgrounds.limit(backgrounds.capacity());
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 12, 0L);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, backgroundVertices);

        // Le décor des étiquettes stylisées, tant que la texture est coupée et
        // avant que le texte n'active ses tableaux de coordonnées : d'abord ce
        // qui se voit derrière un mur, puis ce qui se voit en vue. La profondeur
        // n'est pas écrite (voir depthMask plus haut), seulement testée.
        if (DECOR.count > 0) {
            if (DECOR.vertexBuffer == 0) {
                DECOR.vertexBuffer = GL15.glGenBuffers();
                DECOR.hiddenBuffer = GL15.glGenBuffers();
                DECOR.visibleBuffer = GL15.glGenBuffers();
            }
            sendBuffer(DECOR.vertexBuffer, DECOR.vertices, DECOR.count * 12);
            sendBuffer(DECOR.hiddenBuffer, DECOR.hiddenColors, DECOR.count * 4);
            sendBuffer(DECOR.visibleBuffer, DECOR.visibleColors, DECOR.count * 4);

            // Lus puis rétablis tels quels : ces deux états ne passent pas par
            // GlStateManager ici, son cache doit rester juste.
            int shadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
            boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glDisable(GL11.GL_CULL_FACE);
            // Pleine lumière : sans tableau de coordonnées, la carte de lumière
            // prendrait celle de la dernière entité dessinée.
            GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1, 240.0F, 240.0F);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            drawDecor(false);
            GlStateManager.enableDepthTest();
            drawDecor(true);
            GlStateManager.disableDepthTest();
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glShadeModel(shadeModel);
            if (culling) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            }
            DECOR.count = 0;
        }
        GlStateManager.enableTexture();

        // Les sommets du texte servent aux deux passes : on les envoie une seule
        // fois à la carte graphique.
        upload();

        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);

        // Le texte à travers les murs, presque transparent.
        drawText(false);

        GlStateManager.enableDepthTest();
        GlStateManager.depthMask(true);

        // Le texte normal, caché par ce qui est devant lui.
        drawText(true);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);

        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        // Un dessin avec des couleurs par sommet laisse la couleur courante
        // d'OpenGL indéfinie : on la repose en dur, le cache de GlStateManager
        // pouvant croire qu'elle est déjà blanche et sauter l'appel.
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        backgroundVertices = 0;
    }

    /** Envoie les sommets et les deux jeux de couleurs de l'image, une fois. */
    private static void upload() {
        for (int i = 0; i < TEXTURES.size(); i++) {
            TextureBatch batch = TEXTURES.get(i);
            if (batch.count == 0) {
                continue;
            }
            if (batch.vertexBuffer == 0) {
                batch.vertexBuffer = GL15.glGenBuffers();
                batch.dimBuffer = GL15.glGenBuffers();
                batch.fullBuffer = GL15.glGenBuffers();
            }
            // glBufferData remplace le tampon entier : le pilote n'attend pas que
            // l'image précédente ait fini de lire l'ancien.
            sendBuffer(batch.vertexBuffer, batch.vertices, batch.count * TextureBatch.STRIDE);
            sendBuffer(batch.dimBuffer, batch.dimColors, batch.count * 4);
            sendBuffer(batch.fullBuffer, batch.fullColors, batch.count * 4);
        }
    }

    private static void sendBuffer(int buffer, ByteBuffer data, int bytes) {
        data.position(0).limit(bytes);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
        data.limit(data.capacity());
    }

    private static void drawText(boolean full) {
        for (int i = 0; i < TEXTURES.size(); i++) {
            TextureBatch batch = TEXTURES.get(i);
            if (batch.count == 0) {
                continue;
            }
            GlStateManager.bindTexture(batch.textureId);

            // Avec un tampon d'objets lié, les pointeurs sont des décalages dans ce tampon.
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batch.vertexBuffer);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, TextureBatch.STRIDE, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, TextureBatch.STRIDE, 12L);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, TextureBatch.STRIDE, 20L);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, full ? batch.fullBuffer : batch.dimBuffer);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 4, 0L);

            GL11.glDrawArrays(GL11.GL_QUADS, 0, batch.count);
        }
    }
}
