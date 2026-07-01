package com.yuyuframe.launcheragent.runtime.hud;

/**
 * Un élément HUD déplaçable — un mod déclare son élément ici (voir
 * HudRegistry.register) pour qu'il apparaisse à la fois dans l'éditeur
 * (UiHudEditorScreen, ouvert depuis la sidebar de l'accueil) ET en jeu
 * (HudOverlayRenderer) — même rendu dans les deux cas (voir HudPanelRenderer).
 *
 * Position stockée comme (ancre + décalage en pixels), PAS en coordonnées
 * absolues — voir HudAnchor.
 *
 * {@code w}/{@code h} ne sont PLUS librement réglables indépendamment l'un de
 * l'autre — après inspection du fonctionnement réel d'OneConfig (constaté
 * bien meilleur : texte toujours bien placé, jamais "désolidarisé" de la
 * boîte), le principe retenu est le SIEN : une taille de base ({@link #naturalSize()},
 * calculée depuis le contenu réel) + un seul multiplicateur ({@link #scale}),
 * {@code w = naturalW * scale} et {@code h = naturalH * scale} TOUJOURS —
 * jamais étirées indépendamment. Voir {@link #setScale} (seul point d'entrée
 * pour changer la taille) et UiHudBox (poignée de redimensionnement
 * DIAGONALE UNIQUEMENT, qui ne fait que dériver un nouveau scale).
 *
 * Position gardée UNIQUEMENT en mémoire pour cette première passe — remise
 * aux valeurs par défaut à chaque relance de l'agent (pas encore de
 * sauvegarde disque, viendra avec le chantier persistance général).
 *
 * {@code locked}/{@code showWhenScreenOpen}/{@code paddingX}/{@code paddingY}/
 * {@code scale} — réglages génériques façon OneConfig, exposés
 * automatiquement dans la page de config d'un module qui possède un élément
 * HUD (voir ConfigScreenBuilder + runtime.modules.HudElementOwner) — PAS
 * repris : couleur de fond/bordure/coins personnalisés par élément (jugés
 * superflus, le panneau partagé HudPanelRenderer suffit) et le dropdown
 * "Position Alignment" d'OneConfig (redondant avec notre système d'ancre
 * HudAnchor déjà en place).
 */
public class HudElement {

    /** En dessous, le contenu devient illisible — plancher de {@link #scale}, voir setScale/UiHudBox. */
    public static final float MIN_SCALE = 0.5f;
    /** Au-dessus, la boîte devient déraisonnablement grande — plafond de {@link #scale}. */
    public static final float MAX_SCALE = 4f;

    /** Fournit le contenu affiché (une ligne par entrée), recalculé à CHAQUE frame — voir runtime.modules.builtin.FpsModule/PingModule/CoordsModule (leur ContentSource nichée) pour des exemples réels. */
    public interface ContentSource {
        String[] lines();
    }

    /**
     * Rendu personnalisé, pour un contenu qui ne tient pas dans un simple
     * empilement de lignes de texte (grille de touches, pastilles colorées
     * d'effets de potion...) — voir runtime.modules.builtin.KeystrokesModule/
     * PotionEffectsModule (leur Renderer niché).
     * {@link HudPanelRenderer} dessine TOUJOURS le panneau de fond (même
     * style que les éléments texte, pour rester cohérent dans l'éditeur comme
     * en jeu), puis délègue le CONTENU à ce renderer plutôt qu'à
     * {@link ContentSource} quand celui-ci est fourni. {@code scale} est le
     * multiplicateur générique de l'élément (voir {@link HudElement#scale}) —
     * à appliquer par le renderer à ses propres constantes de taille.
     */
    public interface CustomRenderer {
        void draw(com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer renderer,
                  float x, float y, float w, float h, float scale, int vpWidth, int vpHeight);

        /** Taille "naturelle" à scale=1, {@code {largeur, hauteur}} — voir HudElement.naturalSize(), base de tout calcul de taille. */
        float[] naturalSize();
    }

    public final String id;
    public final String displayName;
    /** DÉRIVÉS de naturalSize()*scale — jamais assignés indépendamment, voir setScale(). */
    public float w, h;
    public final ContentSource content;
    public final CustomRenderer customRenderer;

    public HudAnchor anchor;
    public float offsetX, offsetY;

    // ── Réglages génériques façon OneConfig ─────────────────────────────────
    /** Empêche le glisser/redimensionner dans l'éditeur (voir UiHudBox). */
    public boolean locked = false;
    /** Reste visible même quand un écran NON custom (chat, inventaire, tout autre GUI vanilla/mod) est ouvert — voir HudOverlayRenderer.renderPersistent. */
    public boolean showWhenScreenOpen = false;
    public float paddingX = 0f, paddingY = 0f;
    /** Multiplicateur de taille — SEULE façon de changer w/h, voir setScale(). Ne jamais assigner directement (utiliser setScale, qui recalcule w/h en même temps). */
    public float scale = 1f;

