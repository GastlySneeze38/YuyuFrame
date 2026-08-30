package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Désactive le tremblement/inclinaison de la caméra à la prise de dégâts —
 * port de PvP-Mod HurtCamConfig. Aucun champ de réglage propre (un seul
 * Switch dans l'original) : le toggle de la carte est lu directement par
 * {@code MixinGameRenderer189} via {@code ModuleRegistry.get("hurt-cam")}.
 */
public final class HurtCamModule extends LauncherModule {
    public HurtCamModule() {
        super("hurt-cam", "Hurt Cam", "Supprime le tremblement de caméra à la prise de dégâts", false);
    }
}
