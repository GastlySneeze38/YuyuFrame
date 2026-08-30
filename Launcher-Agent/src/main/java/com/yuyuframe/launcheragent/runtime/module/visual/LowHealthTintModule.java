package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import net.minecraft.client.player.LocalPlayer;

import com.yuyuframe.launcheragent.runtime.game.PlayerData;

/**
 * Teinte l'écran (vignette) quand la vie descend sous un seuil réglable —
 * port de PvP-Mod LowHealthTintConfig/LowHealthTintHandler.
 *
 * Original : Forge {@code RenderGameOverlayEvent.Post} + un vrai dégradé
 * NanoVG. Ici : {@link #onRenderOverlay} (appelé par ModuleRegistry depuis le
 * Mixin de rendu global, même endroit que le HUD) + {@link UiRenderer#drawEdgeVignette}
 * (dégradé calculé par pixel côté GPU, voir shader dédié dans UiRenderer).
 *
 * Repli sur {@link #drawVignetteBands} (bandes empilées, ancienne
 * implémentation) UNIQUEMENT si la compilation du shader échoue sur le
 * GPU/driver de l'utilisateur (voir {@code UiRenderer#isVignetteAvailable}) —
 * moins lisse (paliers visibles) mais fonctionne partout, aucun rect ne se
 * chevauche dans les coins (contrairement à la toute première version).
 */
public final class LowHealthTintModule extends LauncherModule {

    private static final int BANDS = 10;

    @ConfigSlider(name = "Seuil (% de vie)", category = "Réglages", min = 5f, max = 100f, step = 5f)
    public float threshold = 30f;

    // Baissé après retour utilisateur (bord encore visible même dans le noir
    // total, sans contraste de scène en cause) : un delta d'alpha plus petit
    // sur toute la largeur du dégradé laisse plus de marge aux 256 niveaux du
    // framebuffer 8-bit pour représenter la transition sans paliers visibles.
    @ConfigSlider(name = "Opacité max (%)", category = "Réglages", min = 5f, max = 100f, step = 5f)
    public float maxOpacityPercent = 18f;

    // Le dégradé shader (drawEdgeVignette) est un calcul PAR PIXEL basé sur la
    // distance au bord le plus proche — contrairement à l'ancien repli par
    // bandes (drawVignetteBands, limité à 50% pour éviter le chevauchement
    // haut/bas), aucune limite mathématique à respecter ici : au-delà de 50%
    // les zones de dégradé des bords opposés se chevauchent simplement au
    // centre (le min() des 4 distances gère ça nativement, jamais de double
    // comptage). Plafond/valeur par défaut relevés après retour utilisateur :
    // à 25% le dégradé restait perceptible comme un bord net même avec une
    // courbe lisse (smootherstep) — un dégradé plus LARGE, pas juste plus
    // lisse, était nécessaire pour que la transition soit vraiment invisible.
    @ConfigSlider(name = "Largeur du dégradé (% écran)", category = "Réglages", min = 10f, max = 80f, step = 5f)
    public float vignetteWidthPercent = 45f;

    @ConfigColor(name = "Couleur", category = "Réglages")
    public UiColor color = new UiColor(255, 0, 0, 255);

    public LowHealthTintModule() {
        super("low-health-tint", "Teinte vie basse", "Teinte l'écran quand la vie descend sous un seuil",
            "Teinte l'écran à vie basse", false);
        iconUrl = icons8("heart-monitor");
    }

    // Diagnostic — un seul log par frame de test, throttlé à 1x/seconde
    // (pas à chaque frame, sinon spam de plusieurs centaines de lignes/s) —
    // pour vérifier les valeurs RÉELLES utilisées (vpWidth/vpHeight/vSize)
    // plutôt que de deviner à l'aveugle si la distance calculée par le shader
    // part d'une résolution correcte.
    private long la$lastDiagLog;
    // Compte les appels RÉELS à onRenderOverlay dans la fenêtre d'1s — pour
    // vérifier si drawEdgeVignette est appelé plusieurs fois par frame AFFICHÉE
    // (auquel cas le blend alpha s'empile à chaque appel, ce qui rendrait un
    // dégradé mathématiquement lisse beaucoup plus abrupt visuellement — les
    // alpha loggués (18-25% max) semblent trop faibles pour expliquer le rouge
    // très saturé observé en jeu, d'où ce compteur pour vérifier l'hypothèse).
    private int la$callsThisWindow;

