package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import net.minecraft.resources.Identifier;

/**
 * Retire l'overlay de vision (le "blur" de citrouille) affiché en portant
 * une citrouille sculptée sur la tête.
 *
 * 26.1.2 — s'enregistre sur {@link HookPoint#HUD_EXTRACT_TEXTURE_OVERLAY}
 * (voir {@code HudExtractTextureOverlayMixin261}), filtre par chemin de
 * texture "pumpkin" (partagé avec la neige poudreuse au même point de
 * passage vanilla, voir ClearVisionModule pour l'autre moitié).
 *
 * 1.21.11 : toujours lu directement via {@code ModuleRegistry.get("no-pumpkin-overlay")}
 * par {@code ClearOverlaysMixin} ({@code Gui.renderTextureOverlay(...)}/
 * {@code InGameHud.renderOverlay(...)}, Yarn) — pas encore migré vers apimixin.
 *
 * 1.16.5/1.20.4/1.21.4/1.8.9 pas encore portés (architecture de rendu de
 * l'overlay à revérifier séparément pour chacun).
 */
public final class NoPumpkinOverlayModule extends LauncherModule {
    public NoPumpkinOverlayModule() {
        // Nom raccourci (était "Sans citrouille (vision)") — retour
        // utilisateur : débordait de la sous-sidebar du groupe "Confort
        // visuel" ; le détail reste dans la description.
        super("no-pumpkin-overlay", "Sans citrouille", "Retire l'overlay de vision de la citrouille sculptée portée sur la tête", false,
            HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY);
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY, this::cancelPumpkinOverlay);
    }

    private boolean cancelPumpkinOverlay(Object ctx) {
        if (!isEnabled() || !(ctx instanceof Identifier)) return false;
        return ((Identifier) ctx).getPath().contains("pumpkin");
    }
}
