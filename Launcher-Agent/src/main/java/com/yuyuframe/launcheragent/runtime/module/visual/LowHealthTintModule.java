package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
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

    public float threshold = 30f;

    @Override
    protected void settings(SettingList s) {
        s.slider("threshold", "Seuil (% de vie)", "Réglages", 5f, 100f, 5f,
            () -> threshold, v -> threshold = v);
        s.slider("maxOpacityPercent", "Opacité max (%)", "Réglages", 5f, 100f, 5f,
            () -> maxOpacityPercent, v -> maxOpacityPercent = v);
        s.slider("vignetteWidthPercent", "Largeur du dégradé (% écran)", "Réglages", 10f, 80f, 5f,
            () -> vignetteWidthPercent, v -> vignetteWidthPercent = v);
        s.color("color", "Couleur", "Réglages", () -> color, v -> color = v);
    }

    // Baissé après retour utilisateur (bord encore visible même dans le noir
    // total, sans contraste de scène en cause) : un delta d'alpha plus petit
    // sur toute la largeur du dégradé laisse plus de marge aux 256 niveaux du
    // framebuffer 8-bit pour représenter la transition sans paliers visibles.
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
    public float vignetteWidthPercent = 45f;

    public UiColor color = new UiColor(255, 0, 0, 255);

    public LowHealthTintModule() {
        super("low-health-tint", "Teinte vie basse", "Teinte l'écran quand la vie descend sous un seuil",
            "Teinte l'écran à vie basse", false);
        iconUrl = icons8("heart-monitor");
    }

    /**
     * Marque que la passe GUI a déjà dessiné la vignette dans CETTE frame —
     * voir {@link #onRenderOverlay}.
     *
     * <p>Le module est branché sur les DEUX points d'entrée : la passe GUI de
     * vanilla (26.1.2) et l'appel après présentation (autres brackets). Les
     * deux existent dans la même frame sur 26.1.2, d'où ce drapeau plutôt
     * qu'un test de version — il se corrige tout seul si une frame passe sans
     * passe GUI (écran ouvert, hook absent), là où un test de bracket
     * laisserait le module muet.
     */
    private boolean la$drawnInGuiPass;

    /**
     * 26.1.2 — la vignette est émise DANS l'état de GUI de vanilla, comme
     * n'importe quel autre élément, au lieu d'être peinte en OpenGL brut après
     * la présentation. Voir {@code Blaze3DGuiVignette} pour le pourquoi
     * (corruptions d'état signalées dès l'activation du module).
     */
    @Override
    public void onRenderInVanillaGui(UiRenderer renderer, int vpWidth, int vpHeight) {
        la$drawnInGuiPass = true;
        draw(renderer, vpWidth, vpHeight);
    }

    @Override
    public void onRenderOverlay(UiRenderer renderer, int vpWidth, int vpHeight) {
        // La passe GUI a déjà dessiné cette frame : ne pas repasser par-dessus
        // (double blend, et retour du dessin GL brut qu'on vient justement de
        // quitter). Le drapeau est consommé, donc une frame sans passe GUI
        // reprend automatiquement ce chemin.
        if (la$drawnInGuiPass) { la$drawnInGuiPass = false; return; }
        draw(renderer, vpWidth, vpHeight);
    }

    private void draw(UiRenderer renderer, int vpWidth, int vpHeight) {
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

            if (renderer.isVignetteAvailable()) {
                UiColor edgeColor = new UiColor(color.r, color.g, color.b, (alpha / 255f));
                renderer.drawEdgeVignette(edgeColor, vSize, vpWidth, vpHeight);
            } else {
                drawVignetteBands(renderer, vpWidth, vpHeight, vSize, alpha);
            }
        } catch (Throwable t) {
            if (!la$drawErrorLogged) {
                la$drawErrorLogged = true;
                LauncherLog.err("[LowHealthTintModule] draw: " + t);
            }
        }
    }

    private static boolean la$drawErrorLogged;

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
