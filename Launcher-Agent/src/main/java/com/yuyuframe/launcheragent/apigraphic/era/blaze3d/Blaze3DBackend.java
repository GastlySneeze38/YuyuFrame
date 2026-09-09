package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.backend.UiBackend;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

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
}
