package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;

import java.util.UUID;

/** Port de PvP-Mod PingConfig/PingHud — sa propre carte, comme dans la référence. */
public final class PingModule extends SingleHudModule {
    public PingModule() {
        super("ping", "Ping", "Affiche la latence réseau", true,
            new HudElement("ping", "Ping", HudAnchor.TOP_RIGHT, 8f, 8f, new ContentSource()));
        hudElement().textColor = new UiColor(120, 220, 140, 255);
        hudElement().accentSuffix = " ms";
    }

    /** Ping réel du joueur local — retrouve le PlayerListEntry via son UUID (même chemin que l'onglet multijoueur vanilla). */
    private static final class ContentSource implements HudElement.ContentSource {
        @Override
        public String[] lines() {
            try {
                Object mc = McReflect.minecraftClient();
                if (mc == null) return new String[]{ "-- ms" };

                Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                if (player == null) return new String[]{ "-- ms" };

                Object uuid = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/Entity", "getUuid").invoke(player);
                if (uuid == null) return new String[]{ "-- ms" };

                Object handler = McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getNetworkHandler").invoke(mc);
                if (handler == null) return new String[]{ "-- ms" };

                Object entry = McReflect.oneArgMethod(handler.getClass(),
                        "net/minecraft/client/network/ClientPlayNetworkHandler", "getPlayerListEntry", UUID.class)
                        .invoke(handler, uuid);
                if (entry == null) return new String[]{ "-- ms" };

                int latency = (int) McReflect.noArgMethod(entry.getClass(),
                        "net/minecraft/client/network/PlayerListEntry", "getLatency").invoke(entry);
                return new String[]{ latency + " ms" };
            } catch (Throwable t) {
                return new String[]{ "-- ms" };
            }
        }
    }
}
