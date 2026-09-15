package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.game.GameOptions;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Bascule le sprint (au lieu de le maintenir) — même mécanisme que
 * {@link ToggleSneakModule}, sur {@link HookPoint#SPRINT_KEY_HELD}.
 *
 * <p>Détecter le front sur l'état réel, à part, n'est pas un détail ici : le
 * jeu ne lit la touche sprint que quand le joueur PEUT sprinter (en avant,
 * sans objet utilisé…). Une bascule détectée dans le hook ignorerait un appui
 * fait à l'arrêt. Logique ici depuis le 2026-09-15 (elle vivait dans
 * {@code MixinToggleSprint189}).
 */
public final class ToggleSprintModule extends LauncherModule {

    private final ToggleKey toggle = new ToggleKey(GameOptions.KEY_SPRINT);

    public ToggleSprintModule() {
        super("toggle-sprint", "Toggle Sprint", "Un appui sur la touche sprint bascule l'état au lieu de le maintenir", false,
            HookPoint.SPRINT_KEY_HELD);
        VanillaHookRegistry.registerValue(HookPoint.SPRINT_KEY_HELD, ctx -> isEnabled() ? toggle.held() : null);
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