    @Override
    public void onRenderOverlay(UiRenderer renderer, int vpWidth, int vpHeight) {
        try {
            float[] hp = healthAndMax();
            if (hp == null) return;
            float maxHealth = hp[1];
            if (maxHealth <= 0f) return;
            float healthPercent = hp[0] / maxHealth * 100f;
            if (healthPercent >= threshold) return;

            float ratio = Math.max(0f, Math.min(1f, 1f - healthPercent / threshold));
            float maxOpacity = maxOpacityPercent / 100f;
            int alpha = (int) (ratio * maxOpacity * 255f);
            if (alpha <= 0) return;

            float vSize = Math.min(vpWidth, vpHeight) * (vignetteWidthPercent / 100f);

            la$callsThisWindow++;
            long now = System.currentTimeMillis();
            if (now - la$lastDiagLog > 1000) {
                LauncherLog.info("[LowHealthTintModule] diag: vpWidth=" + vpWidth + " vpHeight=" + vpHeight
                    + " vignetteWidthPercent=" + vignetteWidthPercent + " vSize=" + vSize
                    + " maxOpacityPercent=" + maxOpacityPercent + " healthPercent=" + healthPercent
                    + " threshold=" + threshold + " alpha=" + alpha
                    + " shaderAvailable=" + renderer.isVignetteAvailable()
                    + " callsInLastWindow=" + la$callsThisWindow);
                la$lastDiagLog = now;
                la$callsThisWindow = 0;
            }

            if (renderer.isVignetteAvailable()) {
                UiColor edgeColor = new UiColor(color.r, color.g, color.b, (alpha / 255f));
                renderer.drawEdgeVignette(edgeColor, vSize, vpWidth, vpHeight);
            } else {
                drawVignetteBands(renderer, vpWidth, vpHeight, vSize, alpha);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Joueur par l'accessor Mixin ({@code PlayerData}), santé par les méthodes
     * publiques {@code getHealth()}/{@code getMaxHealth()} de
     * {@code LivingEntity} — zéro réflexion. Le repli réflexif multi-bracket a
     * été supprimé le 2026-08-27 avec le reste de l'accès aux données du jeu.
     * @return {@code float[]{health, maxHealth}} ou {@code null} si indisponible.
     */
    private float[] healthAndMax() {
        LocalPlayer player = PlayerData.player();
        if (player == null) return null;
        try {
            return new float[]{ player.getHealth(), player.getMaxHealth() };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Repli sans shader : mêmes anneaux non-chevauchants que la version
     * précédente (voir javadoc de classe) — 10 paliers d'alpha visibles à
     * l'œil, mais fonctionne même si la compilation GLSL échoue.
     */
    private void drawVignetteBands(UiRenderer renderer, int vpWidth, int vpHeight, float vSize, int edgeAlpha) {
        float step = vSize / BANDS;
        for (int i = 0; i < BANDS; i++) {
            float t0 = (float) i / BANDS;
            float bandAlpha = (edgeAlpha / 255f) * (1f - t0);
            UiColor band = new UiColor(color.r, color.g, color.b, bandAlpha);

            float d0 = i * step;
            float d1 = d0 + step;

            // Haut (pleine largeur, coins inclus)
            renderer.drawRoundedRect(0, vpHeight - d1, vpWidth, vpHeight - d0, 0, band, vpWidth, vpHeight);
            // Bas (pleine largeur, coins inclus)
            renderer.drawRoundedRect(0, d0, vpWidth, d1, 0, band, vpWidth, vpHeight);

            // Gauche/droite : uniquement la tranche verticale restante entre
            // haut et bas, sinon double-couverture des coins à cette profondeur.
            if (d1 < vpHeight - d1) {
                renderer.drawRoundedRect(d0, d1, d1, vpHeight - d1, 0, band, vpWidth, vpHeight);
                renderer.drawRoundedRect(vpWidth - d1, d1, vpWidth - d0, vpHeight - d1, 0, band, vpWidth, vpHeight);
            }
        }
    }
}
