package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.hud.PotionEffectsHudRenderer;

/** Port de PvP-Mod PotionEffectsConfig/PotionEffectsHud — sa propre carte, comme dans la référence. */
public final class PotionEffectsModule extends SingleHudModule {
    public PotionEffectsModule() {
        super("potion-effects", "Effets de potion", "Liste des effets de potion actifs", false,
            new HudElement("potion-effects", "Effets de potion", 140f, 90f, HudAnchor.TOP_RIGHT, 8f, 40f,
                (HudElement.CustomRenderer) new PotionEffectsHudRenderer()));
    }
}
