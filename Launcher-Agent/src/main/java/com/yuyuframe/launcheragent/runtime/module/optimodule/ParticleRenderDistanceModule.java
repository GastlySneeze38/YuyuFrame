package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Arrête de rendre les particules (fumée, flammes, débris de bloc...) au-delà
 * d'une distance configurable. Vérifié par désassemblage bytecode réel de
 * {@code ParticleManager} (official bec) : le vanilla plafonne déjà chaque
 * bucket de particules à 4000 (le plus vieux est retiré au-delà), mais le
 * RENDU (boucle dans {@code renderParticles}) ne fait AUCUN culling par
 * distance — une particule à 200 blocs coûte le même passage dans
 * {@code Particle.draw()} (calcul de billboard + écriture dans le
 * BufferBuilder) qu'une particule collée à la caméra. C'est ce qui explique
 * l'effondrement de FPS avec un mur de particules d'explosion/lave (ex:
 * Hypixel Disaster) : des milliers de particules simultanées, toutes
 * rendues peu importe la distance. Le tick (physique/durée de vie) continue
 * normalement même pour les particules coupées visuellement — seul le coût
 * CPU du rendu est évité.
 */
public final class ParticleRenderDistanceModule extends LauncherModule {

    public float maxDistance = 32f;

    @Override
    protected void settings(SettingList s) {
        s.slider("maxDistance", "Distance max (blocs)", "Réglages", 8f, 64f, 4f,
            () -> maxDistance, v -> maxDistance = v);
    }

    public ParticleRenderDistanceModule() {
        // Nom raccourci — voir TileEntityRenderDistanceModule pour le pourquoi (même onglet groupé "Distance de rendu").
        super("particle-render-distance", "Particules", "Ne rend pas les particules (fumée, flammes, débris) au-delà d'une distance donnée — gros gain sur explosions/lave en masse", true);
    }
}
