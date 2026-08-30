package com.yuyuframe.launcheragent.apigraphic.render.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;

/**
 * Commutateur « émettre dans l'état de GUI de vanilla » — armé le temps de la
 * passe GUI, désarmé le reste du temps.
 *
 * <h2>Rôle</h2>
 *
 * {@code UiRenderer} interroge cette classe au début des quelques primitives
 * que le HUD utilise réellement ({@code drawRoundedRect}, {@code drawText},
 * les lots de texte). Armée, elle détourne le dessin vers
 * {@link VanillaGuiLayer} ; désarmée, tout repart par la file Blaze3D
 * habituelle. Les modules HUD ne savent rien de tout ça — ils écrivent
 * toujours {@code renderer.drawText(...)}.
 *
 * <h2>Conversion de repère</h2>
 *
 * Le moteur parle en <b>pixels de framebuffer, Y vers le HAUT, origine en
 * bas à gauche</b>. La GUI vanilla parle en <b>pixels GUI, Y vers le BAS,
 * origine en haut à gauche</b>. La conversion se fait ICI, une fois pour
 * toutes :
 *
 * <pre>
 * guiX =  x / échelle
 * guiY = (hauteurFramebuffer - y) / échelle
 * </pre>
 *
 * L'échelle est déduite du rapport entre la largeur de framebuffer que
 * l'appelant transmet déjà et la largeur GUI que vanilla expose — pas de
 * lecture d'option, donc rien à resynchroniser si le joueur change son
 * réglage en cours de partie.
 *
 * <h2>Portée</h2>
 *
 * Le HUD seulement. Les écrans de menu gardent la file Blaze3D : ils
 * s'affichent seuls, sans rien à entrelacer avec du contenu vanilla, et leur
 * palette de primitives est bien plus large (dégradés, flou, icônes d'atlas,
 * effets) que ce qui est porté ici.
 */
public final class VanillaGuiTarget {
    private VanillaGuiTarget() {}

    private static Object context;
    private static float guiScale = 1f;
    private static int fbHeight;

    /**
     * Arme la cible pour la durée d'un rendu de HUD.
     *
     * @param hookContext le {@code GuiGraphicsExtractor} reçu par le hook.
     * @param fbWidth largeur du framebuffer, telle que le moteur la manipule.
     * @param fbHeightPx hauteur du framebuffer.
     * @return {@code false} si la voie n'est pas disponible — l'appelant doit
     *         alors rendre le HUD par le chemin habituel.
     */
    public static boolean begin(Object hookContext, int fbWidth, int fbHeightPx) {
        int guiWidth = VanillaGuiLayer.guiWidth(hookContext);
        if (guiWidth <= 0 || fbWidth <= 0 || fbHeightPx <= 0) return false;
        context = hookContext;
        guiScale = (float) fbWidth / (float) guiWidth;
        fbHeight = fbHeightPx;
        return true;
    }

    /** Désarme — À APPELER DANS UN {@code finally}, sinon tout le rendu suivant partirait dans un état de GUI périmé. */
    public static void end() {
        context = null;
    }

    public static boolean isArmed() {
        return context != null;
    }

    /** @return {@code true} si le dessin a été pris en charge par la voie vanilla. */
    public static boolean roundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color,
                               int vpWidth, int vpHeight) {
        if (context == null) return false;
        // y1/y2 sont en Y-UP : le plus GRAND est le haut de l'écran, donc le
        // plus PETIT une fois converti.
        float guiTop = (fbHeight - Math.max(y1, y2)) / guiScale;
        float guiBottom = (fbHeight - Math.min(y1, y2)) / guiScale;
        return VanillaGuiLayer.roundedRect(context,
            Math.min(x1, x2) / guiScale, guiTop, Math.max(x1, x2) / guiScale, guiBottom,
            radius / guiScale, color);
    }

    /**
     * @param y ligne de base en Y-UP — {@code TextElement} raisonne en Y-DOWN,
     *          d'où l'inversion ici et non chez lui.
     */
    public static boolean text(UiFont font, String content, float x, float y, UiColor color, float scale,
                        int vpWidth, int vpHeight) {
        if (context == null) return false;
        return VanillaGuiLayer.text(context, font, content,
            x / guiScale, (fbHeight - y) / guiScale, scale / guiScale, color);
    }
}
