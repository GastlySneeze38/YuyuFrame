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

        // Bracket "B" (voir historique de session) — 1.13 à 1.16.x : LWJGL3/
        // GLFW comme le pipeline 1.21.11, mais contexte GL encore en dessous
        // du Core Profile 3.2 imposé depuis la 1.17, donc dessin immédiat
        // encore possible (voir MinecraftVersionDetector.supportsFixedFunctionDrawing
        // et GlobalUiRenderMixin116). Vérifié concrètement seulement pour
        // 1.16.5 au moment de l'écriture — resserré à ce sous-ensemble plutôt
        // que d'inclure toute la 1.13-1.16 par simple confiance dans le
        // raisonnement architectural (même risque qu'expliqué ci-dessus pour
        // la 1.21.x : une version non testée peut échouer silencieusement).
        BRACKETS.add(new VersionBracket(
            "1_16",
            "mixins.launcheragent-1.16.json",
            "1.16.5",
            version -> version != null && version.startsWith("1.16")));

        // Bracket "C" (voir historique de session) — 1.17 à 1.20.4 : Core
        // Profile OpenGL 3.2 obligatoire (pipeline fixe supprimé, voir
        // MinecraftVersionDetector.supportsFixedFunctionDrawing), mais
        // GameRenderer.render(FJZ)V garde la MÊME signature que le bracket
        // "B" (vérifié via mappings/yarn-1.20.4-mergedv2.jar) — seul
        // MixinCrosshair1204 diffère réellement de son équivalent 1.16.5
        // (InGameHud.renderCrosshair prend un DrawContext, pas un
        // MatrixStack). Resserré à la version exacte 1.20.4 pour l'instant,
        // pas encore élargi à toute la 1.17-1.20.4 (même prudence que pour
        // les autres brackets : une version non testée peut échouer
        // silencieusement).
        BRACKETS.add(new VersionBracket(
            "1_20_4",
            "mixins.launcheragent-1.20.4.json",
            "1.20.4",
            version -> "1.20.4".equals(version)));

        // Bracket "D" (voir historique de session) — ~1.21 à 1.21.5 : même
        // profil OpenGL Core que le bracket "C" (1.20.4), mais
        // GameRenderer.render change de signature — render(RenderTickCounter,
        // boolean), RenderTickCounter introduit entre la 1.20.4 et la 1.21
        // (vérifié via mappings/yarn-1.21.4-mergedv2.jar) — MÊME signature
        // que le bracket 1.21.11 déjà existant, mais celui-ci utilise des
        // noms obfusqués figés en dur (technique fragile propre à un seul
        // jar de mappings) plutôt que la résolution 100% dynamique utilisée
        // ici. Resserré à 1.21.4 (seule version vérifiée par javap/mappings
        // au moment de l'écriture), pas encore élargi à toute la 1.21-1.21.5.
        BRACKETS.add(new VersionBracket(
            "1_21_4",
            "mixins.launcheragent-1.21.4.json",
            "1.21.4",
            version -> "1.21.4".equals(version)));

        // Bracket "E" — 26.1.2 : MC N'EST PLUS OBFUSQUÉ à partir de la ligne
        // 26.1.x (Mojang a arrêté de publier des mappings d'obfuscation,
        // confirmé absent à la fois du manifeste officiel ET de Yarn/Quilt/
        // intermediary Fabric — voir FabricMC/fabric-loom#1585 et le guide de
        // portage officiel https://docs.fabricmc.net/develop/porting/, "The
        // 26.1 version of Minecraft is unobfuscated"). Les Mixins de ce
        // bracket (mixin/client/v26_1/) utilisent donc les VRAIS noms Mojang
        // directement en dur (ex: "net.minecraft.client.renderer.GameRenderer",
        // pas de résolution Yarn) — chaque classe/méthode/champ vérifié
        // individuellement via javap sur le jar client 26.1.2 réel (pas deviné
        // par simple renommage de convention). AUCUN jar Yarn n'est chargé
        // pour ce bracket : MappingsRegistry reste en scheme OFFICIAL avec
        // YarnMappings.isLoaded()==false, un état déjà validé sans régression
        // pour le lancement vanilla classique (voir MappingsRegistry). Resserré
        // à la version exacte 26.1.2 (seule version vérifiée au moment de
        // l'écriture) — MÊME PRUDENCE que les autres brackets : une version
        // non testée peut échouer silencieusement. NON VÉRIFIÉ EN JEU (aucun
        // moyen de lancer Minecraft depuis l'environnement où ce portage a été
        // écrit) — à valider en jeu avant tout usage en production.
        BRACKETS.add(new VersionBracket(
            "26_1_2",
            "mixins.launcheragent-26.1.json",
            "26.1.2",
            version -> "26.1.2".equals(version)));
    }

    /** Résout la tranche correspondant à la version détectée, ou {@code null} si aucune ne correspond. */
    public static VersionBracket resolve(String mcVersion) {
        for (VersionBracket bracket : BRACKETS) {
            if (bracket.matcher.test(mcVersion)) return bracket;
        }
        return null;
    }
}
