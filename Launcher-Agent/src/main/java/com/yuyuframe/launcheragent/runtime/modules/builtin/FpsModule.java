package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.FpsHudSource;
import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;

/** Port de PvP-Mod FpsConfig/FpsHud — sa propre carte, comme dans la référence. */
public final class FpsModule extends SingleHudModule {
    public FpsModule() {
        super("fps", "FPS", "Affiche le nombre d'images par seconde", true,
            new HudElement("fps", "FPS", 70f, 24f, HudAnchor.TOP_LEFT, 8f, 8f, new FpsHudSource()));
    }
}
