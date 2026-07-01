package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.CoordsHudSource;
import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;

/** Port de PvP-Mod CoordsConfig/CoordsHud — sa propre carte, comme dans la référence. */
public final class CoordsModule extends SingleHudModule {
    public CoordsModule() {
        super("coords", "Coordonnées", "Affiche la position X/Y/Z du joueur", true,
            new HudElement("coords", "Coordonnées", 130f, 64f, HudAnchor.TOP_LEFT, 8f, 40f, new CoordsHudSource()));
    }
}
