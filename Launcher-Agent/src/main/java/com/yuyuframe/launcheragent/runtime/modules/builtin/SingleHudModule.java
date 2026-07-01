package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.hud.HudRegistry;
import com.yuyuframe.launcheragent.runtime.modules.HudElementOwner;
import com.yuyuframe.launcheragent.runtime.modules.LauncherModule;

/**
 * Base commune des modules qui n'ajoutent RIEN d'autre qu'un unique élément
 * HUD (FPS/Ping/Coordonnées/Keystrokes/Potions/Armure — chacun sa propre
 * carte, comme dans PvP-Mod où chaque HUD est sa propre Config/Mod) —
 * (dés)enregistre l'élément dans {@link HudRegistry} au gré du toggle de la
 * carte, une seule fois, ici. Implémente {@link HudElementOwner} : ses
 * réglages génériques (verrouillage, échelle, marges, reset position)
 * apparaissent donc automatiquement dans la config, voir ConfigScreenBuilder.
 */
abstract class SingleHudModule extends LauncherModule implements HudElementOwner {

    private final HudElement element;

    protected SingleHudModule(String id, String name, String description, boolean enabledByDefault, HudElement element) {
        super(id, name, description, enabledByDefault);
        this.element = element;
        if (enabledByDefault) HudRegistry.register(element);
    }

    @Override
    public HudElement hudElement() { return element; }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) HudRegistry.register(element);
        else HudRegistry.unregister(element);
    }
}
