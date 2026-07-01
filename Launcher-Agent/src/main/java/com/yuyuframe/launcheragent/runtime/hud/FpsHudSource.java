package com.yuyuframe.launcheragent.runtime.hud;

import java.lang.reflect.Field;

/** FPS réel courant — port de PvP-Mod FpsHud (lecture du champ Minecraft.currentFps). */
public final class FpsHudSource implements HudElement.ContentSource {

    @Override
    public String[] lines() {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return new String[]{ "-- FPS" };
            Field fpsField = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "currentFps");
            if (fpsField == null) return new String[]{ "-- FPS" };
            return new String[]{ fpsField.getInt(mc) + " FPS" };
        } catch (Throwable t) {
            return new String[]{ "-- FPS" };
        }
    }
}
