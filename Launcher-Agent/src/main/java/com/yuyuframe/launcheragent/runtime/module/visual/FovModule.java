package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.v26_1.core.OptionInstanceAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.OptionsAccessor261;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import net.minecraft.client.Options;

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

    /**
     * BUG TROUVÉ (2026-08-27, migration vers les accessors) : ce module lisait
     * {@code GameOptions.fov} comme un CHAMP {@code float}
     * ({@code Field.getFloat}). Depuis la 1.19, {@code Options.fov} n'est plus
     * un float mais un {@code OptionInstance<Integer>} (vérifié : c'est le
     * type que déclare {@code OptionsAccessor261#la$fov()}) — {@code getFloat}
     * y levait donc un {@code IllegalArgumentException} à chaque tick, avalé
     * par le {@code catch} en simple ligne de diag. <b>Le module ne faisait
     * rien du tout sur 26.1.2.</b> Il passe désormais par les mêmes accessors
     * que {@code ZoomModule}, qui avait déjà le traitement correct.
     */
    @Override
    public void onTick() {
        OptionInstanceAccessor261 fov = fovOption();
        if (fov == null) { diag("fovOption == null"); return; }
        try {
            Object current = fov.la$value();
            float before = (current instanceof Number) ? ((Number) current).floatValue() : -1f;
            if (savedVanillaFov < 0f) savedVanillaFov = before;
            fov.la$setValue(boxLike(current, fovValue));
            diag("before=" + before + " target=" + fovValue);
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
            OptionInstanceAccessor261 fov = fovOption();
            if (fov != null) fov.la$setValue(boxLike(fov.la$value(), savedVanillaFov));
        } catch (Throwable t) {
            diag("restauration: " + t);
        } finally {
            savedVanillaFov = -1f;
        }
    }

    /**
     * {@code Options.fov} par les accessors Mixin ({@code ClientData} →
     * {@code OptionsAccessor261} → {@code OptionInstanceAccessor261}) — zéro
     * réflexion, et le même chemin que {@code ZoomModule}, qui écrit lui aussi
     * le champ {@code value} DIRECTEMENT plutôt que par {@code setValue()} :
     * ce dernier déclenche la validation vanilla, qui clampe la valeur.
     */
    private OptionInstanceAccessor261 fovOption() {
        Options options = ClientData.options();
        if (!(options instanceof OptionsAccessor261)) return null;
        Object fov = ((OptionsAccessor261) options).la$fov();
        return (fov instanceof OptionInstanceAccessor261) ? (OptionInstanceAccessor261) fov : null;
    }

    /**
     * Réencapsule dans le MÊME type que la valeur courante. {@code
     * OptionInstance<Integer>} pour le FOV sur 26.1.2 : y écrire un
     * {@code Float} compilerait (le champ est déclaré {@code Object} côté
     * accessor) mais planterait au premier déballage côté vanilla.
     */
    private static Object boxLike(Object current, float value) {
        if (current instanceof Integer) return Integer.valueOf(Math.round(value));
        if (current instanceof Double) return Double.valueOf(value);
        return Float.valueOf(value);
    }
}
