package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.backend.UiBackend;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;

/**
 * Backend de l'ère Blaze3D (1.21.11 – 26.x) — première implémentation du
 * contrat {@code UiBackend}.
 *
 * <h2>Ce qu'il fait aujourd'hui, et ce qu'il fera</h2>
 *
 * Il DÉLÈGUE à {@link VanillaGuiTarget}, c'est-à-dire au chemin « dessiner dans
 * l'état GUI de vanilla » déjà en place et validé en jeu. Aucune ligne de rendu
 * n'a été réécrite : le contrat s'insère devant le code existant, il ne le
 * remplace pas encore.
 *
 * <p>C'est ce qui rend cette étape sûre — le comportement est identique appel
 * pour appel, et ce qui est validé, c'est la FORME du contrat (une ère reçue,
 * un backend résolu une fois, une primitive qui passe par lui), pas une
 * nouvelle façon de dessiner.
 *
 * <h2>Ère ≠ phase de frame</h2>
 *
 * {@code VanillaGuiTarget} répond {@code false} hors de la passe GUI de vanilla
 * ({@code isArmed()} vaut « un contexte de dessin est ouvert en ce moment »,
 * pas « je suis sur cette version »). Ce {@code false} remonte tel quel à
 * l'appelant, qui reprend son chemin — exactement le comportement d'avant.
 *
 * <p>Ces deux questions restent empilées ici ; les séparer proprement demande
 * de déplacer la conduite de frame, ce qui touche le chemin de rendu par frame
 * et n'est pas de ce lot.
 */
public final class Blaze3DBackend implements UiBackend {

    /** Public sans argument : instancié par réflexion depuis {@code UiBackendRegistry}. */
    public Blaze3DBackend() {}

