package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.apimixin.v26_1.core.MinecraftAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.OptionInstanceAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.OptionsAccessor261;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;

/**
 * Fullbright — force {@code GameOptions.gamma} bien au-delà du maximum
 * vanilla (le slider en jeu plafonne à 1.0/100%, mais le champ brut accepte
 * n'importe quelle valeur — technique standard, indépendante de la version)
 * à CHAQUE frame tant que le module est actif, restaure la valeur d'origine
 * à la désactivation. Même découpage que {@link FovModule} (onTick force +
 * onEnabledChanged restaure).
 *
 * 26.1.2 sans réflexion (2026-08-26, §22 — audit modules) —
 * {@link OptionsAccessor261#la$gamma()}/{@link OptionInstanceAccessor261}
 * (architecture apimixin, même famille que {@code ZoomModule}.fov/sensitivity :
 * {@code OptionInstance<Double>}, vérifié javap). Écrit DIRECTEMENT le champ
 * {@code .value} via l'accessor, jamais {@code OptionInstance.set()} — même
 * raison que {@code ZoomModule} (validation vanilla qui clampe la valeur,
 * voir {@link McReflect#simpleOptionSetValue} pour l'historique complet de
 * ce piège). Repli réflexion multi-bracket sinon, comportement inchangé.
 */
public final class FullbrightModule extends LauncherModule {

    private static final float GAMMA_VALUE = 1000f;

    private float savedVanillaGamma = Float.NaN;

    public FullbrightModule() {
        super("fullbright", "Fullbright", "Éclaire toute la scène au maximum, ignore l'obscurité", false);
        iconUrl = icons8("sun");
    }

    @Override
    public void onTick() {
        try {
            Object options = optionsInstance();
            if (options == null) return;
            Object handle = gammaHandle(options);
            if (handle == null) return;
            if (Float.isNaN(savedVanillaGamma)) savedVanillaGamma = readGamma(handle, options);
            writeGamma(handle, options, GAMMA_VALUE);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        try {
            if (Float.isNaN(savedVanillaGamma)) return;
            Object options = optionsInstance();
            Object handle = options != null ? gammaHandle(options) : null;
            if (handle != null) writeGamma(handle, options, savedVanillaGamma);
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
     * inopérant. Lit le VRAI type du champ au lieu de supposer — repli
     * réflexion UNIQUEMENT (voir {@link #gammaHandle}) : sur 26.1.2, la
     * valeur passe désormais par {@link OptionInstanceAccessor261}, qui gère
     * son propre boxing (voir {@link #writeGamma}).
     *
     * BUG TROUVÉ #2 (1.20.4, même refonte "SimpleOption" que ZoomModule.fov) :
     * {@code gamma} n'est plus un float/double DU TOUT ici — objet {@code
     * SimpleOption} FINAL (vérifié : {@code f Levl; cb field_1840 gamma}) —
     * voir {@link McReflect#simpleOptionGetValue}/{@link McReflect#simpleOptionSetValue}.
     */
    private float readGamma(Object handle, Object options) throws Exception {
        if (handle instanceof OptionInstanceAccessor261) {
            return ((Number) ((OptionInstanceAccessor261) handle).la$value()).floatValue();
        }
        Field gammaField = (Field) handle;
        if (gammaField.getType() == double.class) return (float) gammaField.getDouble(options);
        if (gammaField.getType() == float.class) return gammaField.getFloat(options);
        return (float) McReflect.simpleOptionGetValue(gammaField.get(options));
    }

    private void writeGamma(Object handle, Object options, float value) throws Exception {
        if (handle instanceof OptionInstanceAccessor261) {
            // gamma est un OptionInstance<Double> (vérifié javap) — boxing fixe,
            // contrairement à ZoomModule.fov/sensitivity (Integer/Float/Double
            // selon le champ) qui doivent détecter le type de la valeur courante.
            ((OptionInstanceAccessor261) handle).la$setValue(Double.valueOf(value));
            return;
        }
        Field gammaField = (Field) handle;
        if (gammaField.getType() == double.class) { gammaField.setDouble(options, value); return; }
        if (gammaField.getType() == float.class) { gammaField.setFloat(options, value); return; }
        McReflect.simpleOptionSetValue(gammaField.get(options), value);
    }

    /**
     * 26.1.2 sans réflexion — {@code MinecraftAccessor261#la$options()}
     * (architecture apimixin, 2026-08-26 §22). Repli réflexion multi-bracket
     * sinon.
     */
    private Object optionsInstance() throws Exception {
        try {
            Object mc = Minecraft.getInstance();
            if (mc instanceof MinecraftAccessor261) {
                Object options = ((MinecraftAccessor261) mc).la$options();
                if (options != null) return options;
            }
        } catch (Throwable ignored) {}
        Object mc = McReflect.minecraftClient();
        if (mc == null) return null;
        return McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
    }

    /**
     * @return soit un {@code OptionInstanceAccessor261} (26.1.2, voir {@link
     * OptionsAccessor261#la$gamma()}), soit un {@code Field} (repli réflexion
     * multi-bracket) — même détection que {@code ZoomModule#fovHandle}.
     */
    private Object gammaHandle(Object options) throws Exception {
        if (options instanceof OptionsAccessor261) {
            Object gammaOption = ((OptionsAccessor261) options).la$gamma();
            if (gammaOption instanceof OptionInstanceAccessor261) return gammaOption;
        }
        return McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "gamma");
    }
}
