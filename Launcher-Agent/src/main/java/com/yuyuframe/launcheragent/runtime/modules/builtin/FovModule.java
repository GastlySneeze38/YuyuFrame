package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.McReflect;
import com.yuyuframe.launcheragent.runtime.modules.LauncherModule;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigSlider;

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

    public FovModule() {
        super("fov", "FOV", "Remplace le FOV vanilla (sprint/ralenti compris)", false);
    }

    @Override
    public void onTick() {
        try {
            Field fovField = fovField();
            if (fovField == null) return;
            if (savedVanillaFov < 0f) savedVanillaFov = fovField.getFloat(optionsInstance());
            fovField.setFloat(optionsInstance(), fovValue);
        } catch (Throwable ignored) {}
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
