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
        // Nom raccourci (était "Culling face arrière (joueur)") — demande
        // explicite : trop long pour la sous-sidebar de UiModGroupConfigScreen,
        // qui affiche déjà "Culling face arrière" comme titre de l'onglet
        // (voir ModuleRegistry) — le nom du module lui-même n'a plus qu'à
        // porter la partie DISTINCTIVE ("joueur" vs "entités"), plus jamais
        // affiché seul ailleurs (voir ModuleGroup, module rattaché
        // uniquement via ce groupe, jamais sa propre carte).
        super("player-backface-culling", "Joueur", "Ne rend pas les faces cachées du modèle joueur — gain FPS, désactive si un skin affiche un trou", false);
    }
}
