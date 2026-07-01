package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.hud.PingHudSource;

/** Port de PvP-Mod PingConfig/PingHud — sa propre carte, comme dans la référence. */
public final class PingModule extends SingleHudModule {
    public PingModule() {
        super("ping", "Ping", "Affiche la latence réseau", true,
            new HudElement("ping", "Ping", 70f, 24f, HudAnchor.TOP_RIGHT, 8f, 8f, new PingHudSource()));
    }
}
