package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.McReflect;
import com.yuyuframe.launcheragent.runtime.modules.LauncherModule;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

import java.lang.reflect.Method;

/**
 * Teinte l'écran (vignette) quand la vie descend sous un seuil réglable —
 * port de PvP-Mod LowHealthTintConfig/LowHealthTintHandler.
 *
 * Original : Forge {@code RenderGameOverlayEvent.Post} + un vrai dégradé
 * NanoVG. Ici : {@link #onRenderOverlay} (appelé par ModuleRegistry depuis le
 * Mixin de rendu global, même endroit que le HUD) + un dégradé APPROXIMÉ par
 * bandes empilées (UiRenderer ne sait dessiner que des rectangles de couleur
 * UNIE, pas de vrai dégradé shader) — suffisamment lisible pour l'usage
 * (avertissement visuel), pas un rendu pixel-perfect de l'original.
 */
public final class LowHealthTintModule extends LauncherModule {

    private static final int BANDS = 10;

    @ConfigSlider(name = "Seuil (% de vie)", category = "Réglages", min = 5f, max = 100f, step = 5f)
    public float threshold = 30f;

    @ConfigSlider(name = "Opacité max (%)", category = "Réglages", min = 5f, max = 100f, step = 5f)
    public float maxOpacityPercent = 35f;

    @ConfigSlider(name = "Largeur du dégradé (% écran)", category = "Réglages", min = 10f, max = 50f, step = 5f)
    public float vignetteWidthPercent = 25f;

    @ConfigColor(name = "Couleur", category = "Réglages")
    public UiColor color = new UiColor(255, 0, 0, 255);

    public LowHealthTintModule() {
        super("low-health-tint", "Teinte vie basse", "Teinte l'écran quand la vie descend sous un seuil", false);
    }

    @Override
    public void onRenderOverlay(UiRenderer renderer, int vpWidth, int vpHeight) {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
            if (player == null) return;

            Method getHealth = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getHealth");
            Method getMaxHealth = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getMaxHealth");
            if (getHealth == null || getMaxHealth == null) return;

            float maxHealth = (float) getMaxHealth.invoke(player);
            if (maxHealth <= 0f) return;
            float healthPercent = (float) getHealth.invoke(player) / maxHealth * 100f;
            if (healthPercent >= threshold) return;

            float ratio = Math.max(0f, Math.min(1f, 1f - healthPercent / threshold));
            float maxOpacity = maxOpacityPercent / 100f;
            int alpha = (int) (ratio * maxOpacity * 255f);
            if (alpha <= 0) return;

            float vSize = Math.min(vpWidth, vpHeight) * (vignetteWidthPercent / 100f);
            drawVignette(renderer, vpWidth, vpHeight, vSize, alpha);
        } catch (Throwable ignored) {}
    }

    private void drawVignette(UiRenderer renderer, int vpWidth, int vpHeight, float vSize, int edgeAlpha) {
        for (int i = 0; i < BANDS; i++) {
            float t0 = (float) i / BANDS;
            float bandAlpha = (edgeAlpha / 255f) * (1f - t0);
            UiColor band = new UiColor(color.r, color.g, color.b, bandAlpha);
            float bandSize = vSize / BANDS;

            // Haut
            renderer.drawRoundedRect(0, vpHeight - (t0 * vSize) - bandSize, vpWidth, vpHeight - (t0 * vSize), 0, band, vpWidth, vpHeight);
            // Bas
            renderer.drawRoundedRect(0, t0 * vSize, vpWidth, t0 * vSize + bandSize, 0, band, vpWidth, vpHeight);
            // Gauche
            renderer.drawRoundedRect(t0 * vSize, 0, t0 * vSize + bandSize, vpHeight, 0, band, vpWidth, vpHeight);
            // Droite
            renderer.drawRoundedRect(vpWidth - (t0 * vSize) - bandSize, 0, vpWidth - (t0 * vSize), vpHeight, 0, band, vpWidth, vpHeight);
        }
    }
}
