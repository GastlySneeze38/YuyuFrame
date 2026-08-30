package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mumble.MumbleLinkBridge;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import net.minecraft.client.player.LocalPlayer;

import java.lang.reflect.Method;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.mojang.authlib.GameProfile;

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
        super("mumble-link", "Mumble Link", "Envoie la position/orientation à Mumble pour l'audio positionnel 3D",
            "Audio positionnel 3D (Mumble)", false);
        iconUrl = icons8("headphones");
    }

    @Override
    public void onTick() {
        try {
            if (!MumbleLinkBridge.ensureInit()) return;

            // Position/orientation par les ACCESSORS Mixin (voir PlayerData) —
            // plus aucune réflexion ici. Ce module et CoordsModule avaient
            // chacun leur propre lecture de x/y/z/yaw, avec les mêmes pièges de
            // mappings découverts et corrigés séparément des deux côtés ; il
            // n'en existe désormais qu'une seule implémentation.
            LocalPlayer directPlayer = PlayerData.player();
            if (directPlayer == null) return;

            double[] pos = PlayerData.position();
            float eyeHeight = PlayerData.eyeHeight();
            // GameProfile (com.mojang.authlib) — bibliothèque EXTERNE, pas sur
            // le classpath de compilation de ce module (ni stub ni jar,
            // contrairement à net.minecraft.*) : getName() par réflexion reste
            // nécessaire pour ce seul champ, voir profileName(). C'est la seule
            // réflexion restante du module, et elle ne porte pas sur le jeu.
            String username = profileName(directPlayer.getGameProfile());

            float yawRad = (float) Math.toRadians(PlayerData.yaw());
            float pitchRad = (float) Math.toRadians(PlayerData.pitch());
            MumbleLinkBridge.update((float) pos[0], (float) (pos[1] + eyeHeight), (float) pos[2],
                yawRad, pitchRad, username);
        } catch (Throwable t) {
            LauncherLog.err("[MumbleLinkModule] onTick: " + t);
        }
    }





    /** {@code GameProfile.getName()} par réflexion directe — voir javadoc de classe (bibliothèque externe, hors classpath de compilation). */
    /**
     * BUG TROUVÉ (2026-08-27) : cette méthode lisait le pseudo par réflexion
     * sur {@code getName()} — or {@code GameProfile} est devenu un RECORD,
     * dont l'accesseur s'appelle {@code name()}. La recherche échouait, le
     * {@code catch} renvoyait {@code null}, et Mumble recevait un pseudo vide
     * sans le moindre log. Le stub {@code com.mojang.authlib.GameProfile}
     * (ajouté en même temps) supprime la réflexion ET l'erreur de nom.
     */
    private String profileName(GameProfile profile) {
        return profile == null ? null : profile.name();
    }
}
