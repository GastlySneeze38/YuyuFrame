package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.game.GameOptions;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Permet de swing l'épée en bloquant sur un bloc (comme en 1.7) — inspiré de
 * TAKfsg/oldblockhit-legacy-fabric (mod Fabric/Yarn, repo GitHub, features
 * "useSwing"/"enableBlockHits" dans {@code HeldItemRendererMixin.java}).
 *
 * <p>Logique ici depuis le 2026-09-15 (elle vivait dans
 * {@code MixinSwingWhileBlocking189}, entièrement réflexive) :
 * <ul>
 *   <li>au front où attaque ET utilisation sont enfoncées, réticule sur un
 *       bloc, l'animation du swing est relancée — côté client seulement,
 *       aucun paquet ({@link #onTick}, appelé à chaque image) ;</li>
 *   <li>le swing reste visible pendant le blocage
 *       ({@link HookPoint#HELD_ITEM_SWING_PROGRESS}, action {@code "block"}).</li>
 * </ul>
 */
public final class SwingWhileBlockingModule extends LauncherModule {

    private boolean prevBothDown;

    public SwingWhileBlockingModule() {
        super("swing-while-blocking", "Swing en bloquant", "Permet de faire swing l'épée en bloquant sur un bloc, comme en 1.7", false,
            HookPoint.HELD_ITEM_SWING_PROGRESS);
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_SWING_PROGRESS,
            ctx -> isEnabled() && "block".equals(ctx) ? Boolean.TRUE : null);
    }

    @Override
    public void onTick() {
        Object[] keys = GameOptions.actionKeys();
        boolean bothDown = keys != null
            && GameOptions.keyDown(keys[GameOptions.KEY_ATTACK])
            && GameOptions.keyDown(keys[GameOptions.KEY_USE])
            && "block".equals(ClientData.crosshairTarget());
        if (bothDown && !prevBothDown) PlayerData.restartSwingAnimation();
        prevBothDown = bothDown;
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        prevBothDown = false;
    }
}
