package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.ArmorDurabilityHudSource;
import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;

/** Port de PvP-Mod ArmorDurabilityConfig/ArmorDurabilityHud — sa propre carte, comme dans la référence. */
public final class ArmorDurabilityModule extends SingleHudModule {
    public ArmorDurabilityModule() {
        super("armor-durability", "Armure/Durabilité", "Durabilité de l'armure et de l'objet en main", false,
            new HudElement("armor-durability", "Armure/Durabilité", 110f, 100f, HudAnchor.BOTTOM_RIGHT, 8f, 8f, new ArmorDurabilityHudSource()));
    }
}
