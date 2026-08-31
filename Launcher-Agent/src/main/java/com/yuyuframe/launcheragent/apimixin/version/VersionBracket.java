package com.yuyuframe.launcheragent.apimixin.version;

import java.util.function.Predicate;

/**
 * Une tranche de version Minecraft supportée par le LauncherAgent — regroupe
 * tout ce qui dépend de la version au moment du bootstrap Mixin (quelle
 * config charger, quel indice de nom de jar Yarn privilégier, quelles
 * versions MC en font partie). Voir {@link VersionBracketRegistry} pour la
 * liste des tranches réellement supportées et la convention pour en ajouter
 * une nouvelle.
 */
public final class VersionBracket {

    public final String key;
    public final String mixinConfigResource;
    /**
     * Config Mixin {@code apimixin/} additionnelle (ROADMAP-agent.md §3.2/§4) —
     * {@code null} tant qu'un bracket n'a pas encore son propre pull apimixin/
     * (aujourd'hui seul 26.1.2 en a une). Chargée EN PLUS de {@link
     * #mixinConfigResource} (jamais à sa place — la config legacy correspondante
     * reste tissée telle quelle, voir IsolatedBootstrap.start()) : le tissage
     * RÉEL de chaque mixin backé par un {@link com.yuyuframe.launcheragent.apimixin.HookPoint}
     * reste filtré mixin par mixin, AVANT même que Mixin ne charge le JSON —
     * voir {@code IsolatedBootstrap.filterConfigByHookPoints()} — lister un
     * mixin ici ne suffit pas à ce qu'il weave, voir la javadoc de {@code
     * HookPoint}.
     */
    public final String apiMixinConfigResource;
    public final String yarnJarNameHint;
    public final Predicate<String> matcher;

    /**
     * Faux = tranche DÉCLARÉE mais pas active : {@link VersionBracketRegistry#resolve}
     * l'ignore, donc le bootstrap Mixin s'arrête proprement sur cette version
     * (message clair, agent qui continue de tourner, aucun mixin appliqué).
     *
     * <p>Existe pour geler les brackets en attente de rework sans supprimer
     * leur déclaration — celle-ci porte le matcher, le nom de config, l'indice
     * de jar Yarn et surtout les commentaires expliquant POURQUOI chaque
     * tranche est resserrée à une version exacte. Les commenter ferait perdre
     * tout ça ; un booléen les garde relisibles et les réactive en un mot.
     */
    public final boolean enabled;

    public VersionBracket(String key, String mixinConfigResource, String yarnJarNameHint, Predicate<String> matcher) {
        this(key, mixinConfigResource, null, yarnJarNameHint, matcher, true);
    }

    public VersionBracket(String key, String mixinConfigResource, String apiMixinConfigResource, String yarnJarNameHint, Predicate<String> matcher) {
        this(key, mixinConfigResource, apiMixinConfigResource, yarnJarNameHint, matcher, true);
    }

    public VersionBracket(String key, String mixinConfigResource, String apiMixinConfigResource,
                          String yarnJarNameHint, Predicate<String> matcher, boolean enabled) {
        this.key = key;
        this.mixinConfigResource = mixinConfigResource;
        this.apiMixinConfigResource = apiMixinConfigResource;
        this.yarnJarNameHint = yarnJarNameHint;
        this.matcher = matcher;
        this.enabled = enabled;
    }

    /** Même tranche, gelée — voir {@link #enabled}. Fluent, pour que la déclaration reste lisible telle quelle. */
    public VersionBracket disabled() {
        return new VersionBracket(key, mixinConfigResource, apiMixinConfigResource, yarnJarNameHint, matcher, false);
    }
}
