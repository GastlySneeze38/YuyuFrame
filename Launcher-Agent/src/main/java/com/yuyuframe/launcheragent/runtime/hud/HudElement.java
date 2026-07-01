package com.yuyuframe.launcheragent.runtime.hud;

/**
 * Un élément HUD déplaçable — un mod déclare son élément ici (voir
 * HudRegistry.register) pour qu'il apparaisse à la fois dans l'éditeur
 * (UiHudEditorScreen, ouvert depuis la sidebar de l'accueil) ET en jeu
 * (HudOverlayRenderer) — même rendu dans les deux cas (voir HudPanelRenderer).
 *
 * Position stockée comme (ancre + décalage en pixels), PAS en coordonnées
 * absolues — voir HudAnchor. {@code w}/{@code h} sont l'empreinte affichée
 * (ajustable via la poignée de redimensionnement de l'éditeur, voir setSize) ;
 * le contenu réel (voir ContentSource) peut déborder si la boîte est trop petite.
 *
 * Position gardée UNIQUEMENT en mémoire pour cette première passe — remise
 * aux valeurs par défaut à chaque relance de l'agent (pas encore de
 * sauvegarde disque, viendra avec le chantier persistance général).
 *
 * {@code locked}/{@code showWhenScreenOpen}/{@code paddingX}/{@code paddingY}/
 * {@code scale} — réglages génériques façon OneConfig (voir capture d'écran
 * fournie par l'utilisateur de la config FPS d'OneConfig), exposés
 * automatiquement dans la page de config d'un module qui possède un élément
 * HUD (voir ConfigScreenBuilder + runtime.modules.HudElementOwner) — PAS
 * repris : couleur de fond/bordure/coins personnalisés par élément (jugés
 * superflus, le panneau partagé HudPanelRenderer suffit) et le dropdown
 * "Position Alignment" d'OneConfig (redondant avec notre système d'ancre
 * HudAnchor déjà en place).
 */
public class HudElement {

    /** Fournit le contenu affiché (une ligne par entrée), recalculé à CHAQUE frame — voir FpsHudSource/PingHudSource/CoordsHudSource pour des exemples réels. */
    public interface ContentSource {
        String[] lines();
    }

    /**
     * Rendu personnalisé, pour un contenu qui ne tient pas dans un simple
     * empilement de lignes de texte (grille de touches, pastilles colorées
     * d'effets de potion...) — voir KeystrokesHudRenderer/PotionEffectsHudRenderer.
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
    }

    public final String id;
    public final String displayName;
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
    /** Multiplicateur de taille du CONTENU affiché, indépendant de {@code w}/{@code h} (voir HudPanelRenderer). */
    public float scale = 1f;

    private final HudAnchor defaultAnchor;
    private final float defaultOffsetX, defaultOffsetY;

    public HudElement(String id, String displayName, float w, float h, HudAnchor anchor, float offsetX, float offsetY, ContentSource content) {
        this.id = id;
        this.displayName = displayName;
        this.w = w;
        this.h = h;
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.content = content;
        this.customRenderer = null;
        this.defaultAnchor = anchor;
        this.defaultOffsetX = offsetX;
        this.defaultOffsetY = offsetY;
    }

    /** Variante rendu personnalisé — voir {@link CustomRenderer}. */
    public HudElement(String id, String displayName, float w, float h, HudAnchor anchor, float offsetX, float offsetY, CustomRenderer customRenderer) {
        this.id = id;
        this.displayName = displayName;
        this.w = w;
        this.h = h;
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.content = null;
        this.customRenderer = customRenderer;
        this.defaultAnchor = anchor;
        this.defaultOffsetX = offsetX;
        this.defaultOffsetY = offsetY;
    }

    /** Contenu STATIQUE (texte fixe, jamais recalculé) — pratique pour un placeholder rapide sans écrire une vraie ContentSource. */
    public HudElement(String id, String displayName, float w, float h, HudAnchor anchor, float offsetX, float offsetY, String... staticLines) {
        this(id, displayName, w, h, anchor, offsetX, offsetY, (ContentSource) () -> staticLines);
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

    /** Redimensionne (éditeur HUD, poignée coin) — w/h ne sont plus figées comme au constructeur. */
    public void setSize(float w, float h) {
        this.w = w;
        this.h = h;
    }

    /** Bouton "Réinitialiser la position" (voir ConfigScreenBuilder) — remet ancre+décalage tels que déclarés à la construction, PAS la taille (w/h, volontairement laissée telle quelle). */
    public void resetPosition() {
        this.anchor = defaultAnchor;
        this.offsetX = defaultOffsetX;
        this.offsetY = defaultOffsetY;
    }
}
