package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Active GL_CULL_FACE (via GlStateManager.enableCull/disableCull, jamais LWJGL
 * direct — voir MixinPlayerBackfaceCulling189) UNIQUEMENT autour du rendu du
 * modèle joueur (PlayerEntityRenderer) — le vanilla ne culle jamais les faces
 * arrière des entités, doublant le travail de fragment shading pour rien sur
 * des triangles jamais visibles. Défaut désactivé (contrairement aux items
 * non empilés, purement cosmétique/sans risque) : un skin/resource pack au
 * winding incorrect pourrait théoriquement montrer un trou — même principe
 * que "Player Back-face Culling" de PolyPatcher.
 */
public final class PlayerBackfaceCullingModule extends LauncherModule {

    public PlayerBackfaceCullingModule() {
        super("player-backface-culling", "Culling face arrière (joueur)", "Ne rend pas les faces cachées du modèle joueur — gain FPS, désactive si un skin affiche un trou", false);
    }
}
