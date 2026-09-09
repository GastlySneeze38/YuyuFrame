package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.runtime.game.GameOptions;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * FOV personnalisé — port de PvP-Mod FovConfig/FovHandler. Fixe
 * {@code GameOptions.fov} à CHAQUE frame tant que le module est activé (voir
 * {@link #onTick}, appelé par ModuleRegistry.tickAll() indépendamment de tout
 * écran ouvert, comme le TickEvent de l'original) ; le sprint/ralenti est
 * neutralisé séparément par {@code MixinGameRenderer189} (même découpage que
 * la référence, dont le seul vrai Mixin gère ce cas précis).
 */
public final class FovModule extends LauncherModule {

    public float fovValue = 90f;

    @Override
    protected void settings(SettingList s) {
        s.slider("fov", "FOV", "Réglages", 30f, 110f, 1f, () -> fovValue, v -> fovValue = v);
    }

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
        Object fov = GameOptions.fovHandle();
        if (fov == null) { diag("fovOption == null"); return; }
        try {
            float before = (float) GameOptions.value(fov, -1d);
            if (savedVanillaFov < 0f) savedVanillaFov = before;
            GameOptions.setValue(fov, fovValue);
            diag("before=" + before + " target=" + fovValue);
        } catch (Throwable t) {
            diag("exception: " + t);
        }
    }

    private void diag(String msg) {
        if (DIAG_LOGGED) return;
        DIAG_LOGGED = true;
        com.yuyuframe.launcheragent.base.log.LauncherLog.info("[FovModule] diag: " + msg);
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        try {
            if (savedVanillaFov < 0f) return;
            GameOptions.setValue(GameOptions.fovHandle(), savedVanillaFov);
        } catch (Throwable t) {
            diag("restauration: " + t);
        } finally {
            savedVanillaFov = -1f;
        }
    }

}