    private final HudAnchor defaultAnchor;
    private final float defaultOffsetX, defaultOffsetY;

    public HudElement(String id, String displayName, HudAnchor anchor, float offsetX, float offsetY, ContentSource content) {
        this.id = id;
        this.displayName = displayName;
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.content = content;
        this.customRenderer = null;
        this.defaultAnchor = anchor;
        this.defaultOffsetX = offsetX;
        this.defaultOffsetY = offsetY;
        recomputeSize();
    }

    /** Variante rendu personnalisé — voir {@link CustomRenderer}. */
    public HudElement(String id, String displayName, HudAnchor anchor, float offsetX, float offsetY, CustomRenderer customRenderer) {
        this.id = id;
        this.displayName = displayName;
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.content = null;
        this.customRenderer = customRenderer;
        this.defaultAnchor = anchor;
        this.defaultOffsetX = offsetX;
        this.defaultOffsetY = offsetY;
        recomputeSize();
    }

    /**
     * SEUL point d'entrée pour changer la taille (voir UiHudBox, poignée
     * diagonale) — recalcule TOUJOURS w/h ensemble depuis naturalSize()*scale,
     * jamais l'un sans l'autre : c'est précisément ce qui manquait avant
     * (largeur/hauteur réglables indépendamment) et qui "désolidarisait" le
     * texte de sa boîte selon la façon dont elle avait été étirée.
     */
    public void setScale(float scale) {
        this.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        recomputeSize();
    }

    private void recomputeSize() {
        float[] size = naturalSize();
        this.w = size[0] * scale;
        this.h = size[1] * scale;
    }

    /**
     * Taille "naturelle" du contenu à scale=1, {@code {largeur, hauteur}} —
     * délègue à {@link CustomRenderer#naturalSize()} si présent, sinon calcule
     * depuis {@link ContentSource#lines()} (nombre de lignes × hauteur de
     * ligne, largeur = ligne la plus longue) avec les mêmes constantes que
     * HudPanelRenderer. Base de TOUT calcul de taille (voir recomputeSize()).
     */
    public float[] naturalSize() {
        if (customRenderer != null) {
            try {
                return customRenderer.naturalSize();
            } catch (Throwable t) {
                return new float[]{ 80f, 24f };
            }
        }
        String[] lines;
        try {
            lines = content.lines();
        } catch (Throwable t) {
            lines = new String[]{ "--" };
        }
        float naturalH = lines.length * HudPanelRenderer.LINE_H;
        float naturalW = HudPanelRenderer.PADDING;
        for (String line : lines) {
            naturalW = Math.max(naturalW, HudPanelRenderer.PADDING + com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont.REGULAR.textWidth(line, HudPanelRenderer.TEXT_SCALE));
        }
        return new float[]{ naturalW, naturalH };
    }

    /** Coin bas-gauche de la boîte (espace pixels framebuffer, comme UiWidget) pour un viewport donné. */
    public float screenX(int vpWidth) {
        switch (anchor) {
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
                return vpWidth - offsetX - w;
            case TOP_CENTER:
            case BOTTOM_CENTER:
                return vpWidth / 2f - w / 2f + offsetX;
            default: // TOP_LEFT, BOTTOM_LEFT
                return offsetX;
        }
    }

    public float screenY(int vpHeight) {
        switch (anchor) {
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
                return vpHeight - offsetY - h;
            default: // BOTTOM_*
                return offsetY;
        }
    }

    /** Recalcule offsetX/offsetY à partir d'une position absolue glissée (recomposée selon l'ancre actuelle). */
    public void setScreenPosition(float absX, float absY, int vpWidth, int vpHeight) {
        switch (anchor) {
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
                offsetX = vpWidth - absX - w;
                break;
            case TOP_CENTER:
            case BOTTOM_CENTER:
                offsetX = absX - (vpWidth / 2f - w / 2f);
                break;
            default:
                offsetX = absX;
        }
        switch (anchor) {
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
                offsetY = vpHeight - absY - h;
                break;
            default:
                offsetY = absY;
        }
    }

    /** Bouton "Réinitialiser la position" (voir ConfigScreenBuilder) — remet ancre+décalage tels que déclarés à la construction, PAS la taille/l'échelle (volontairement laissées telles quelles). */
    public void resetPosition() {
        this.anchor = defaultAnchor;
        this.offsetX = defaultOffsetX;
        this.offsetY = defaultOffsetY;
    }
}
