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
 */
public class HudElement {

    /** Fournit le contenu affiché (une ligne par entrée), recalculé à CHAQUE frame — voir FpsHudSource/PingHudSource/CoordsHudSource pour des exemples réels. */
    public interface ContentSource {
        String[] lines();
    }

    public final String id;
    public final String displayName;
    public float w, h;
    public final ContentSource content;

    public HudAnchor anchor;
    public float offsetX, offsetY;

    public HudElement(String id, String displayName, float w, float h, HudAnchor anchor, float offsetX, float offsetY, ContentSource content) {
        this.id = id;
        this.displayName = displayName;
        this.w = w;
        this.h = h;
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.content = content;
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
}
