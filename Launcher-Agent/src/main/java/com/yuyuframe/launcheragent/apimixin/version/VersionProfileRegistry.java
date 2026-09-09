package com.yuyuframe.launcheragent.apimixin.version;

import java.util.ArrayList;
import java.util.List;

/**
 * Tranches de version Minecraft connues du LauncherAgent — remplace
 * {@code VersionBracketRegistry} (2026-09-09).
 *
 * <h2>Ce qu'il ne fait PLUS</h2>
 *
 * Il ne décide plus quels mixins sont tissés. Cette responsabilité est passée
 * à {@link com.yuyuframe.launcheragent.apimixin.MixinHookPointRegistry}, où la
 * version est une colonne explicite de la table déclarative — le JSON de
 * config Mixin en est GÉNÉRÉ au démarrage. Il ne reste donc ici que ce qui se
 * décide avant tout chargement Mixin : quels mappings charger, et l'éventuelle
 * config héritée d'une tranche pas encore migrée.
 *
 * <h2>Ajouter une version</h2>
 * <ol>
 *   <li>Écrire ses mixins dans {@code apimixin/vXX_Y/}.</li>
 *   <li>Les déclarer dans {@code MixinHookPointRegistry} avec la chaîne de
 *       version comme clé ({@code gate}/{@code always}).</li>
 *   <li>Si des mixins utilisent des noms Yarn "named" (pas des descripteurs
 *       obfusqués bruts), ajouter leurs entrées dans
 *       {@code LauncherMixinService.REFMAP_ENTRIES}.</li>
 *   <li>Ajouter UN {@link VersionProfile} ci-dessous.</li>
 * </ol>
 * Aucun JSON à créer : le template partagé
 * {@code mixins.launcheragent-apimixin.template.json} fournit l'en-tête pour
 * toutes les versions. {@code build.bat} compile tout par wildcard.
 *
 * <h2>⚠️ SEULE la 26.1.2 est active (2026-08-31)</h2>
 *
 * Décision de l'utilisateur, en attendant son rework des autres versions : les
 * quatre autres tranches sont GELÉES ({@code .frozen()}). Elles restent
 * DÉCLARÉES — leurs commentaires expliquent pourquoi chacune est resserrée à
 * une version exacte, ce qui n'a aucune raison de disparaître — mais
 * {@link #resolve} les ignore.
 *
 * <p>Conséquence sur une version gelée : le bootstrap Mixin s'arrête
 * proprement, l'agent reste chargé et ne tisse RIEN. Le jeu se lance
 * normalement, simplement sans aucune fonctionnalité de l'agent. C'est
 * volontairement le même chemin qu'une version inconnue, avec un message qui
 * distingue les deux cas.
 */
public final class VersionProfileRegistry {

    private VersionProfileRegistry() {}

    private static final List<VersionProfile> PROFILES = new ArrayList<>();

