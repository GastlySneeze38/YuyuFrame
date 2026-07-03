package com.yuyuframe.launcheragent.runtime.version;

import java.util.ArrayList;
import java.util.List;

/**
 * Registre des tranches de version Minecraft réellement supportées par le
 * LauncherAgent. Remplace l'ancien branchement binaire
 * ({@code MinecraftVersionDetector.isLegacy189()} utilisé directement dans
 * {@code IsolatedBootstrap}) par une liste extensible — objectif final :
 * couvrir 1.7 à 1.26.1, mais chaque tranche n'est ajoutée ICI qu'une fois ses
 * Mixins réellement écrits et vérifiés en jeu (voir historique du projet :
 * une version qui tombe dans le mauvais bracket échoue SILENCIEUSEMENT,
 * Mixin logue juste un WARN et rejette la classe concernée).
 *
 * Convention pour ajouter une nouvelle tranche (ex: 1.12.2) :
 *   1. Écrire ses Mixins dans un nouveau package {@code mixin/client/v1_12/}
 *      (même convention que {@code v1_8/} existant).
 *   2. Créer {@code mixins.launcheragent-1.12.json} dans src/main/resources.
 *   3. Si des Mixins de cette tranche utilisent des noms Yarn "named" (pas
 *      des descripteurs obfusqués bruts), ajouter leurs entrées dans
 *      {@code LauncherMixinService.REFMAP_ENTRIES} (nouveau bloc commenté à
 *      la suite des blocs existants — la génération elle-même est générique).
 *   4. Ajouter UNE entrée {@link VersionBracket} ci-dessous. Aucun autre
 *      fichier n'a besoin de changer (build.bat compile tout par wildcard,
 *      le système de mappings est déjà générique).
 */
public final class VersionBracketRegistry {

    private VersionBracketRegistry() {}

    private static final List<VersionBracket> BRACKETS = new ArrayList<>();

    static {
        BRACKETS.add(new VersionBracket(
            "1_8_9",
            "mixins.launcheragent-1.8.json",
            "1.8.9",
            MinecraftVersionDetector::isLegacy189));

        // Resserré à la version exacte (avant : tout ce qui n'était pas
        // 1.8.x tombait implicitement ici) — une version 1.21.x non testée
        // ne doit PAS hériter silencieusement des Mixins vérifiés seulement
        // pour 1.21.11.
        BRACKETS.add(new VersionBracket(
            "1_21_11",
            "mixins.launcheragent.json",
            "1.21.11",
            version -> "1.21.11".equals(version)));
    }

    /** Résout la tranche correspondant à la version détectée, ou {@code null} si aucune ne correspond. */
    public static VersionBracket resolve(String mcVersion) {
        for (VersionBracket bracket : BRACKETS) {
            if (bracket.matcher.test(mcVersion)) return bracket;
        }
        return null;
    }
}
