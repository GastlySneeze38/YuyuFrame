package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.resources.Identifier;

/**
 * Équivalent "Clear Water/Lava/Powder Snow" — supprime le brouillard
 * teinté (eau/lave/neige poudreuse) ET, pour la neige poudreuse, l'overlay
 * de givre à l'écran. Aucune logique ici : lu directement par 4 Mixins via
 * {@code ModuleRegistry.get("clear-vision")} :
 * - {@code WaterFogEnvironmentMixin261}/{@code LavaFogEnvironmentMixin261}/
 *   {@code PowderedSnowFogEnvironmentMixin261} (force {@code isApplicable()}
 *   à {@code false} — DÉCOUVERTE : en 26.1+, la vision teintée eau/lave/
 *   neige est ENTIÈREMENT gérée par le nouveau système de brouillard par
 *   "FogEnvironment", pas par un overlay séparé — voir mémoire du portage).
 * - {@code ClearOverlaysMixin261} (annule spécifiquement l'overlay givre de
 *   neige poudreuse, texture "powder_snow_outline", indépendant du
 *   brouillard).
 *
 * Volontairement SÉPARÉ de {@link NoFogModule} : ce dernier coupe TOUT le
 * brouillard (y compris la distance de rendu/le brouillard atmosphérique
 * normal) — celui-ci ne vise QUE les 3 environnements liquide/poudre,
 * laisse le brouillard normal intact.
 *
 * 26.1.2 UNIQUEMENT pour l'instant (voir mémoire du portage).
 */
public final class ClearVisionModule extends LauncherModule {

    private static final float FAR = 1_000_000f;

    // Réglages ajoutés explicitement à la demande — activer/désactiver
    // CHAQUE liquide indépendamment (avant : tout ou rien via le seul
    // toggle du module). Vrai par défaut pour les 3 : comportement
    // IDENTIQUE à avant tant que l'utilisateur ne désactive rien ici. Lus
    // directement par WaterFogEnvironmentMixin261/LavaFogEnvironmentMixin261/
    // PowderedSnowFogEnvironmentMixin261/ClearOverlaysMixin261 (voir chacun).
    public boolean clearWater = true;

    public boolean clearLava = true;

    public boolean clearPowderSnow = true;

    @Override
    protected void settings(SettingList s) {
        s.toggle("clearWater", "Eau", "Retire le brouillard teinté sous l'eau.", "Réglages", null,
            () -> clearWater, v -> clearWater = v);
        s.toggle("clearLava", "Lave", "Retire le brouillard teinté dans la lave.", "Réglages", null,
            () -> clearLava, v -> clearLava = v);
        s.toggle("clearPowderSnow", "Neige poudreuse",
            "Retire le brouillard teinté ET le givre à l'écran dans la neige poudreuse.", "Réglages", null,
            () -> clearPowderSnow, v -> clearPowderSnow = v);
    }

    public ClearVisionModule() {
        // Nom raccourci (était "Vision claire (eau/lave/neige)") — retour
        // utilisateur : débordait de la sous-sidebar du groupe "Confort
        // visuel" ; le détail reste dans la description.
        super("clear-vision", "Vision claire", "Retire le brouillard teinté et le givre de l'eau, la lave et la neige poudreuse", false,
            HookPoint.FOG_SETUP_WATER, HookPoint.FOG_SETUP_LAVA, HookPoint.FOG_SETUP_POWDERED_SNOW,
            HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY);
        // 26.1.2 — voir apimixin/v26_1/fog/ (brouillard) et
        // apimixin/v26_1/hud/HudExtractTextureOverlayMixin261 (givre écran).
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_WATER, ctx -> pushFogFarIf(clearWater, ctx));
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_LAVA, ctx -> pushFogFarIf(clearLava, ctx));
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_POWDERED_SNOW, ctx -> pushFogFarIf(clearPowderSnow, ctx));
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY, this::cancelPowderSnowOverlay);
    }

    private boolean pushFogFarIf(boolean flag, Object ctx) {
        if (!isEnabled() || !flag || !(ctx instanceof FogData)) return false;
        FogData fogData = (FogData) ctx;
        fogData.environmentalStart = FAR;
        fogData.environmentalEnd = FAR * 2f;
        fogData.renderDistanceStart = FAR;
        fogData.renderDistanceEnd = FAR * 2f;
        return true;
    }

    private boolean cancelPowderSnowOverlay(Object ctx) {
        if (!isEnabled() || !clearPowderSnow || !(ctx instanceof Identifier)) return false;
        return ((Identifier) ctx).getPath().contains("powder_snow");
    }
}
