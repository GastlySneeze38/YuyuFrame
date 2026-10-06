package com.yuyuframe.launcheragent.apimixin.v1_8_9.text;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.FloatBuffer;

/**
 * Regroupe les caractères d'une chaîne en un seul envoi à la carte graphique.
 *
 * <p>Le {@code TextRenderer} de la 1.8.9 dessine chaque caractère à part, en
 * mode immédiat : {@code glBegin}, quatre {@code glTexCoord2f}, quatre
 * {@code glVertex3f}, {@code glEnd} — dix appels par caractère.
 * {@link TextRendererBatchMixin189} détourne ces appels ici : les sommets
 * s'accumulent dans un tampon, envoyé en un {@code glDrawArrays} quand la
 * chaîne est finie.
 *
 * <p><b>Rien n'est recalculé</b> : positions et coordonnées de texture sont
 * celles que vanilla passait à OpenGL. L'image doit être identique.
 *
 * <p>Le tampon ne porte ni couleur ni texture : il est dessiné avec l'état
 * OpenGL courant. Il doit donc être vidé <b>avant</b> tout changement de cet
 * état — changement de page de police, de couleur (codes §), ou trait de
 * soulignement que vanilla trace entre deux caractères. C'est le mixin qui
 * appelle {@link #flush()} à ces endroits.
 *
 * <p>Classe normale à côté du mixin, et non méthodes statiques sur lui : Mixin
 * refuse qu'une classe mixin soit référencée directement. Aucun type du jeu.
 * Fil de rendu seulement.
 */
public final class TextBatch189 {

    private TextBatch189() {
    }

    /** Suit le module Optimisation, sans réglage propre. Faux = les appels repartent tels quels vers OpenGL. */
    private static volatile boolean enabled = true;

    /** Position (2) + texture (2). Vanilla passe toujours z = 0. */
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;
    private static final int MAX_QUADS = 2048;

    private static final FloatBuffer BUFFER = BufferUtils.createFloatBuffer(MAX_QUADS * 4 * FLOATS_PER_VERTEX);

    /** Les quatre sommets du caractère en cours, dans l'ordre où vanilla les émet (bande de triangles). */
    private static final float[] STRIP = new float[4 * FLOATS_PER_VERTEX];
    private static int stripVertex;
    private static float pendingU, pendingV;

    private static int quads;

    /** Page de police des caractères en attente : {@link #PAGE_NONE}, {@link #PAGE_ASCII} ou une page unicode. */
    private static final int PAGE_NONE = Integer.MIN_VALUE;
    public static final int PAGE_ASCII = -1;
    private static int page = PAGE_NONE;

    /** Vrai entre le premier détournement d'un caractère et son {@code glEnd}. */
    private static boolean capturing;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    // ── Bornes d'une chaîne ──────────────────────────────────────────────────

    /** Début d'une chaîne : rien ne doit traîner d'un appel précédent interrompu par une exception. */
    public static void beginString() {
        quads = 0;
        page = PAGE_NONE;
        capturing = false;
    }

    /**
     * Avant qu'un caractère ne lie sa texture : si elle diffère de celle des
     * caractères en attente, ceux-ci partent d'abord, tant que la leur est
     * encore liée.
     */
    public static void beginGlyph(int glyphPage) {
        if (!enabled) {
            return;
        }
        if (page != glyphPage) {
            flush();
            page = glyphPage;
        }
    }

    // ── Les quatre appels détournés ──────────────────────────────────────────

    public static void glBegin(int mode) {
        if (!enabled) {
            GL11.glBegin(mode);
            return;
        }
        capturing = true;
        stripVertex = 0;
    }

    public static void glTexCoord2f(float u, float v) {
        if (!capturing) {
            GL11.glTexCoord2f(u, v);
            return;
        }
        pendingU = u;
        pendingV = v;
    }

    public static void glVertex3f(float x, float y, float z) {
        if (!capturing) {
            GL11.glVertex3f(x, y, z);
            return;
        }
        if (stripVertex < 4) {
            int at = stripVertex * FLOATS_PER_VERTEX;
            STRIP[at] = x;
            STRIP[at + 1] = y;
            STRIP[at + 2] = pendingU;
            STRIP[at + 3] = pendingV;
            stripVertex++;
        }
    }

    public static void glEnd() {
        if (!capturing) {
            GL11.glEnd();
            return;
        }
        capturing = false;
        if (stripVertex != 4) {
            return;
        }
        if (quads == MAX_QUADS) {
            flush();
        }
        // Bande de triangles 0-1-2-3 → quad 0-1-3-2 : mêmes deux triangles, même sens.
        int base = quads * 4 * FLOATS_PER_VERTEX;
        putVertex(base, 0);
        putVertex(base + FLOATS_PER_VERTEX, 1);
        putVertex(base + 2 * FLOATS_PER_VERTEX, 3);
        putVertex(base + 3 * FLOATS_PER_VERTEX, 2);
        quads++;
    }

    private static void putVertex(int at, int stripIndex) {
        int from = stripIndex * FLOATS_PER_VERTEX;
        BUFFER.put(at, STRIP[from]);
        BUFFER.put(at + 1, STRIP[from + 1]);
        BUFFER.put(at + 2, STRIP[from + 2]);
        BUFFER.put(at + 3, STRIP[from + 3]);
    }

    // ── Envoi ────────────────────────────────────────────────────────────────

    /** Dessine les caractères en attente avec la texture et la couleur courantes. Sans effet s'il n'y en a pas. */
    public static void flush() {
        if (quads == 0) {
            return;
        }
        int count = quads * 4;
        quads = 0;

        // Les pointeurs ci-dessous sont des adresses mémoire : avec un tampon
        // d'objets lié, OpenGL les prendrait pour des décalages dans ce tampon.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        BUFFER.position(0);
        GL11.glVertexPointer(2, GL11.GL_FLOAT, STRIDE_BYTES, BUFFER);
        BUFFER.position(2);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE_BYTES, BUFFER);
        BUFFER.position(0);

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, count);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }
}
