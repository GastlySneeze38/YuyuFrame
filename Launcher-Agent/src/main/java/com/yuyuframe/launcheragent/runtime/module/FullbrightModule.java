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
            if (Float.isNaN(savedVanillaGamma)) savedVanillaGamma = readGamma(gammaField, options);
            writeGamma(gammaField, options, GAMMA_VALUE);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        try {
            if (Float.isNaN(savedVanillaGamma)) return;
            Field gammaField = gammaField();
            if (gammaField != null) writeGamma(gammaField, optionsInstance(), savedVanillaGamma);
        } catch (Throwable ignored) {
        } finally {
            savedVanillaGamma = Float.NaN;
        }
    }

    /**
     * BUG TROUVÉ (audit modules, voir historique de session) : {@code
     * GameOptions.gamma} est un {@code float} en 1.8.9 mais un {@code double}
     * en 1.13-1.16.5 (mappings 1.16.5 : {@code f D aR field_1840 gamma}) —
     * {@code getFloat}/{@code setFloat} levait {@code IllegalArgumentException}
     * sur ce dernier, avalée silencieusement, fullbright totalement
     * inopérant. Lit le VRAI type du champ au lieu de supposer.
     *
     * BUG TROUVÉ #2 (1.20.4, même refonte "SimpleOption" que ZoomModule.fov) :
     * {@code gamma} n'est plus un float/double DU TOUT ici — objet {@code
     * SimpleOption} FINAL (vérifié : {@code f Levl; cb field_1840 gamma}) —
     * voir {@link McReflect#simpleOptionGetValue}/{@link McReflect#simpleOptionSetValue}.
     */
    private float readGamma(Field gammaField, Object options) throws Exception {
        if (gammaField.getType() == double.class) return (float) gammaField.getDouble(options);
        if (gammaField.getType() == float.class) return gammaField.getFloat(options);
        return (float) McReflect.simpleOptionGetValue(gammaField.get(options));
    }

    private void writeGamma(Field gammaField, Object options, float value) throws Exception {
        if (gammaField.getType() == double.class) { gammaField.setDouble(options, value); return; }
        if (gammaField.getType() == float.class) { gammaField.setFloat(options, value); return; }
        McReflect.simpleOptionSetValue(gammaField.get(options), value);
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
