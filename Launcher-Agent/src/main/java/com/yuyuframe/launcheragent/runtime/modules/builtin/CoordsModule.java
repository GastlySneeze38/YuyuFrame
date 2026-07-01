package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

/** Port de PvP-Mod CoordsConfig/CoordsHud — sa propre carte, comme dans la référence. */
public final class CoordsModule extends SingleHudModule {
    public CoordsModule() {
        super("coords", "Coordonnées", "Affiche la position X/Y/Z du joueur", true,
            new HudElement("coords", "Coordonnées", HudAnchor.TOP_LEFT, 8f, 40f, new ContentSource()));
    }

    /** Coordonnées réelles du joueur (X/Y/Z) — ligne "Biome" volontairement pas reprise, aucun accesseur confirmé dans mappings-1.8.9.tiny. */
    private static final class ContentSource implements HudElement.ContentSource {
        private static final String[] FALLBACK = { "X: --", "Y: --", "Z: --" };

        @Override
        public String[] lines() {
            try {
                Object mc = McReflect.minecraftClient();
                if (mc == null) return FALLBACK;
                Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                if (player == null) return FALLBACK;

                double x = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "x").getDouble(player);
                double y = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "y").getDouble(player);
                double z = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "z").getDouble(player);

                return new String[]{
                        "X: " + (int) Math.floor(x),
                        "Y: " + (int) Math.floor(y),
                        "Z: " + (int) Math.floor(z)
                };
            } catch (Throwable t) {
                return FALLBACK;
            }
        }
    }
}
