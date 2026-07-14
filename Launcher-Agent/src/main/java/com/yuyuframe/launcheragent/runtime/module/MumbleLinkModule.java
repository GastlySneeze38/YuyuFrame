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

            double px = readEntityDouble(player, "x", "getX");
            double py = readEntityDouble(player, "y", "getY");
            double pz = readEntityDouble(player, "z", "getZ");
            float yaw = readEntityFloat(player, "yaw", "getYaw");
            float pitch = readEntityFloat(player, "pitch", "getPitch");
            float eyeHeight = readEyeHeight(player);

            String username = readUsername(player);

            float yawRad = (float) Math.toRadians(yaw);
            float pitchRad = (float) Math.toRadians(pitch);
            MumbleLinkBridge.update((float) px, (float) (py + eyeHeight), (float) pz, yawRad, pitchRad, username);
        } catch (Throwable t) {
            LauncherLog.err("[MumbleLinkModule] onTick: " + t);
        }
    }

    /**
     * BUG TROUVÉ (1.20.4, log confirmé) : {@code McReflect.field(player.getClass(),
     * "net/minecraft/entity/Entity", "x"...)} spammait {@code
     * IllegalArgumentException: Attempt to get java.lang.String field "blv.x"
     * with illegal data type conversion to double} — EXACTEMENT le même motif
     * que les bugs yaw/world de CoordsModule (voir historique de session) :
     * champ x/y/z retiré d'Entity depuis la "Flattening" (~1.13, remplacé par
     * getX()/getY()/getZ()) — {@code McReflect.field()} (sans garde
     * hasFieldMapping ici, contrairement à CoordsModule.tryField) retombait
     * sur le nom Yarn "x" INCHANGÉ, qui coïncide par hasard avec un VRAI champ
     * obfusqué "x" sans rapport (type String) plus proche dans la hiérarchie
     * du joueur. {@link McReflect#fieldOnClass} corrige les DEUX problèmes à
     * la fois : garde hasFieldMapping intégrée (pas de repli hasardeux) ET
     * résolution directement sur Entity (pas de collision de hiérarchie).
     */
    private double readEntityDouble(Object player, String yarnField, String yarnGetter) throws Exception {
        java.lang.reflect.Field f = McReflect.fieldOnClass("net/minecraft/entity/Entity", yarnField);
        if (f != null) return f.getDouble(player);
        Method m = McReflect.methodOnClass("net/minecraft/entity/Entity", yarnGetter);
        return m != null ? (double) m.invoke(player) : 0;
    }

    private float readEntityFloat(Object player, String yarnField, String yarnGetter) throws Exception {
        java.lang.reflect.Field f = McReflect.fieldOnClass("net/minecraft/entity/Entity", yarnField);
        if (f != null) return f.getFloat(player);
        Method m = McReflect.methodOnClass("net/minecraft/entity/Entity", yarnGetter);
        return m != null ? (float) m.invoke(player) : 0f;
    }

    /**
     * BUG TROUVÉ (1.20.4, log confirmé) : {@code ClassCastException: class
     * fob cannot be cast to class java.lang.Float} — {@code
     * Entity.getEyeHeight()} n'a AUCUNE surcharge sans paramètre en 1.20.4
     * (vérifié dans les mappings : seulement {@code
     * (EntityPose,EntityDimensions):float} et {@code (EntityPose):float}) —
     * l'ancien appel {@code noArgMethod(player.getClass(), ..., "getEyeHeight")}
     * supposait un no-arg à tort, et en remontant la hiérarchie du joueur
     * tombait sur une méthode SANS RAPPORT (renvoyant un {@code
     * PlayerListEntry}, "fob") coïncidemment nommée pareil. Résout maintenant
     * {@code getPose()} (no-arg) puis {@code getEyeHeight(EntityPose)} — les
     * deux via {@link McReflect#methodOnClass} (résolution directe sur
     * Entity, jamais en remontant depuis une instance).
     */
    private float readEyeHeight(Object player) throws Exception {
        Method getEyeHeightNoArg = McReflect.methodOnClass("net/minecraft/entity/Entity", "getEyeHeight");
        if (getEyeHeightNoArg != null) return (float) getEyeHeightNoArg.invoke(player);

        Method getPose = McReflect.methodOnClass("net/minecraft/entity/Entity", "getPose");
        if (getPose == null) return 1.62f;
        Object pose = getPose.invoke(player);
        if (pose == null) return 1.62f;

        Method getEyeHeightPosed = McReflect.methodOnClass("net/minecraft/entity/Entity", "getEyeHeight", pose.getClass());
        return getEyeHeightPosed != null ? (float) getEyeHeightPosed.invoke(player, pose) : 1.62f;
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
