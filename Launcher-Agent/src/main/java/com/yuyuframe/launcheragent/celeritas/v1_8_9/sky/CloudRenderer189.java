package com.yuyuframe.launcheragent.celeritas.v1_8_9.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/**
 * Les nuages détaillés de la 1.8.9, réécrits — notre code, pas celui de
 * Celeritas (l'idée vient de Sodium, qui réécrit lui aussi leur maillage).
 *
 * <p><b>Ce que fait vanilla</b> ({@code WorldRenderer.renderFancyClouds}) : à
 * chaque image, il recalcule tous les sommets des nuages — 64 dalles de
 * 8×8 cases, jusqu'à 34 quads chacune — et les envoie en 128 dessins (les 64
 * dalles, deux fois : une passe de profondeur, une passe de couleur).
 *
 * <p><b>Ce que fait cette classe</b> : la même géométrie, construite une fois
 * et gardée sur la carte graphique. Tout ce qui change d'une image à l'autre
 * passe par les matrices, sans toucher aux sommets :
 * <ul>
 *   <li>le défilement à l'intérieur d'une case et la hauteur relative à la
 *       caméra : une translation de la vue ;</li>
 *   <li>le décalage dans la texture quand on change de case : une translation
 *       de la matrice de texture.</li>
 * </ul>
 * Le maillage n'est refait que si la couleur des nuages change (lever et
 * coucher du soleil, pluie) ou si la caméra passe au-dessus ou en dessous de
 * la couche (les faces du dessus et du dessous n'existent alors plus ou pas).
 * Deux dessins par image au lieu de 128.
 *
 * <p>Les constantes et l'ordre des sommets sont ceux de vanilla, relevés dans
 * le bytecode de {@code bfr.c(FI)} : l'image doit être la même.
 */
@YarnNamed
public final class CloudRenderer189 {

    private CloudRenderer189() {
    }

    private static final Identifier CLOUDS = new Identifier("minecraft", "textures/environment/clouds.png");

    /** Une case de nuage fait 12 blocs de côté et 4 de haut. */
    private static final float CELL_SIZE = 12.0F;
    private static final float THICKNESS = 4.0F;
    /** Une case de nuage = un pixel d'une texture de 256. */
    private static final float TEXEL = 0.00390625F;
    /** Retrait des faces est, sud et dessus, pour qu'elles ne se superposent pas à leurs voisines. */
    private static final float INSET = 9.765625E-4F;

    /**
     * À la place de {@code WorldRenderer.renderFancyClouds}, hors anaglyphe.
     *
     * @param ticks le compteur de ticks du {@code WorldRenderer}, qui fait défiler les nuages
     */
    public static void render(int ticks, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        Entity camera = client.getCameraEntity();
        World world = client.world;

        GlStateManager.disableCull();

        float cameraY = (float) (camera.prevTickY + (camera.y - camera.prevTickY) * (double) tickDelta);
        double time = (double) ((float) ticks + tickDelta);
        double cloudX = (camera.prevX + (camera.x - camera.prevX) * (double) tickDelta + time * 0.029999999329447746D) / 12.0D;
        double cloudZ = (camera.prevZ + (camera.z - camera.prevZ) * (double) tickDelta) / 12.0D + 0.33000001311302185D;
        float relativeY = world.dimension.getCloudHeight() - cameraY + 0.33F;

        // La texture boucle tous les 2048 : on reste dans des nombres que le flottant porte bien.
        cloudX -= (double) (floor(cloudX / 2048.0D) * 2048);
        cloudZ -= (double) (floor(cloudZ / 2048.0D) * 2048);

        client.getTextureManager().bindTexture(CLOUDS);
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

        Vec3d color = world.getCloudColor(tickDelta);
        float red = (float) color.x;
        float green = (float) color.y;
        float blue = (float) color.z;

        int cellX = floor(cloudX);
        int cellZ = floor(cloudZ);
        float textureU = (float) cellX * TEXEL;
        float textureV = (float) cellZ * TEXEL;
        float fractionX = (float) (cloudX - (double) cellX);
        float fractionZ = (float) (cloudZ - (double) cellZ);

        // Mêmes seuils que vanilla : sous la couche on voit son dessous, au-dessus son dessus.
        boolean bottom = relativeY > -5.0F;
        boolean top = relativeY <= 5.0F;

        Mesh.ensure(bottom, top,
            pack(red, green, blue),
            pack(red * 0.9F, green * 0.9F, blue * 0.9F),
            pack(red * 0.7F, green * 0.7F, blue * 0.7F),
            pack(red * 0.8F, green * 0.8F, blue * 0.8F));

        GL11.glScalef(CELL_SIZE, 1.0F, CELL_SIZE);
        GL11.glTranslatef(-fractionX, relativeY, -fractionZ);

        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glTranslatef(textureU, textureV, 0.0F);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        Mesh.bind();
        // Passe de profondeur d'abord : sans elle, les faces arrière d'un nuage
        // se verraient à travers ses faces avant, toutes étant translucides.
        GlStateManager.colorMask(false, false, false, false);
        Mesh.draw();
        GlStateManager.colorMask(true, true, true, true);
        Mesh.draw();
        Mesh.unbind();

        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        // Un dessin avec des couleurs par sommet laisse la couleur courante
        // d'OpenGL indéfinie : on la repose en dur, le cache de GlStateManager
        // pouvant croire qu'elle est déjà blanche et sauter l'appel.
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < (double) truncated ? truncated - 1 : truncated;
    }

