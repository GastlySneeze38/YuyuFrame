package com.yuyuframe.launcheragent.runtime.ui;

import java.util.List;

/**
 * Regroupe plusieurs {@link LauncherModule} sous UNE seule carte à l'écran
 * d'accueil — demande utilisateur : les modules "confort visuel" (FOV, Hurt
 * Cam, Sprint/Sneak) et "1.7" (swing, item, arc...) partagent chacun un seul
 * écran de config à onglets (un onglet par module membre, voir
 * {@link com.yuyuframe.launcheragent.runtime.ui.ingameui.UiModGroupConfigScreen})
 * au lieu d'une carte séparée par module.
 *
 * Purement une façade d'AFFICHAGE : les modules membres restent enregistrés
 * individuellement dans {@link ModuleRegistry} (mêmes {@code onTick}/
 * {@code onRenderOverlay}/persistance qu'avant, rien ne change côté logique),
 * seule la présentation dans le menu change.
 */
public final class ModuleGroup {
    public final String id;
    public final String name;
    public final String description;
    public final List<LauncherModule> members;

    public ModuleGroup(String id, String name, String description, List<LauncherModule> members) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.members = members;
    }
}
