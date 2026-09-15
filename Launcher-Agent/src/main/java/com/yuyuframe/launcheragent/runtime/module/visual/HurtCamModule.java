package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Désactive le tremblement/inclinaison de la caméra à la prise de dégâts —
 * port de PvP-Mod HurtCamConfig. Aucun réglage propre (un seul Switch dans
 * l'original) : annule {@link HookPoint#CAMERA_HURT_TILT} tant qu'il est actif
 * (auparavant lu par {@code MixinGameRenderer189} via {@code ModuleRegistry}).
 */
public final class HurtCamModule extends LauncherModule {
    public HurtCamModule() {
        super("hurt-cam", "Hurt Cam", "Supprime le tremblement de caméra à la prise de dégâts", false,
            HookPoint.CAMERA_HURT_TILT);
        VanillaHookRegistry.register(HookPoint.CAMERA_HURT_TILT, ctx -> isEnabled());
    }
}
