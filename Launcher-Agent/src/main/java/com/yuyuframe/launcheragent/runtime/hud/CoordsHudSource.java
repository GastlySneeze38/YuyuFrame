package com.yuyuframe.launcheragent.runtime.hud;

/**
 * Coordonnées réelles du joueur — port de PvP-Mod CoordsHud (X/Y/Z). Ligne
 * "Biome" du mod d'origine volontairement PAS reprise pour cette passe :
 * aucun accesseur biome-par-position confirmé dans mappings-1.8.9.tiny.
 */
public final class CoordsHudSource implements HudElement.ContentSource {

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
