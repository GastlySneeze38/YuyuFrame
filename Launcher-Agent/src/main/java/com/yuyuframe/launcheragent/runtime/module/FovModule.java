package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

import java.lang.reflect.Field;

/**
 * FOV personnalisé — port de PvP-Mod FovConfig/FovHandler. Fixe
 * {@code GameOptions.fov} à CHAQUE frame tant que le module est activé (voir
 * {@link #onTick}, appelé par ModuleRegistry.tickAll() indépendamment de tout
 * écran ouvert, comme le TickEvent de l'original) ; le sprint/ralenti est
 * neutralisé séparément par {@code MixinGameRenderer189} (même découpage que
 * la référence, dont le seul vrai Mixin gère ce cas précis).
 */
public final class FovModule extends LauncherModule {

    @ConfigSlider(name = "FOV", category = "Réglages", min = 30f, max = 110f, step = 1f)
    public float fovValue = 90f;

    private float savedVanillaFov = -1f;
    private static boolean DIAG_LOGGED = false;

    public FovModule() {
        super("fov", "FOV", "Remplace le FOV vanilla (sprint/ralenti compris)", false);
    }

    @Override
    public void onTick() {
        try {
            Object options = optionsInstance();
            Field fovField = fovField();
            if (fovField == null) { diag("fovField == null, options=" + options); return; }
            float before = fovField.getFloat(options);
            if (savedVanillaFov < 0f) savedVanillaFov = before;
            fovField.setFloat(options, fovValue);
            float after = fovField.getFloat(options);
            diag("options=" + options + " before=" + before + " target=" + fovValue + " after=" + after);
        } catch (Throwable t) {
            diag("exception: " + t);
        }
    }

    private void diag(String msg) {
        if (DIAG_LOGGED) return;
        DIAG_LOGGED = true;
        com.yuyuframe.launcheragent.runtime.log.LauncherLog.info("[FovModule] diag: " + msg);
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        try {
            if (savedVanillaFov < 0f) return;
            Field fovField = fovField();
            if (fovField != null) fovField.setFloat(optionsInstance(), savedVanillaFov);
        } catch (Throwable ignored) {
        } finally {
            savedVanillaFov = -1f;
        }
    }

    private Object optionsInstance() throws Exception {
        Object mc = McReflect.minecraftClient();
        if (mc == null) return null;
        return McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
    }

    private Field fovField() throws Exception {
        Object options = optionsInstance();
        if (options == null) return null;
        return McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "fov");
    }
}
