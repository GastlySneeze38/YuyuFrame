package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Même principe que {@link PlayerBackfaceCullingModule} mais appliqué à
 * TOUTES les entités vivantes (LivingEntityRenderer, la classe de base
 * partagée par zombies/vaches/squelettes/etc — les joueurs en héritent aussi
 * mais ont déjà leur propre toggle séparé et plus ciblé). Séparé du toggle
 * joueur pour un contrôle fin façon PolyPatcher ("Entity Back-face Culling" /
 * "Player Back-face Culling" sont deux réglages distincts chez eux) : un
 * skin/mob resource pack au winding incorrect peut montrer un trou, plus
 * probable sur des mods de mobs custom que sur le modèle joueur standard.
 */
public final class EntityBackfaceCullingModule extends LauncherModule {

    public EntityBackfaceCullingModule() {
        super("entity-backface-culling", "Culling face arrière (entités)", "Ne rend pas les faces cachées des modèles d'entités vivantes — désactive si un mob/resource pack affiche un trou", false);
    }
}
