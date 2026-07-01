package com.yuyuframe.launcheragent.runtime.hud;

/**
 * Un élément HUD déplaçable — un mod déclarera plus tard ses propres
 * éléments ici (voir HudRegistry.register) pour qu'ils apparaissent dans
 * l'éditeur (UiHudEditorScreen, ouvert depuis la sidebar de l'accueil).
 *
 * Position stockée comme (ancre + décalage en pixels), PAS en coordonnées
 * absolues — voir HudAnchor. {@code w}/{@code h} ne sont que l'empreinte
 * approximative affichée dans l'éditeur (aucun rendu HUD réel branché pour
 * l'instant, voir docs/LauncherAgent/index.md) : un vrai élément mettra à
 * jour ces dimensions selon son contenu réel le jour où il existera.
 *
 * Position gardée UNIQUEMENT en mémoire pour cette première passe — remise
 * aux valeurs par défaut à chaque relance de l'agent (pas encore de
 * sauvegarde disque, viendra avec le chantier persistance général).
 */
public class HudElement {

    public final String id;
    public final String displayName;
    public final float w, h;

    public HudAnchor anchor;
    public float offsetX, offsetY;

    public HudElement(String id, String displayName, float w, float h, HudAnchor anchor, float offsetX, float offsetY) {
        this.id = id;
        this.displayName = displayName;
        this.w = w;
        this.h = h;
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
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
}
