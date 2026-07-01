package com.yuyuframe.launcheragent.runtime.hud;

import java.util.UUID;

/**
 * Ping réel du joueur local — port de PvP-Mod PingHud : retrouve le
 * PlayerListEntry du joueur (même chemin que l'onglet multijoueur vanilla,
 * via son UUID) et lit sa latence.
 */
public final class PingHudSource implements HudElement.ContentSource {

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
