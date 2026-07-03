package com.yuyuframe.launcheragent.runtime.ui.hud;

/**
 * Coin/bord de référence pour la position d'un élément HUD — une position
 * exprimée en (ancre + décalage en pixels) reste sensée quelle que soit la
 * résolution/taille de fenêtre, contrairement à des coordonnées absolues
 * figées (un élément ancré TOP_RIGHT reste "en haut à droite" après un
 * redimensionnement, il ne se retrouve jamais hors écran).
 */
public enum HudAnchor {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}
