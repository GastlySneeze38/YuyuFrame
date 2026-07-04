package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.mumble.MumbleLinkBridge;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

import java.lang.reflect.Method;

/**
 * Port de PvP-Mod MumbleLinkHandler/MumbleLinkConfig — envoie position/
 * orientation/nom du joueur à Mumble (audio positionnel 3D) via mémoire
 * partagée Win32 (voir MumbleLinkBridge, tout le protocole/JNA y vit). Ce
 * module ne fait que lire l'état joueur par réflexion CHAQUE frame (même
 * pattern que CoordsModule pour X/Y/Z/yaw) et transmettre au bridge — aucun
 * Mixin nécessaire, contrairement à FOV/sneak/etc, puisqu'on ne fait que LIRE
 * un état déjà mis à jour par vanilla, jamais le modifier.
 */
public final class MumbleLinkModule extends LauncherModule {

    public MumbleLinkModule() {
        super("mumble-link", "Mumble Link", "Envoie la position/orientation à Mumble pour l'audio positionnel 3D", false);
    }

    @Override
    public void onTick() {
        try {
            if (!MumbleLinkBridge.ensureInit()) return;

            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
            if (player == null) return;

            double px = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "x").getDouble(player);
            double py = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "y").getDouble(player);
            double pz = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "z").getDouble(player);
            float yaw = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "yaw").getFloat(player);
            float pitch = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "pitch").getFloat(player);

            Method getEyeHeight = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/Entity", "getEyeHeight");
            float eyeHeight = getEyeHeight != null ? (float) getEyeHeight.invoke(player) : 1.62f;

            String username = readUsername(player);

            float yawRad = (float) Math.toRadians(yaw);
            float pitchRad = (float) Math.toRadians(pitch);
            MumbleLinkBridge.update((float) px, (float) (py + eyeHeight), (float) pz, yawRad, pitchRad, username);
        } catch (Throwable t) {
            LauncherLog.err("[MumbleLinkModule] onTick: " + t);
        }
    }

    /**
     * GameProfile (com.mojang.authlib.GameProfile) — classe externe NON
     * obfusquée par Mojang (bibliothèque authlib) : getName() s'appelle en
     * réflexion directe, pas via McReflect/mappings Yarn (qui ne couvrent que
     * le code du jeu lui-même).
     */
    private String readUsername(Object player) {
        try {
            Method getGameProfile = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/player/PlayerEntity", "getGameProfile");
            if (getGameProfile == null) return null;
            Object profile = getGameProfile.invoke(player);
            if (profile == null) return null;
            Object name = profile.getClass().getMethod("getName").invoke(profile);
            return name != null ? name.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
