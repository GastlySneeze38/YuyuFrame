package com.yuyuframe.launcheragent.runtime.version;

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
    public final String yarnJarNameHint;
    public final Predicate<String> matcher;

    public VersionBracket(String key, String mixinConfigResource, String yarnJarNameHint, Predicate<String> matcher) {
        this.key = key;
        this.mixinConfigResource = mixinConfigResource;
        this.yarnJarNameHint = yarnJarNameHint;
        this.matcher = matcher;
    }
}
