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
 * <h2>Versions supportées (2026-09-16)</h2>
 *
 * Décision de l'utilisateur : seules la 1.8.9 et les versions 1.21.11+ sont
 * supportées. Les tranches 1.16.5, 1.20.4 et 1.21.4, gelées depuis le
 * 2026-08-31, ont été supprimées avec le package {@code mixin/} et leurs
 * configs JSON.
 *
 * <p>Le gel ({@code .frozen()}) reste disponible pour suspendre une tranche
 * sans supprimer sa déclaration : {@link #resolve} l'ignore alors, le bootstrap
 * Mixin s'arrête proprement, l'agent reste chargé et ne tisse RIEN. Le jeu se
 * lance normalement, simplement sans aucune fonctionnalité de l'agent — même
 * chemin qu'une version inconnue, avec un message qui distingue les deux cas.
 */
public final class VersionProfileRegistry {

    private VersionProfileRegistry() {}

    private static final List<VersionProfile> PROFILES = new ArrayList<>();

    static {
        // Refonte 1.8.9 (2026-09-13, docs/LauncherAgent/v1.8.9/README.md) :
        // tranche déclarative, mixins dans apimixin/v1_8_9 et
        // MixinHookPointRegistry sous "1.8.9".
        //
        // DÉGELÉE le 2026-09-15 (build v1159), après : liaisons de points
        // d'accès (v1146), Java 25 + agent en --release 25 (2026-09-14),
        // LWJGL 3 / legacy-lwjgl3 (v1150), ère gl3 (v1151-v1156) et modules
        // migrés sur la nouvelle architecture (v1158). Rien de tout ça n'avait
        // encore tourné en jeu : à valider mécanisme par mécanisme.
        PROFILES.add(new VersionProfile(
            "1_8_9",
            new String[]{ "1.8.*" },
            "1.8.9",
            "1.8.9",
            // gl3 depuis le 2026-09-14 : LWJGL 3 ouvre un contexte 3.2 de
            // compatibilité, les shaders #version 150 y tournent. L'état GL
            // est rendu à la 1.8.9 par GlFrameState189 (hub).
            "gl3"));

        // Resserré à la version exacte (avant : tout ce qui n'était pas
        // 1.8.x tombait implicitement ici) — une version 1.21.x non testée
        // ne doit PAS hériter silencieusement des Mixins vérifiés seulement
        // pour 1.21.11.
        //
        // DÉGELÉE le 2026-09-11 — deuxième tranche entièrement déclarative
        // après 26.1.2 : plus de config Mixin en ressource, la liste vit dans
        // MixinHookPointRegistry sous "1.21.11". Contrairement à 26.1.2, la
        // version est obfusquée : l'indice Yarn reste indispensable.
        PROFILES.add(new VersionProfile(
            "1_21_11",
            new String[]{ "1.21.11" },
            "1.21.11",
            "1.21.11",
            "blaze3d"));

        // 26.1.2 : MC N'EST PLUS OBFUSQUÉ à partir de la ligne
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
            "blaze3d"));
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
     * <p>Correspondance EXACTE d'abord, familles ({@code "1.8.*"}) seulement
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

    // ── Profil actif ───────────────────────────────────────────────────────

    private static volatile VersionProfile active;

    /**
     * Publie le profil résolu au bootstrap — appelé UNE fois par
     * {@code IsolatedBootstrap.start()}, jamais ailleurs.
     *
     * <p>Explicite plutôt qu'effet de bord de {@link #resolve} : « quelle
     * tranche correspond à cette version » et « quelle tranche tourne
     * réellement » sont deux questions différentes, et seule la seconde a une
     * réponse unique par lancement.
     */
    public static void setActive(VersionProfile profile) {
        active = profile;
        if (profile != null) System.setProperty(ACTIVE_PROFILE_PROPERTY, profile.key);
    }

    /**
     * Canal du profil actif ENTRE COPIES de cette classe.
     *
     * <p>BUG TROUVÉ (2026-09-13, « aucun profil actif publié » à CHAQUE
     * lancement, 26.1.2 comme 1.21.11) : sous Fabric, {@code IsolatedBootstrap}
     * tourne sur le classloader isolé de {@code LauncherAgent.startIsolated},
     * qui charge sa PROPRE copie de {@code launcher-agent.jar}. Il publiait le
     * profil dans le champ statique de CETTE copie ; le moteur graphique
     * ({@code RenderEra}), chargé par le classloader du jeu, lit l'AUTRE copie,
     * dont {@link #active} n'avait jamais été renseigné — d'où le repli par
     * déduction à chaque lancement. Même piège, et même remède, que les
     * mappings Yarn ({@code launcheragent.yarnPath}, voir {@code LauncherAgent}) :
     * une propriété système est le seul canal qui traverse toutes les copies.
     */
    private static final String ACTIVE_PROFILE_PROPERTY = "launcheragent.versionProfile";

    /**
     * Profil réellement actif, ou {@code null} si le bootstrap Mixin n'a pas
     * eu lieu. Dans une copie de cette classe autre que celle du bootstrap, le
     * profil est retrouvé par sa clé — voir {@link #ACTIVE_PROFILE_PROPERTY}.
     */
    public static VersionProfile active() {
        VersionProfile p = active;
        if (p != null) return p;
        String key = System.getProperty(ACTIVE_PROFILE_PROPERTY);
        if (key == null) return null;
        for (VersionProfile candidate : PROFILES) {
            if (candidate.key.equals(key)) {
                active = candidate;
                return candidate;
            }
        }
        com.yuyuframe.launcheragent.base.log.LauncherLog.err(
            "[VersionProfileRegistry] profil publié \"" + key + "\" absent de la table de cette copie");
        return null;
    }

    /**
     * Ère de rendu de la version qui tourne — {@code "gl3"},
     * {@code "blaze3d"}, ou {@code null}.
     *
     * <h2>Le repli, et pourquoi il est ICI</h2>
     *
     * Si aucun profil n'a été publié (bootstrap Mixin non passé, test hors
     * jeu), on retombe sur la DÉTECTION historique : présence de
     * {@code GpuDevice} → Blaze3D, sinon gl3 (seule ère OpenGL restante).
     *
     * <p>Ce repli lit la version et sonde des classes du jeu — deux choses
     * parfaitement légitimes dans {@code apimixin}, dont c'est le métier, et
     * interdites dans {@code apigraphic}, qui doit RECEVOIR son ère. Le mettre
     * ici est ce qui permet au moteur graphique de n'avoir aucune déduction du
     * tout, sans pour autant se retrouver aveugle si le bootstrap n'a pas eu
     * lieu.
     *
     * <p>Il se signale dans le log : un lancement en jeu où ce message
     * apparaît indique que le chemin normal (profil publié) n'a pas fonctionné.
     */
    public static String activeRenderEra() {
        VersionProfile p = active();
        if (p != null) return p.renderEra;

        String mcVersion = System.getProperty("launcheragent.mcVersion", "");
        String fallback = probeRenderEra(mcVersion);
        com.yuyuframe.launcheragent.base.log.LauncherLog.warn(
            "[VersionProfileRegistry] aucun profil actif publié — ère de rendu DÉDUITE (\""
            + fallback + "\") pour la version \"" + mcVersion + "\". Chemin de repli : "
            + "en jeu, ce message ne devrait jamais apparaître.");
        return fallback;
    }

    /** Détection historique, conservée uniquement comme repli — voir {@link #activeRenderEra()}. */
    private static String probeRenderEra(String mcVersion) {
        return classPresent("com.mojang.blaze3d.systems.GpuDevice") ? "blaze3d" : "gl3";
    }

    /**
     * Présence d'une classe non obfusquée, sans l'initialiser : classloader de
     * contexte d'abord (celui du jeu), puis celui de cette classe.
     */
    private static boolean classPresent(String binaryName) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            try {
                Class.forName(binaryName, false, ctx);
                return true;
            } catch (Throwable ignored) {
                // repli ci-dessous
            }
        }
        try {
            Class.forName(binaryName, false, VersionProfileRegistry.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
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
