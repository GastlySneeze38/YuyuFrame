package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.v26_1.core.MinecraftAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.OptionInstanceAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.OptionsAccessor261;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

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
        return ((Number) ((OptionInstanceAccessor261) handle).la$value()).floatValue();
    }

    private void writeGamma(Object handle, Object options, float value) throws Exception {
        // gamma est un OptionInstance<Double> (vérifié javap) — boxing fixe,
        // contrairement à ZoomModule.fov/sensitivity (Integer/Float/Double
        // selon le champ) qui doivent détecter le type de la valeur courante.
        // Écrit le champ value DIRECTEMENT : setValue() déclencherait la
        // validation vanilla, qui clampe (voir OptionInstanceAccessor261).
        ((OptionInstanceAccessor261) handle).la$setValue(Double.valueOf(value));
    }

    /** Options par l'accessor Mixin, via {@code ClientData} — zéro réflexion (repli multi-bracket supprimé le 2026-08-27). */
    private Object optionsInstance() throws Exception {
        return ClientData.options();
    }

    /**
     * @return l'{@code OptionInstanceAccessor261} de {@code Options.gamma}
     * (voir {@link OptionsAccessor261#la$gamma()}), ou {@code null} hors
     * bracket 26.1.2 — même forme que {@code ZoomModule#fovHandle}.
     */
    private Object gammaHandle(Object options) throws Exception {
        if (!(options instanceof OptionsAccessor261)) return null;
        Object gammaOption = ((OptionsAccessor261) options).la$gamma();
        return (gammaOption instanceof OptionInstanceAccessor261) ? gammaOption : null;
    }
}