    static {
        PROFILES.add(new VersionProfile(
            "1_8_9",
            new String[]{ "1.8.*" },
            "1.8.9",
            "1.8.9",
            "mixins.launcheragent-1.8.json").frozen());

        // Resserré à la version exacte (avant : tout ce qui n'était pas
        // 1.8.x tombait implicitement ici) — une version 1.21.x non testée
        // ne doit PAS hériter silencieusement des Mixins vérifiés seulement
        // pour 1.21.11.
        PROFILES.add(new VersionProfile(
            "1_21_11",
            new String[]{ "1.21.11" },
            "1.21.11",
            "1.21.11",
            "mixins.launcheragent.json").frozen());

        // Bracket "B" — 1.13 à 1.16.x : LWJGL3/GLFW comme le pipeline 1.21.11,
        // mais contexte GL encore en dessous du Core Profile 3.2 imposé depuis
        // la 1.17, donc dessin immédiat encore possible (voir
        // MinecraftVersionDetector.supportsFixedFunctionDrawing et
        // GlobalUiRenderMixin116). Vérifié concrètement seulement pour 1.16.5
        // au moment de l'écriture — resserré à ce sous-ensemble plutôt que
        // d'inclure toute la 1.13-1.16 par simple confiance dans le
        // raisonnement architectural (même risque qu'expliqué ci-dessus pour
        // la 1.21.x : une version non testée peut échouer silencieusement).
        PROFILES.add(new VersionProfile(
            "1_16",
            new String[]{ "1.16.*" },
            "1.16.5",
            "1.16.5",
            "mixins.launcheragent-1.16.json").frozen());

        // Bracket "C" — 1.17 à 1.20.4 : Core Profile OpenGL 3.2 obligatoire
        // (pipeline fixe supprimé), mais GameRenderer.render(FJZ)V garde la
        // MÊME signature que le bracket "B" (vérifié via
        // mappings/yarn-1.20.4-mergedv2.jar) — seul MixinCrosshair1204 diffère
        // réellement de son équivalent 1.16.5 (InGameHud.renderCrosshair prend
        // un DrawContext, pas un MatrixStack). Resserré à la version exacte
        // 1.20.4 pour l'instant, pas encore élargi à toute la 1.17-1.20.4.
        PROFILES.add(new VersionProfile(
            "1_20_4",
            new String[]{ "1.20.4" },
            "1.20.4",
            "1.20.4",
            "mixins.launcheragent-1.20.4.json").frozen());

        // Bracket "D" — ~1.21 à 1.21.5 : même profil OpenGL Core que le
        // bracket "C" (1.20.4), mais GameRenderer.render change de signature —
        // render(RenderTickCounter, boolean), RenderTickCounter introduit entre
        // la 1.20.4 et la 1.21 (vérifié via mappings/yarn-1.21.4-mergedv2.jar)
        // — MÊME signature que le bracket 1.21.11, mais celui-ci utilise des
        // noms obfusqués figés en dur (technique fragile propre à un seul jar
        // de mappings) plutôt que la résolution 100% dynamique utilisée ici.
        // Resserré à 1.21.4 (seule version vérifiée par javap/mappings au
        // moment de l'écriture), pas encore élargi à toute la 1.21-1.21.5.
        PROFILES.add(new VersionProfile(
            "1_21_4",
            new String[]{ "1.21.4" },
            "1.21.4",
            "1.21.4",
            "mixins.launcheragent-1.21.4.json").frozen());

        // Bracket "E" — 26.1.2 : MC N'EST PLUS OBFUSQUÉ à partir de la ligne
        // 26.1.x (Mojang a arrêté de publier des mappings d'obfuscation,
        // confirmé absent à la fois du manifeste officiel ET de Yarn/Quilt/
        // intermediary Fabric — voir FabricMC/fabric-loom#1585 et le guide de
        // portage officiel https://docs.fabricmc.net/develop/porting/, "The
        // 26.1 version of Minecraft is unobfuscated"). Les mixins de cette
        // tranche (apimixin/v26_1/) utilisent donc les VRAIS noms Mojang
        // directement en dur — chaque classe/méthode/champ vérifié
        // individuellement via javap sur le jar client 26.1.2 réel. AUCUN jar
        // Yarn n'est chargé (yarnJarNameHint=null) : MappingsRegistry reste en
        // scheme OFFICIAL avec YarnMappings.isLoaded()==false, état déjà validé
        // sans régression. Entièrement déclarative : plus aucune config Mixin
        // en ressource, la liste des mixins vit dans MixinHookPointRegistry.
        PROFILES.add(new VersionProfile(
            "26_1_2",
            new String[]{ "26.1.2" },
            "26.1.2",
            null,
            null));
    }

    /**
     * Résout le profil correspondant à la version détectée, ou {@code null}
     * si aucun ne correspond OU si celui qui correspond est gelé.
     *
     * <p>Une tranche gelée est traitée exactement comme une version inconnue :
     * l'appelant journalise et abandonne le bootstrap Mixin proprement, l'agent
     * continue de tourner sans rien tisser (voir {@code IsolatedBootstrap}).
     * C'est le comportement voulu — mieux vaut un agent inerte qu'un agent qui
     * applique des mixins non retestés.
     *
     * <p>Le message distingue les deux cas, sinon « version non supportée » sur
     * une 1.8.9 qui marchait la veille serait incompréhensible.
     *
     * <p>Correspondance EXACTE d'abord, familles ({@code "1.16.*"}) seulement
     * ensuite : un profil resserré sur une version précise doit toujours
     * gagner sur un profil de famille qui l'engloberait.
     */
    public static VersionProfile resolve(String mcVersion) {
        VersionProfile match = findExact(mcVersion);
        if (match == null) match = findByFamily(mcVersion);
        if (match == null) return null;
        if (match.frozen) {
            com.yuyuframe.launcheragent.base.log.LauncherLog.err(
                "[LauncherAgent] Version MC \"" + mcVersion + "\" reconnue (profil " + match.key
                + ") mais GELÉE — voir VersionProfileRegistry, en attente du rework de cette version. "
                + "Aucun mixin ne sera appliqué.");
            return null;
        }
        return match;
    }

    private static VersionProfile findExact(String mcVersion) {
        for (VersionProfile p : PROFILES) {
            for (String v : p.versions) {
                if (v != null && !v.endsWith(".*") && v.equals(mcVersion)) return p;
            }
        }
        return null;
    }

    private static VersionProfile findByFamily(String mcVersion) {
        for (VersionProfile p : PROFILES) {
            if (p.covers(mcVersion)) return p;
        }
        return null;
    }
}
