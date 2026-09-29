package com.mojang.blaze3d.pipeline;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>Opaque : l'état couleur d'un pipeline de référence est LU puis reposé tel
 * quel sur notre constructeur. Cette classe n'existe PAS en 1.21.11, où le même
 * réglage est éclaté en méthodes séparées du builder — c'est la seule vraie
 * divergence d'API entre les deux implémentations.
 */
public final class ColorTargetState {

    private ColorTargetState() {
    }
}
