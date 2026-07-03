package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Animation de consommation (manger/boire) à l'ancienne, 1ère personne —
 * inspiré de TAKfsg/oldblockhit-legacy-fabric (mod Fabric/Yarn, repo GitHub,
 * {@code Config.oldConsume} dans {@code HeldItemRendererMixin.java}). Juste
 * un marqueur ici, toute la logique vit dans {@code MixinOldConsume189}.
 */
public final class OldConsumeModule extends LauncherModule {
    public OldConsumeModule() {
        super("old-consume", "Manger/Boire 1.7", "Animation de consommation à l'ancienne, 1ère personne", false);
    }
}
