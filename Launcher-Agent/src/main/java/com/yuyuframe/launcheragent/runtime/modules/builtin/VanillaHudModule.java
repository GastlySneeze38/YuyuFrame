package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.CoordsHudSource;
import com.yuyuframe.launcheragent.runtime.hud.FpsHudSource;
import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.hud.HudRegistry;
import com.yuyuframe.launcheragent.runtime.hud.PingHudSource;
import com.yuyuframe.launcheragent.runtime.modules.LauncherModule;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigToggle;

/**
 * Premier module réel du système — porte les 3 sources HUD déjà branchées
 * sur de vraies données (FPS/Ping/Coordonnées, portées depuis PvP-Mod, voir
 * runtime.hud). Sert de MODÈLE pour les futurs modules (YuyuPvP, etc.) et
 * de démonstration que le toggle de la carte mod a un effet réel : il
 * (dés)enregistre les éléments dans {@link HudRegistry}, qui pilote à la
 * fois l'éditeur (UiHudEditorScreen) et l'affichage en jeu (HudOverlayRenderer).
 */
public final class VanillaHudModule extends LauncherModule {

    @ConfigToggle(name = "Afficher les coordonnées", description = "Position X/Y/Z du joueur.", category = "Éléments")
    public boolean showCoords = true;

    private final HudElement fps = new HudElement("fps", "FPS", 70f, 24f, HudAnchor.TOP_LEFT, 8f, 8f, new FpsHudSource());
    private final HudElement coords = new HudElement("coords", "Coordonnées", 130f, 64f, HudAnchor.TOP_LEFT, 8f, 40f, new CoordsHudSource());
    private final HudElement ping = new HudElement("ping", "Ping", 70f, 24f, HudAnchor.TOP_RIGHT, 8f, 8f, new PingHudSource());

    public VanillaHudModule() {
        super("vanilla-hud", "HUD Vanilla+", "FPS, ping et coordonnées affichés en jeu", true);
        HudRegistry.register(fps);
        HudRegistry.register(ping);
        HudRegistry.register(coords);
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        sync(fps, enabled);
        sync(ping, enabled);
        sync(coords, enabled && showCoords);
    }

    @Override
    public void onConfigChanged() {
        sync(coords, isEnabled() && showCoords);
    }

    private static void sync(HudElement element, boolean shouldBeRegistered) {
        boolean present = HudRegistry.elements().contains(element);
        if (shouldBeRegistered && !present) HudRegistry.register(element);
        else if (!shouldBeRegistered && present) HudRegistry.unregister(element);
    }
}
