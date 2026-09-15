package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.game.GameOptions;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Bascule le sneak (au lieu de le maintenir) — technique reprise du vrai
 * binaire PolySprint : on change la valeur que la logique de déplacement LIT
 * pour la touche, pas la touche elle-même.
 *
 * <p>Logique ici depuis le 2026-09-15 : le front d'appui est détecté sur l'état
 * RÉEL de la touche ({@link #onTick}, chaque image), et
 * {@link HookPoint#SNEAK_KEY_HELD} rend « maintenue » tant que la bascule est
 * active. Elle vivait dans {@code MixinToggleSneak189}, qui écrivait le champ
 * de la touche par réflexion.
 */
public final class ToggleSneakModule extends LauncherModule {

    private final ToggleKey toggle = new ToggleKey(GameOptions.KEY_SNEAK);

    public ToggleSneakModule() {
        super("toggle-sneak", "Toggle Sneak", "Un appui sur la touche sneak bascule l'état au lieu de le maintenir", false,
            HookPoint.SNEAK_KEY_HELD);
        VanillaHookRegistry.registerValue(HookPoint.SNEAK_KEY_HELD, ctx -> isEnabled() ? toggle.held() : null);
    }

    @Override
    public void onTick() {
        toggle.poll();
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        toggle.reset();
    }
}
