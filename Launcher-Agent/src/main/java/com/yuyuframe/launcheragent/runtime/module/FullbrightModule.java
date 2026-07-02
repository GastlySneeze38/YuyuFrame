package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

import java.lang.reflect.Field;

/**
 * Fullbright — force {@code GameOptions.gamma} bien au-delà du maximum
 * vanilla (le slider en jeu plafonne à 1.0/100%, mais le champ brut accepte
 * n'importe quelle valeur — technique standard, indépendante de la version)
 * à CHAQUE frame tant que le module est actif, restaure la valeur d'origine
 * à la désactivation. Même découpage que {@link FovModule} (onTick force +
 * onEnabledChanged restaure).
 */
public final class FullbrightModule extends LauncherModule {

    private static final float GAMMA_VALUE = 1000f;

    private float savedVanillaGamma = Float.NaN;

    public FullbrightModule() {
        super("fullbright", "Fullbright", "Éclaire toute la scène au maximum, ignore l'obscurité", false);
    }

    @Override
    public void onTick() {
        try {
            Field gammaField = gammaField();
            if (gammaField == null) return;
            Object options = optionsInstance();
            if (Float.isNaN(savedVanillaGamma)) savedVanillaGamma = gammaField.getFloat(options);
            gammaField.setFloat(options, GAMMA_VALUE);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        try {
            if (Float.isNaN(savedVanillaGamma)) return;
            Field gammaField = gammaField();
            if (gammaField != null) gammaField.setFloat(optionsInstance(), savedVanillaGamma);
        } catch (Throwable ignored) {
        } finally {
            savedVanillaGamma = Float.NaN;
        }
    }

    private Object optionsInstance() throws Exception {
        Object mc = McReflect.minecraftClient();
        if (mc == null) return null;
        return McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
    }

    private Field gammaField() throws Exception {
        Object options = optionsInstance();
        if (options == null) return null;
        return McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "gamma");
    }
}
