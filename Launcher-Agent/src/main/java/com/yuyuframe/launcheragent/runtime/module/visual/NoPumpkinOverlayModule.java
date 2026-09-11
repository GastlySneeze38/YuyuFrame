package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Retire l'overlay de vision (le "blur" de citrouille) affiché en portant
 * une citrouille sculptée sur la tête.
 *
 * S'enregistre sur {@link HookPoint#HUD_EXTRACT_TEXTURE_OVERLAY} et filtre par
 * chemin de texture "pumpkin" (partagé avec la neige poudreuse au même point
 * de passage vanilla, voir ClearVisionModule pour l'autre moitié). Servi en
 * 26.1.2 par {@code HudExtractTextureOverlayMixin261} et en 1.21.11 par
 * {@code HudExtractTextureOverlayMixin1211} — ce module ne sait pas lequel.
 *
 * <p>{@code ctx} est le chemin de texture, une {@code String} : plus aucun
 * type du jeu à connaître ici (c'était un {@code Identifier} 26.1.2, faux sur
 * toute version obfusquée — voir la javadoc du HookPoint).
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
        if (!isEnabled() || !(ctx instanceof String)) return false;
        return ((String) ctx).contains("pumpkin");
    }
}