    @Override
    public String id() {
        return "blaze3d";
    }

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2, float radius,
                               UiColor color, int vpWidth, int vpHeight) {
        return VanillaGuiTarget.roundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
    }

    /**
     * Deux chemins, dans cet ordre — repris à l'identique de ce que faisait
     * {@code UiRenderer.drawText} puis {@code UiTextRenderer.drawTextModern}
     * avant le découpage :
     *
     * <ol>
     *   <li>pendant la passe GUI de vanilla, l'élément part dans le
     *       {@code GuiRenderState} ({@link VanillaGuiTarget}) — c'est ce qui
     *       donne le bon z-order vis-à-vis du HUD et du chat ;</li>
     *   <li>hors de cette passe, {@link Blaze3DText#queueDraw} met en file pour
     *       la frame suivante. JAMAIS de repli sur le pipeline SDF générique
     *       sur cette ère : il y est corrompu de façon non déterministe, et un
     *       texte absent vaut mieux qu'un texte parfois illisible.</li>
     * </ol>
     */
    @Override
    public boolean text(UiFont font, String content, float x, float y, UiColor color, float scale,
                        int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.text(font, content, x, y, color, scale, vpWidth, vpHeight)) return true;
        Blaze3DText.queueDraw(font, content, x, y, color, scale, vpWidth, vpHeight);
        return true;
    }

    // ── Primitives reprises de la façade (2026-09-09) ─────────────────────
    // Chacune était un « if (VanillaGuiTarget.x(...)) return; » en tête de la
    // méthode correspondante d'UiRenderer. Déplacées ici TELLES QUELLES, y
    // compris l'ordre des rayons par coin, qui diffère entre les deux API
    // (le moteur dit bas-gauche/bas-droit/haut-gauche/haut-droit, la GUI
    // vanilla attend l'ordre inverse en Y — voir VanillaGuiTarget).

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2,
                               float radiusBottomLeft, float radiusBottomRight,
                               float radiusTopLeft, float radiusTopRight,
                               UiColor color, int vpWidth, int vpHeight) {
        return VanillaGuiTarget.roundedRect(x1, y1, x2, y2,
            radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight,
            color, vpWidth, vpHeight);
    }

    @Override
    public boolean roundedRectHud(float x1, float y1, float x2, float y2, float radius,
                                  UiColor color, int vpWidth, int vpHeight) {
        return VanillaGuiTarget.roundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
    }

    @Override
    public boolean vignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        return VanillaGuiTarget.vignette(edgeColor, vSize, vpWidth, vpHeight);
    }

    @Override
    public boolean icon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                        float alpha, int vpWidth, int vpHeight) {
        return VanillaGuiTarget.icon(cacheKey, img, x, y, x + w, y + h, alpha, vpWidth, vpHeight);
    }

    // ── Capacités propres à cette ère ─────────────────────────────────────
    // Verre dépoli et lot de texte n'existent que sur Blaze3D. Les autres ères
    // héritent du défaut qui décline — plus besoin d'un test chez l'appelant.

    @Override
    public boolean vignetteAvailable() {
        return VanillaGuiTarget.isArmed();
    }

    @Override
    public boolean glassAvailable() {
        return Blaze3DBlur.isGlassAvailable();
    }

    @Override
    public boolean beginGlassFrame(int passes, int vpWidth, int vpHeight) {
        // Voie vanilla : la chaîne est calculée TOUT DE SUITE et non mise en
        // file — sinon elle arriverait après la soumission de la GUI, et les
        // panneaux échantillonneraient le flou de la frame précédente.
        if (VanillaGuiTarget.beginGlassFrame(passes, vpWidth, vpHeight)) return true;
        Blaze3DBlur.queueFrameChain(passes, vpWidth, vpHeight);
        return true;
    }

    /**
     * {@code false} quand la chaîne de flou n'a pas pu être calculée pour
     * cette frame : l'appelant dessine alors son aplat de repli. Ce n'est pas
     * un échec, c'est le comportement prévu — repris tel quel de la façade.
     */
    @Override
    public boolean glassPanel(float x1, float y1, float x2, float y2,
                              float radiusTopLeft, float radiusTopRight,
                              float radiusBottomLeft, float radiusBottomRight,
                              UiColor tint, float tintStrength, UiColor fallback,
                              int vpWidth, int vpHeight) {
        // Voie vanilla : un GuiElementRenderState qui échantillonne la chaîne
        // de flou.
        if (VanillaGuiTarget.glassPanel(x1, y1, x2, y2,
                radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight,
                tint, fallback, vpWidth, vpHeight)) return true;
        if (Blaze3DBlur.isGlassAvailable() && !VanillaGuiTarget.isArmed()) {
            Blaze3DBlur.queueGlassPanel(
                x1, y1, x2, y2, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
                tint, tintStrength, fallback.a, vpWidth, vpHeight);
            return true;
        }
        return false;
    }

    @Override
    public boolean blurredPanel(float x1, float y1, float x2, float y2,
                                float radiusTopLeft, float radiusTopRight,
                                float radiusBottomLeft, float radiusBottomRight,
                                int passes, UiColor tint, float tintStrength,
                                int vpWidth, int vpHeight) {
        Blaze3DBlur.queueBlurredPanel(
            x1, y1, x2, y2, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
            passes, tint, tintStrength, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean beginTextBatch() {
        // La voie vanilla a SON lot (voir VanillaGuiTarget) : même rôle,
        // regrouper tout le texte pour n'ouvrir qu'un maillage au lieu d'un
        // par chaîne.
        if (VanillaGuiTarget.beginTextBatch()) return true;
        Blaze3DText.beginBatch();
        return true;
    }

    @Override
    public boolean endTextBatch(int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.endTextBatch()) return true;
        Blaze3DText.endBatch(vpWidth, vpHeight);
        return true;
    }

    // ── Primitives dont le test d'ère vivait DANS UiPrimitiveRenderer ─────
    // Chaque corps est repris du « if (Blaze3DCore.isAvailable()) » de la
    // méthode correspondante. Deux d'entre elles ne dessinent RIEN sur cette
    // ère, et c'est délibéré : voir ci-dessous.

    /**
     * Ne dessine rien, et répond quand même « pris en charge ».
     *
     * <p>Repris du {@code if (Blaze3DCore.isAvailable()) return;} d'origine :
     * une vraie ombre demanderait un flou gaussien, impossible sur ce chemin.
     * Le {@code true} évite simplement à l'appelant de descendre dans un
     * chemin qui, lui, se contentait de sortir aussitôt.
     */
    @Override
    public boolean shadow(float x1, float y1, float x2, float y2, float radius, float blur, float spread,
                          UiColor color, int vpWidth, int vpHeight) {
        return true;
    }

    /** Ne dessine rien sur cette ère — même raison que {@link #shadow}. */
    @Override
    public boolean roundedRectBorder(float x1, float y1, float x2, float y2, float radius, float borderWidth,
                                     UiColor color, int vpWidth, int vpHeight) {
        return true;
    }

    @Override
    public boolean gradientRect(float x1, float y1, float x2, float y2, float radius,
                                UiColor colorBottom, UiColor colorTop, int vpWidth, int vpHeight) {
        Blaze3DGradient.queueGradientRect(x1, y1, x2, y2, radius, colorBottom, colorTop, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean gradientRect2D(float x1, float y1, float x2, float y2, float radius,
                                  UiColor colorBottomLeft, UiColor colorBottomRight,
                                  UiColor colorTopLeft, UiColor colorTopRight,
                                  int vpWidth, int vpHeight) {
        Blaze3DGradient.queueGradientRect2D(x1, y1, x2, y2, radius,
            colorBottomLeft, colorBottomRight, colorTopLeft, colorTopRight, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean multiStopGradientRect(float x1, float y1, float x2, float y2, float radius,
                                         UiGradientType type, float startX, float startY, float endX, float endY,
                                         UiColor[] colors, float[] positions, int vpWidth, int vpHeight) {
        Blaze3DGradient.queueMultiStopGradientRect(x1, y1, x2, y2, radius, type,
            startX, startY, endX, endY, colors, positions, vpWidth, vpHeight);
        return true;
    }

    /**
     * Pas de clip stencil sur cette ère — repris du {@code return} d'origine.
     * Le {@code true} des deux méthodes garde la paire cohérente : ouvrir sans
     * fermer, ou l'inverse, laisserait un stencil dans un état imprévisible.
     */
    @Override
    public boolean beginRoundedClip(float x1, float y1, float x2, float y2, float radius,
                                    int vpWidth, int vpHeight) {
        return true;
    }

    @Override
    public boolean endRoundedClip() {
        return true;
    }
}
