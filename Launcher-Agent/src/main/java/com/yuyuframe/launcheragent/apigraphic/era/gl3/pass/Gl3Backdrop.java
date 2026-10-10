package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import org.lwjgl.opengl.GL11;

/**
 * Copie du fond — ce qui est DÉJÀ dessiné dans le framebuffer courant — dans
 * une texture échantillonnable. Source du verre dépoli ({@link Gl3Blur}) et
 * des modes de fusion ({@link Gl3Blend}).
 *
 * <h2>Pourquoi une copie</h2>
 *
 * On ne peut pas échantillonner le framebuffer dans lequel on dessine. Blaze3D
 * y arrive parce que la cible principale y est créée avec l'usage « texture »
 * ; ici la cible est celle du jeu, qu'on ne contrôle pas : on copie. En 1.8.9,
 * au moment où le hub dessine, c'est le framebuffer de Minecraft qui est lié
 * (lecture ET écriture), et c'est bien lui qu'on veut.
 *
 * <p>{@code glCopyTexSubImage2D} copie côté GPU, sans passer par la mémoire
 * du CPU. La texture n'est réallouée que si la taille du viewport change.
 */
public final class Gl3Backdrop {

    private int texture = -1;
    private int width, height;

    /**
     * Copie le framebuffer de lecture courant, taille {@code vpWidth × vpHeight}.
     *
     * @return la texture, liée sur l'unité 0 en sortie
     */
    public int capture(int vpWidth, int vpHeight) {
        return capture(vpWidth, vpHeight, null, 0f);
    }

    /**
     * La même copie, limitée aux rectangles {@code {x1, y1, x2, y2}} donnés
     * (élargis de {@code marginPx}) : copier tout l'écran à chaque image pour
     * quelques petits panneaux de HUD coûtait plus que leur flou. Hors de ces
     * rectangles, la texture garde ce qu'elle contenait — à ne pas lire.
     */
    public int capture(int vpWidth, int vpHeight, float[][] regions, float marginPx) {
        if (texture == -1 || width != vpWidth || height != vpHeight) {
            if (texture != -1) GL11.glDeleteTextures(texture);
            texture = Gl3Core.createTexture(vpWidth, vpHeight);
            width = vpWidth;
            height = vpHeight;
        } else {
            Gl3Core.bindTexture0(texture);
        }
        if (regions == null) {
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, vpWidth, vpHeight);
            return texture;
        }
        for (float[] r : regions) {
            int x0 = Math.max(0, (int) Math.floor(Math.min(r[0], r[2]) - marginPx));
            int y0 = Math.max(0, (int) Math.floor(Math.min(r[1], r[3]) - marginPx));
            int x1 = Math.min(vpWidth, (int) Math.ceil(Math.max(r[0], r[2]) + marginPx));
            int y1 = Math.min(vpHeight, (int) Math.ceil(Math.max(r[1], r[3]) + marginPx));
            if (x1 <= x0 || y1 <= y0) continue;
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x0, y0, x0, y0, x1 - x0, y1 - y0);
        }
        return texture;
    }
}
