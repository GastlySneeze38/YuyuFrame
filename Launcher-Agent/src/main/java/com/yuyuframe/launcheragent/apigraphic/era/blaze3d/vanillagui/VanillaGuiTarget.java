package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DBlur;

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
    /** Implémentation de l'état de GUI pour la version en cours — posée par {@link #begin}. */
    private static VanillaGuiSink sink;
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
        VanillaGuiSink target = VanillaGuiSinks.active();
        if (target == null) return false;
        // accepts() AVANT tout : sur une version où l'état de GUI n'est pas
        // atteignable, mieux vaut renoncer ici (l'appelant garde son chemin)
        // que dessiner tout le HUD dans le vide, primitive par primitive.
        if (!target.accepts(hookContext)) return false;
        int guiWidth = target.guiWidth(hookContext);
        if (guiWidth <= 0 || fbWidth <= 0 || fbHeightPx <= 0) return false;
        // Précompilation des deux pipelines UNE FOIS PAR PASSE, pas une fois
        // par primitive (2026-08-30). Chaque appel traverse une invocation
        // réflexive de precompilePipeline ; le faire par rect et par chaîne de
        // texte, à chaque frame, était le poste de coût le plus bête du
        // nouveau chemin. Reste appelé à chaque frame et non une seule fois :
        // un rechargement de ressources (F3+T) vide le cache de pipelines du
        // device, et l'appel est un no-op quand le pipeline y est déjà.
        if (!target.ensureCompiled()) {
            return false;
        }
        context = hookContext;
        sink = target;
        guiScale = (float) fbWidth / (float) guiWidth;
        fbHeight = fbHeightPx;
        glassChainDone = false;
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
        if (sink.roundedRect(context,
            Math.min(x1, x2) / guiScale, guiTop, Math.max(x1, x2) / guiScale, guiBottom,
            rTopLeft / guiScale, rTopRight / guiScale,
            rBottomLeft / guiScale, rBottomRight / guiScale,
            color)) return true;
        return consumedAfterFailure("rect arrondi");
    }

    /**
     * Icône RGBA — voir {@link IconElement}.
     *
     * <p>Même conversion de repère que {@link #roundedRect} : {@code y1}/{@code y2}
     * arrivent en Y vers le HAUT, le plus grand est donc le haut de l'écran et
     * devient le plus petit une fois converti.
     */
    public static boolean icon(String cacheKey, java.awt.image.BufferedImage img,
                               float x1, float y1, float x2, float y2, float alpha,
                               int vpWidth, int vpHeight) {
        if (context == null) return false;
        float guiTop = (fbHeight - Math.max(y1, y2)) / guiScale;
        float guiBottom = (fbHeight - Math.min(y1, y2)) / guiScale;
        if (sink.icon(context, cacheKey, img,
            Math.min(x1, x2) / guiScale, guiTop, Math.max(x1, x2) / guiScale, guiBottom, alpha)) return true;
        return consumedAfterFailure("icone");
    }

    /**
     * Dégradé de bord plein écran — voir {@link VignetteElement}.
     *
     * <p>Le quad couvre TOUT l'écran, la conversion se limite donc à une
     * division par l'échelle : pas d'inversion d'axe à faire, l'écran entier
     * est symétrique.
     *
     * <p>Retourne {@code true} MÊME si le pipeline est indisponible, dès lors
     * que la cible est armée. C'est délibéré : le repli de l'appelant est un
     * dessin en OpenGL brut, et c'est exactement ce qu'il ne faut plus faire
     * au milieu de la passe GUI de vanilla — c'était la cause des corruptions
     * d'état sur ce bracket. Ne rien afficher vaut mieux que corrompre la
     * frame ; l'échec est journalisé une fois par {@code VanillaGuiLayer}.
     */
    public static boolean vignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        if (context == null) return false;
        sink.vignette(context, 0f, 0f, vpWidth / guiScale, fbHeight / guiScale,
            vSize / guiScale, edgeColor);
        return true;
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
        if (sink.text(context, font, content, guiX, guiBaseline, guiScaleText, color)) return true;
        return consumedAfterFailure("texte");
    }

    /**
     * Calcule la chaîne de flou pour cette frame, IMMÉDIATEMENT.
     *
     * <p>La mettre en file la ferait arriver après la soumission de la GUI par
     * vanilla : les panneaux échantillonneraient le flou de la frame
     * précédente. Ici la scène est déjà rendue, la source est valide.
     *
     * @return {@code true} si la chaîne est prête — sinon l'appelant doit
     *         retomber sur un fond plein.
     */
    public static boolean beginGlassFrame(int passes, int vpWidth, int vpHeight) {
        if (context == null) return false;
        // Pas de composite de verre sur cette version : ne pas payer la chaîne
        // de flou (plusieurs passes plein écran) pour un résultat jamais dessiné.
        if (sink == null || !sink.supportsGlassPanel()) return false;
        // IDEMPOTENT sur la durée d'une passe : chaque panneau appelle
        // ensureGlassChain avant de se dessiner, mais UNE chaîne suffit pour
        // tous. Sans ce garde, six panneaux HUD = six chaînes par frame.
        //
        // L'ancien garde (HudPanelRenderer) déduisait « nouvelle frame » de la
        // TAILLE de la file Blaze3D. Il ne tient plus ici : le HUD n'alimente
        // plus cette file, elle reste vide, et le garde laissait tout passer.
        if (glassChainDone) return true;
        glassChainDone = Blaze3DBlur.renderChainNow(passes, vpWidth, vpHeight);
        return glassChainDone;
    }

    private static boolean glassChainDone;

    /**
     * Panneau de verre, rayons dans l'ordre du MOTEUR (bas-gauche, bas-droit,
     * haut-gauche, haut-droit) — réordonnés ici comme pour
     * {@link #roundedRect}.
     */
    public static boolean glassPanel(float x1, float y1, float x2, float y2,
                                     float rBottomLeft, float rBottomRight, float rTopLeft, float rTopRight,
                                     UiColor tint, UiColor background, int vpWidth, int vpHeight) {
        if (context == null) return false;
        float guiTop = (fbHeight - Math.max(y1, y2)) / guiScale;
        float guiBottom = (fbHeight - Math.min(y1, y2)) / guiScale;
        return sink.glassPanel(context,
            Math.min(x1, x2) / guiScale, guiTop, Math.max(x1, x2) / guiScale, guiBottom,
            rTopLeft / guiScale, rTopRight / guiScale,
            rBottomLeft / guiScale, rBottomRight / guiScale,
            tint, background);
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

    /**
     * La sink a REFUSÉ un dessin alors que la passe GUI est armée.
     *
     * <p>On renvoie {@code true} — « consommé » — pour que l'appelant ne se
     * rabatte SURTOUT PAS sur la file Blaze3D. Ce repli-là ne réparait rien :
     * il redessinait après la présentation de la frame, donc PAR-DESSUS le chat
     * et toute la GUI vanilla, en donnant l'illusion que ça marchait. Vécu en
     * v1068 : HUD bien visible, mais au mauvais z-order, et la vraie erreur
     * noyée dans le journal.
     *
     * <p>Ne rien dessiner et le dire vaut mieux que dessiner au mauvais endroit.
     */
    private static boolean consumedAfterFailure(String what) {
        reportOnce(what + " refusé par la sink « " + (sink != null ? sink.id() : "?")
            + " » alors que la passe GUI est armée — rien dessiné, et AUCUN repli sur la file"
            + " (il masquerait l'erreur)");
        return true;
    }

    private static String lastReport;

    /** Une raison distincte n'est journalisée qu'une fois — sinon c'est un message par primitive et par frame. */
    private static void reportOnce(String message) {
        if (message.equals(lastReport)) return;
        lastReport = message;
        LauncherLog.err("[VanillaGuiTarget] " + message);
    }

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
            if (!sink.text(context, t.font, t.content, t.x, t.baseline, t.scale, t.color)) {
                consumedAfterFailure("texte (lot)");
            }
        }
        pending.clear();
    }
}
