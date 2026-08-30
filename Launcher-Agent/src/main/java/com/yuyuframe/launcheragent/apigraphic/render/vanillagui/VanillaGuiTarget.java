package com.yuyuframe.launcheragent.apigraphic.render.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DGuiRoundedRect;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DGuiText;

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
        // Précompilation des deux pipelines UNE FOIS PAR PASSE, pas une fois
        // par primitive (2026-08-30). Chaque appel traverse une invocation
        // réflexive de precompilePipeline ; le faire par rect et par chaîne de
        // texte, à chaque frame, était le poste de coût le plus bête du
        // nouveau chemin. Reste appelé à chaque frame et non une seule fois :
        // un rechargement de ressources (F3+T) vide le cache de pipelines du
        // device, et l'appel est un no-op quand le pipeline y est déjà.
        if (!Blaze3DGuiRoundedRect.ensureCompiled() | !Blaze3DGuiText.ensureCompiled()) {
            // `|` et non `||` : les DEUX doivent être tentés, sinon un échec du
            // premier empêcherait le second de se compiler pour toujours.
            return false;
        }
        context = hookContext;
        guiScale = (float) fbWidth / (float) guiWidth;
        fbHeight = fbHeightPx;
        return true;
    }

    /** Désarme — À APPELER DANS UN {@code finally}, sinon tout le rendu suivant partirait dans un état de GUI périmé. */
    public static void end() {
        // Filet : un lot laissé ouvert (exception au milieu du rendu d'un
        // module) engloutirait silencieusement tout son texte. On le vide
        // plutôt que de le perdre — même précaution que Blaze3DText, qui
        // détecte les lots non refermés.
        if (batching) {
            flushPending();
            batching = false;
        }
        context = null;
    }

    public static boolean isArmed() {
        return context != null;
    }

    /** @return {@code true} si le dessin a été pris en charge par la voie vanilla. */
    public static boolean roundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color,
                               int vpWidth, int vpHeight) {
        return roundedRect(x1, y1, x2, y2, radius, radius, radius, radius, color, vpWidth, vpHeight);
    }

    /**
     * Variante à RAYON PAR COIN. Les paramètres suivent l'ordre du moteur —
     * bas-gauche, bas-droit, haut-gauche, haut-droit — et sont réordonnés ici
     * pour la convention Y-DOWN de la GUI vanilla.
     *
     * <p>Le « bas » du moteur reste le bas de l'ÉCRAN : l'inversion d'axe
     * change les nombres, pas la géométrie. Seul l'ordre des paramètres diffère
     * entre les deux API, d'où ce réordonnancement plutôt qu'une inversion.
     */
    public static boolean roundedRect(float x1, float y1, float x2, float y2,
                                      float rBottomLeft, float rBottomRight, float rTopLeft, float rTopRight,
                                      UiColor color, int vpWidth, int vpHeight) {
        if (context == null) return false;
        // y1/y2 sont en Y-UP : le plus GRAND est le haut de l'écran, donc le
        // plus PETIT une fois converti.
        float guiTop = (fbHeight - Math.max(y1, y2)) / guiScale;
        float guiBottom = (fbHeight - Math.min(y1, y2)) / guiScale;
        return VanillaGuiLayer.roundedRect(context,
            Math.min(x1, x2) / guiScale, guiTop, Math.max(x1, x2) / guiScale, guiBottom,
            rTopLeft / guiScale, rTopRight / guiScale,
            rBottomLeft / guiScale, rBottomRight / guiScale,
            color);
    }

    /**
     * @param y ligne de base en Y-UP — {@code TextElement} raisonne en Y-DOWN,
     *          d'où l'inversion ici et non chez lui.
     */
    public static boolean text(UiFont font, String content, float x, float y, UiColor color, float scale,
                        int vpWidth, int vpHeight) {
        if (context == null) return false;
        float guiX = x / guiScale;
        float guiBaseline = (fbHeight - y) / guiScale;
        float guiScaleText = scale / guiScale;
        if (batching) {
            pending.add(new PendingText(font, content, guiX, guiBaseline, guiScaleText, color));
            return true;
        }
        return VanillaGuiLayer.text(context, font, content, guiX, guiBaseline, guiScaleText, color);
    }

    // ── Lot de texte ─────────────────────────────────────────────────────────
    //
    // OPTIMISATION (2026-08-30) : vanilla regroupe les éléments consécutifs qui
    // partagent pipeline ET texture — dès que l'un des deux change, il ferme le
    // maillage courant et en ouvre un autre (voir GuiRenderer.addElementToMesh).
    // Le HUD émet naturellement panneau, texte, panneau, texte… : chaque
    // alternance coûtait donc une rupture de maillage, soit un dessin par
    // primitive.
    //
    // Le lot diffère TOUT le texte jusqu'à sa fermeture : l'ordre devient
    // [tous les panneaux][tout le texte], soit deux maillages au lieu de deux
    // par élément HUD. C'est exactement le rôle que jouait déjà
    // Blaze3DText.beginBatch sur l'ancien chemin, pour la même raison.
    //
    // Le z-order y gagne aussi en clarté : tout le texte passe au-dessus de
    // tous les fonds, ce que le HUD veut de toute façon (les éléments HUD ne se
    // chevauchent pas entre eux).

    private static final java.util.List<PendingText> pending = new java.util.ArrayList<>();
    private static boolean batching;

    private static final class PendingText {
        final UiFont font; final String content; final float x, baseline, scale; final UiColor color;
        PendingText(UiFont font, String content, float x, float baseline, float scale, UiColor color) {
            this.font = font; this.content = content; this.x = x;
            this.baseline = baseline; this.scale = scale; this.color = color;
        }
    }

    /** @return {@code true} si le lot est pris en charge ici (cible armée). */
    public static boolean beginTextBatch() {
        if (context == null) return false;
        batching = true;
        return true;
    }

    /** @return {@code true} si le lot était pris en charge ici. */
    public static boolean endTextBatch() {
        if (context == null) return false;
        flushPending();
        batching = false;
        return true;
    }

    private static void flushPending() {
        if (pending.isEmpty()) return;
        for (PendingText t : pending) {
            VanillaGuiLayer.text(context, t.font, t.content, t.x, t.baseline, t.scale, t.color);
        }
        pending.clear();
    }
}
