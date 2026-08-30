package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Permet de swing l'épée en bloquant sur un bloc (comme en 1.7) — inspiré de
 * TAKfsg/oldblockhit-legacy-fabric (mod Fabric/Yarn, repo GitHub, features
 * "useSwing"/"enableBlockHits" dans {@code HeldItemRendererMixin.java}).
 * Juste un marqueur ici, toute la logique vit dans
 * {@code MixinSwingWhileBlocking189}.
 */
public final class SwingWhileBlockingModule extends LauncherModule {
    public SwingWhileBlockingModule() {
        super("swing-while-blocking", "Swing en bloquant", "Permet de faire swing l'épée en bloquant sur un bloc, comme en 1.7", false);
    }
}
