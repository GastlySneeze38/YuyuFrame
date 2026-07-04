package com.yuyuframe.launcheragent.runtime.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Regroupe plusieurs {@link LauncherModule} sous UNE seule carte à l'écran
 * d'accueil — demande utilisateur : les modules "confort visuel" (FOV, Hurt
 * Cam, Sprint/Sneak) et "1.7" (swing, item, arc...) partagent chacun un seul
 * écran de config à onglets, au lieu d'une carte séparée par module (voir
 * {@link com.yuyuframe.launcheragent.runtime.ui.ingameui.UiModGroupConfigScreen}).
 *
 * Purement une façade d'AFFICHAGE : les modules membres restent enregistrés
 * individuellement dans {@link ModuleRegistry} (mêmes {@code onTick}/
 * {@code onRenderOverlay}/persistance qu'avant, rien ne change côté logique),
 * seule la présentation dans le menu change.
 *
 * {@link #tabs} découple "quels modules appartiennent à ce groupe" ({@link #members},
 * toujours une liste PLATE — sert entre autres à {@link ModuleRegistry#ungrouped()})
 * de "comment les regrouper visuellement en onglets" : par défaut un onglet
 * par module ({@link #ModuleGroup(String, String, String, List)}), mais un
 * groupe peut aussi fournir explicitement des {@link Tab} qui rassemblent
 * PLUSIEURS modules apparentés sous un seul onglet (ex: les deux "Culling
 * face arrière (joueur/entités)" ou les 3 "Distance de rendu" du groupe
 * "Optimisations" — demandé explicitement pour réduire le nombre d'onglets
 * et la longueur de leurs noms, voir ModuleRegistry).
 */
public final class ModuleGroup {
    public final String id;
    public final String name;
    public final String description;
    public final List<LauncherModule> members;
    public final List<Tab> tabs;

    public ModuleGroup(String id, String name, String description, List<LauncherModule> members) {
        this(id, name, description, members, oneTabPerMember(members));
    }

    public ModuleGroup(String id, String name, String description, List<LauncherModule> members, List<Tab> tabs) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.members = members;
        this.tabs = tabs;
    }

    private static List<Tab> oneTabPerMember(List<LauncherModule> members) {
        List<Tab> result = new ArrayList<>();
        for (LauncherModule m : members) {
            if (m != null) result.add(new Tab(m.name, Collections.singletonList(m)));
        }
        return result;
    }

    /** Un onglet de l'écran de config du groupe — peut porter PLUSIEURS modules empilés à la suite (voir javadoc de classe). */
    public static final class Tab {
        public final String label;
        public final List<LauncherModule> modules;

        public Tab(String label, List<LauncherModule> modules) {
            this.label = label;
            this.modules = modules;
        }
    }
}
