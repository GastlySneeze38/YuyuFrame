package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

/**
 * Crosshair personnalisé — port du vrai PvP-Mod
 * ({@code CrosshairConfig}/{@code CrosshairHandler}, mode procédural
 * uniquement ici — le mode image PNG de PvP-Mod n'est pas repris, pas
 * demandé). Le vanilla est masqué par {@code MixinCrosshair189}
 * ({@code InGameHud.showCrosshair()} forcé à false), on dessine notre propre
 * croix ici à la place.
 */
public final class CrosshairModule extends LauncherModule {

    @ConfigSlider(name = "Taille", category = "Réglages", min = 1f, max = 32f, step = 1f)
    public float size = 8f;

    @ConfigSlider(name = "Épaisseur", category = "Réglages", min = 1f, max = 6f, step = 1f)
    public float thickness = 2f;

    @ConfigSlider(name = "Espacement central", category = "Réglages", min = 0f, max = 16f, step = 1f)
    public float gap = 3f;

    @ConfigToggle(name = "Point au lieu d'une croix", category = "Réglages")
    public boolean dotMode = false;

    @ConfigColor(name = "Couleur", category = "Réglages")
    public UiColor color = new UiColor(255, 255, 255, 255);

    public CrosshairModule() {
        super("custom-crosshair", "Crosshair personnalisé", "Remplace la croix de visée vanilla", false);
    }

    @Override
    public void onRenderOverlay(UiRenderer renderer, int vpWidth, int vpHeight) {
        try {
            float cx = vpWidth / 2f;
            float cy = vpHeight / 2f;

            if (dotMode) {
                renderer.drawRoundedRect(cx - thickness, cy - thickness, cx + thickness, cy + thickness, thickness, color, vpWidth, vpHeight);
                return;
            }

            // Gauche / droite / haut / bas — 4 barres autour du centre, avec l'espacement "gap"
            renderer.drawRoundedRect(cx - gap - size, cy - thickness / 2f, cx - gap, cy + thickness / 2f, 0, color, vpWidth, vpHeight);
            renderer.drawRoundedRect(cx + gap, cy - thickness / 2f, cx + gap + size, cy + thickness / 2f, 0, color, vpWidth, vpHeight);
            renderer.drawRoundedRect(cx - thickness / 2f, cy - gap - size, cx + thickness / 2f, cy - gap, 0, color, vpWidth, vpHeight);
            renderer.drawRoundedRect(cx - thickness / 2f, cy + gap, cx + thickness / 2f, cy + gap + size, 0, color, vpWidth, vpHeight);
        } catch (Throwable ignored) {}
    }
}
