package com.mojang.blaze3d.systems;

import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir {@link RenderSystem}.
 *
 * <p>Seule la surcharge à DEUX arguments est déclarée : le booléen à
 * {@code true} garde la plage de LOD complète (mips utilisés). Celle à un seul
 * argument fige le LOD à 0 — c'est le bug historique du texte « pixelisé », et
 * ne pas déclarer ici la mauvaise surcharge empêche de la rappeler par erreur.
 */
public abstract class SamplerCache {

    private SamplerCache() {
    }

    public GpuSampler getClampToEdge(FilterMode filter, boolean fullMipRange) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