    /** Couleur d'un sommet telle qu'OpenGL la lit en mémoire : rouge, vert, bleu, alpha (0,8). */
    private static int pack(float red, float green, float blue) {
        int r = (int) (red * 255.0F) & 0xFF;
        int g = (int) (green * 255.0F) & 0xFF;
        int b = (int) (blue * 255.0F) & 0xFF;
        int a = (int) (0.8F * 255.0F);
        return a << 24 | b << 16 | g << 8 | r;
    }

    /**
     * Le maillage gardé sur la carte graphique. Aucun type du jeu : pas de
     * marqueur {@link YarnNamed}.
     */
    private static final class Mesh {
        /** Position (3 flottants), texture (2 flottants), couleur (4 octets). */
        private static final int STRIDE = 24;
        /** 64 dalles × (dessus + dessous + 4 côtés de 8 cases). */
        private static final int MAX_QUADS = 64 * (2 + 4 * 8);

        private static final ByteBuffer DATA = BufferUtils.createByteBuffer(MAX_QUADS * 4 * STRIDE);

        private static int buffer;
        private static int vertexCount;
        private static boolean built;
        private static boolean builtBottom, builtTop;
        private static int builtColorTop, builtColorX, builtColorBottom, builtColorZ;

        static void ensure(boolean bottom, boolean top, int colorTop, int colorX, int colorBottom, int colorZ) {
            if (built && bottom == builtBottom && top == builtTop && colorTop == builtColorTop
                    && colorX == builtColorX && colorBottom == builtColorBottom && colorZ == builtColorZ) {
                return;
            }
            built = true;
            builtBottom = bottom;
            builtTop = top;
            builtColorTop = colorTop;
            builtColorX = colorX;
            builtColorBottom = colorBottom;
            builtColorZ = colorZ;

            DATA.clear();
            for (int tileX = -3; tileX <= 4; tileX++) {
                for (int tileZ = -3; tileZ <= 4; tileZ++) {
                    float x = (float) (tileX * 8);
                    float z = (float) (tileZ * 8);

                    if (bottom) {
                        vertex(x, 0.0F, z + 8.0F, x, z + 8.0F, colorBottom);
                        vertex(x + 8.0F, 0.0F, z + 8.0F, x + 8.0F, z + 8.0F, colorBottom);
                        vertex(x + 8.0F, 0.0F, z, x + 8.0F, z, colorBottom);
                        vertex(x, 0.0F, z, x, z, colorBottom);
                    }
                    if (top) {
                        float y = THICKNESS - INSET;
                        vertex(x, y, z + 8.0F, x, z + 8.0F, colorTop);
                        vertex(x + 8.0F, y, z + 8.0F, x + 8.0F, z + 8.0F, colorTop);
                        vertex(x + 8.0F, y, z, x + 8.0F, z, colorTop);
                        vertex(x, y, z, x, z, colorTop);
                    }
                    // Côtés : seulement ceux tournés vers la caméra, qui est dans les dalles centrales.
                    if (tileX > -1) {
                        for (int i = 0; i < 8; i++) {
                            float faceX = x + (float) i;
                            float u = x + (float) i + 0.5F;
                            vertex(faceX, 0.0F, z + 8.0F, u, z + 8.0F, colorX);
                            vertex(faceX, THICKNESS, z + 8.0F, u, z + 8.0F, colorX);
                            vertex(faceX, THICKNESS, z, u, z, colorX);
                            vertex(faceX, 0.0F, z, u, z, colorX);
                        }
                    }
                    if (tileX <= 1) {
                        for (int i = 0; i < 8; i++) {
                            float faceX = x + (float) i + 1.0F - INSET;
                            float u = x + (float) i + 0.5F;
                            vertex(faceX, 0.0F, z + 8.0F, u, z + 8.0F, colorX);
                            vertex(faceX, THICKNESS, z + 8.0F, u, z + 8.0F, colorX);
                            vertex(faceX, THICKNESS, z, u, z, colorX);
                            vertex(faceX, 0.0F, z, u, z, colorX);
                        }
                    }
                    if (tileZ > -1) {
                        for (int i = 0; i < 8; i++) {
                            float faceZ = z + (float) i;
                            float v = z + (float) i + 0.5F;
                            vertex(x, THICKNESS, faceZ, x, v, colorZ);
                            vertex(x + 8.0F, THICKNESS, faceZ, x + 8.0F, v, colorZ);
                            vertex(x + 8.0F, 0.0F, faceZ, x + 8.0F, v, colorZ);
                            vertex(x, 0.0F, faceZ, x, v, colorZ);
                        }
                    }
                    if (tileZ <= 1) {
                        for (int i = 0; i < 8; i++) {
                            float faceZ = z + (float) i + 1.0F - INSET;
                            float v = z + (float) i + 0.5F;
                            vertex(x, THICKNESS, faceZ, x, v, colorZ);
                            vertex(x + 8.0F, THICKNESS, faceZ, x + 8.0F, v, colorZ);
                            vertex(x + 8.0F, 0.0F, faceZ, x + 8.0F, v, colorZ);
                            vertex(x, 0.0F, faceZ, x, v, colorZ);
                        }
                    }
                }
            }
            vertexCount = DATA.position() / STRIDE;
            DATA.flip();

            if (buffer == 0) {
                buffer = GL15.glGenBuffers();
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, DATA, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }

        /** {@code cellU}/{@code cellV} : coordonnées de texture en cases, converties ici en fraction de la texture. */
        private static void vertex(float x, float y, float z, float cellU, float cellV, int color) {
            DATA.putFloat(x).putFloat(y).putFloat(z);
            DATA.putFloat(cellU * TEXEL).putFloat(cellV * TEXEL);
            DATA.putInt(color);
        }

        static void bind() {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 12L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, 20L);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        }

        static void draw() {
            GL11.glDrawArrays(GL11.GL_QUADS, 0, vertexCount);
        }

        static void unbind() {
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }
}
