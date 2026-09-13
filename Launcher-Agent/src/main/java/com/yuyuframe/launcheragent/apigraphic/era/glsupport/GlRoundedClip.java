package com.yuyuframe.launcheragent.apigraphic.era.glsupport;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Clip à coins arrondis par stencil (roadmap Phase 5.1) — commun aux ères gl2
 * et gl3.
 *
 * <p>Remplace {@code glScissor} (rectangle strict) quand la zone de clip doit
 * suivre un rect ARRONDI (modale, liste déroulante, défilement à coins
 * arrondis) : avec le scissor seul, le contenu est coupé en angle droit pile
 * sur un coin visuellement rond.
 *
 * <p>Du GL brut IDENTIQUE dans les deux ères — {@code glEnable(GL_STENCIL_TEST)},
 * un masque, puis {@code glStencilFunc(GL_EQUAL, …)}. Seul le rect qui peuple
 * le masque est propre à l'ère : il est donc passé par l'appelant (le
 * backend), qui le dessine avec SON pipeline. Vivait dans
 * {@code render/UiPrimitiveRenderer} jusqu'à la dissolution de ce paquet
 * (2026-09-13).
 *
 * <p>« Anti-aliasé » au sens où la frontière du masque SUIT la courbe
 * analytique du rect arrondi pixel par pixel (un fragment sous le seuil alpha
 * est rejeté, donc jamais écrit dans le stencil) — pas un mélange sous-pixel :
 * le stencil reste un masque entier 0/1, ce qui suffit à éliminer le défaut
 * visible (coin carré qui mord sur un coin rond).
 *
 * <p>Pas d'équivalent Blaze3D : les dessins y passent par une file DIFFÉRÉE,
 * et un vrai stencil demanderait de faire traverser la région de clip à
 * travers chaque élément jusqu'au {@code RenderPass} — écarté pour la même
 * raison que le scissor Blaze3D. Le fondu de bord de {@code UiScrollContainer}
 * reste le filet de sécurité là-bas.
 */
public final class GlRoundedClip {

    private static final int GL_STENCIL_TEST = 0x0B90;
    private static final int GL_STENCIL_BUFFER_BIT = 0x00000400;
    private static final int GL_ALWAYS = 0x0207;
    private static final int GL_EQUAL = 0x0202;
    private static final int GL_KEEP = 0x1E00;
    private static final int GL_REPLACE = 0x1E01;

    private final GlBridge gl;
    private boolean active;

    public GlRoundedClip(GlBridge gl) {
        this.gl = gl;
    }

    /**
     * @param drawMask dessine le rect arrondi du masque, en BLANC OPAQUE : la
     *        couleur n'est jamais écrite (masque couleur fermé), mais l'alpha
     *        doit rester au-dessus du seuil de rejet du shader pour que le
     *        stencil soit écrit PARTOUT à l'intérieur de la forme
     */
    public void begin(Runnable drawMask) {
        try {
            gl.glEnable(GL_STENCIL_TEST);
            // Passe 1 : écrit le masque, sans toucher au framebuffer couleur.
            gl.glClear(GL_STENCIL_BUFFER_BIT);
            gl.glColorMask(false, false, false, false);
            gl.glStencilFunc(GL_ALWAYS, 1, 0xFF);
            gl.glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
            gl.glStencilMask(0xFF);
            drawMask.run();
            // Passe 2 : seul le stencil posé (==1) laisse passer la suite.
            gl.glColorMask(true, true, true, true);
            gl.glStencilFunc(GL_EQUAL, 1, 0xFF);
            gl.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            gl.glStencilMask(0x00);
            active = true;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] beginRoundedClip: " + t);
            active = false;
        }
    }

    /** No-op si aucun clip n'est actif (ou si {@link #begin} a échoué). */
    public void end() {
        if (!active) return;
        active = false;
        try {
            gl.glStencilMask(0xFF);
            gl.glDisable(GL_STENCIL_TEST);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] endRoundedClip: " + t);
        }
    }
}
