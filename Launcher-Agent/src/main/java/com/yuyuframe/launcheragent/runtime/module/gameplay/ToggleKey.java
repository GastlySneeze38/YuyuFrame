package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.runtime.game.GameOptions;

/**
 * État de bascule d'une touche d'action — partagé par {@link ToggleSneakModule}
 * et {@link ToggleSprintModule}.
 */
final class ToggleKey {

    private final int keyIndex;
    private boolean prevDown;
    private boolean toggled;

    /** @param keyIndex index dans {@link GameOptions#actionKeys()} ({@code KEY_*}). */
    ToggleKey(int keyIndex) {
        this.keyIndex = keyIndex;
    }

    /** Lit l'état réel de la touche et bascule sur chaque nouvel appui. */
    void poll() {
        Object[] keys = GameOptions.actionKeys();
        boolean down = keys != null && GameOptions.keyDown(keys[keyIndex]);
        if (down && !prevDown) toggled = !toggled;
        prevDown = down;
    }

    /** Valeur à substituer : « maintenue » si la bascule est active, sinon l'état réel ({@code null}). */
    Object held() {
        return toggled ? Boolean.TRUE : null;
    }

    void reset() {
        prevDown = false;
        toggled = false;
    }
}
