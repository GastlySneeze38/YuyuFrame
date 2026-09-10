package com.yuyuframe.launcheragent.apimixin.mapping;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mappings SRG (Forge / NeoForge) au format TSRG et TSRG2 — <b>jumeau strict
 * de l'index « intermediary » de {@link YarnMappings}</b>.
 *
 * <h2>Pourquoi un troisième schéma</h2>
 *
 * Le registre ne connaissait que deux façons de nommer le jeu à l'exécution :
 * {@code OFFICIAL} (les noms obfusqués bruts de Mojang, ex. {@code gfj}) en
 * vanilla, et {@code INTERMEDIARY} (ex. {@code class_310}) sous Fabric/Quilt.
 * Forge 1.13+ et NeoForge s'isolent correctement dans notre bootstrap mais
 * tournent en <b>SRG</b>, un troisième nommage — ils retombaient donc sur
 * {@code OFFICIAL}, c'est-à-dire sur des noms qui n'existent pas dans le jar
 * réellement chargé. Aucune exception, aucun message : simplement plus rien
 * qui résout.
 *
 * <h2>Ce qu'est SRG, concrètement</h2>
 *
 * Contrairement à intermediary, SRG nomme les <b>classes de façon lisible</b>
 * et garde des identifiants numérotés pour les <b>membres</b> :
 *
 * <pre>
 *   official  gfj            a()             r
 *   srg       net/minecraft/client/Minecraft m_91087_   f_90981_
 * </pre>
 *
 * Deux générations coexistent selon la version de MC :
 * <ul>
 *   <li>≤ 1.16 : {@code func_71410_x} / {@code field_71425_J} (TSRG v1) ;</li>
 *   <li>1.17+ : {@code m_91087_} / {@code f_90981_} (TSRG2).</li>
 * </ul>
 * Les deux formats sont lus ici — la différence est de syntaxe, pas de sens.
 *
 * <h2>Le pivot reste « official »</h2>
 *
 * Tout le code de réflexion du projet écrit des noms officiels en dur, et
 * {@link YarnMappings} indexe déjà {@code official → intermediary} en direct.
 * Cet index fait exactement la même chose vers SRG, avec les <b>mêmes formes
 * de clés</b> ({@code classe\0nom\0descripteur}, descripteur en termes
 * officiels) et les mêmes noms de méthodes d'accès au préfixe près. C'est
 * volontaire : {@link MappingsRegistry} peut alors aiguiller entre les deux
 * sans que la forme de son code change.
 *
 * <h2>⚠️ NeoForge n'est pas forcément du SRG</h2>
 *
 * NeoForge a basculé son exécution sur les noms Mojang officiels lisibles
 * (« Mojmap ») à partir de la 1.20.2 — ses membres s'appellent alors
 * {@code getInstance}, pas {@code m_91087_}. Ce point <b>n'a pas pu être
 * vérifié depuis cet environnement</b> (aucune instance NeoForge sous la
 * main). Le repli est donc explicite plutôt que deviné : voir
 * {@code LauncherAgent.resolveSchemeName} et la propriété
 * {@code -Dlauncheragent.mappingScheme=...} qui permet de forcer le schéma
 * sans rebuild si le diagnostic en jeu contredit ce choix.
 *
 * <h2>Portée réelle aujourd'hui</h2>
 *
 * SRG ne sert QUE sur une version obfusquée. La 26.1.x ne l'est pas (Mojang
 * a cessé de publier des mappings d'obfuscation), donc tous les loaders y
 * convergent sur les vrais noms et ce fichier n'y change rien. Toutes les
 * tranches obfusquées étant gelées à l'heure de l'écriture, c'est du
 * terrassement : correct à poser maintenant, mais sans effet observable tant
 * qu'une tranche obfusquée n'est pas dégelée.
 */
public final class SrgMappings {

    private SrgMappings() {}

    private static volatile boolean loaded = false;

    /** official → srg, pour les classes (le srg est le nom LISIBLE). */
    private static Map<String, String> classOfficialToSrg = Collections.emptyMap();
    /** srg → official, pour le chemin retour (runtime → named). */
    private static Map<String, String> classSrgToOfficial = Collections.emptyMap();
    /** classeOff\0nomOff\0descOff → nom srg. */
    private static Map<String, String> methodOfficialToSrg = Collections.emptyMap();
    /** classeOff\0nomOff → {noms srg} — plusieurs si le nom officiel est surchargé. */
    private static Map<String, Set<String>> methodOfficialNameToSrgNames = Collections.emptyMap();
    /** classeOff\0nomOff → nom srg. */
    private static Map<String, String> fieldOfficialToSrg = Collections.emptyMap();

    public static boolean isLoaded() { return loaded; }

    /**
     * Lit un {@code joined.tsrg} (TSRG v1 ou TSRG2).
     *
     * <h3>Les deux formats, et ce qui les distingue à la lecture</h3>
     *
     * <b>TSRG v1</b> — aucun en-tête, deux espaces de noms implicites :
     * <pre>
     *   gfj net/minecraft/client/Minecraft
     *   \tr field_71425_J
     *   \ta ()V func_71410_x
     * </pre>
     *
     * <b>TSRG2</b> — en-tête {@code tsrg2 obf srg [id]}, puis la même
     * structure, plus des lignes à DEUX tabulations portant des métadonnées
     * (marqueur {@code static}, noms de paramètres) :
     * <pre>
     *   tsrg2 obf srg
     *   gfj net/minecraft/client/Minecraft
     *   \tr f_90981_
     *   \ta ()V m_91087_
     *   \t\tstatic
     * </pre>
     *
     * La distinction membre/métadonnée se fait sur le NOMBRE de tabulations,
     * pas sur le contenu : une ligne à deux tabulations est ignorée en bloc.
     * C'est le seul critère fiable — un nom de paramètre peut ressembler à
     * n'importe quoi.
     *
     * <p>Champ ou méthode se distinguent par le nombre de colonnes : deux pour
     * un champ ({@code obf srg}), trois pour une méthode ({@code obf desc
     * srg}). Le descripteur est écrit en termes OFFICIELS, exactement comme
     * les clés de l'index intermediary — d'où la correspondance directe, sans
     * traduction, avec les descripteurs que le code appelant passe déjà.
     */
    public static synchronized void load(InputStream is) throws IOException {
        Map<String, String> classOffToSrg = new HashMap<>(8192);
        Map<String, String> classSrgToOff = new HashMap<>(8192);
        Map<String, String> methodOffToSrg = new HashMap<>(65536);
        Map<String, Set<String>> methodOffNameToSrg = new HashMap<>(65536);
        Map<String, String> fieldOffToSrg = new HashMap<>(32768);

        int skippedMalformed = 0;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            boolean firstLine = true;
            String currentOfficialClass = null;

            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;

                if (firstLine) {
                    firstLine = false;
                    // En-tête TSRG2 : consommé et ignoré. Les espaces de noms
                    // sont TOUJOURS pris dans l'ordre (0 = official, 1 = srg) ;
                    // les colonnes suivantes éventuelles ("id") ne nous servent
                    // à rien. Pas de détection par nom d'espace comme le fait
                    // YarnMappings pour tiny v2 : TSRG2 ne garantit pas des
                    // libellés stables, seul l'ordre l'est.
                    if (line.startsWith("tsrg2")) continue;
                    // TSRG v1 : pas d'en-tête, cette ligne est déjà une donnée.
                }

                int tabs = 0;
                while (tabs < line.length() && line.charAt(tabs) == '\t') tabs++;

                // Métadonnées TSRG2 (static, noms de paramètres) — jamais un
                // membre, quel que soit leur contenu.
                if (tabs >= 2) continue;

                String[] p = line.substring(tabs).trim().split("\\s+");

                if (tabs == 0) {
                    // Ligne de classe. Une ligne mal formée invalide la classe
                    // COURANTE plutôt que de laisser les membres suivants
                    // s'attacher silencieusement à la classe précédente — un
                    // rattachement erroné produirait des mappings faux, bien
                    // pire qu'une absence de mapping.
                    currentOfficialClass = null;
                    if (p.length >= 2) {
                        currentOfficialClass = p[0];
                        classOffToSrg.put(p[0], p[1]);
                        classSrgToOff.put(p[1], p[0]);
                    } else {
                        skippedMalformed++;
                    }
                    continue;
                }

                if (currentOfficialClass == null) continue;

                if (p.length == 2) {
                    // Champ : obf srg
                    fieldOffToSrg.put(currentOfficialClass + "\0" + p[0], p[1]);
                } else if (p.length >= 3) {
                    // Méthode : obf desc srg
                    String officialName = p[0];
                    String desc = p[1];
                    String srgName = p[2];
                    String offKey = currentOfficialClass + "\0" + officialName;
                    methodOffToSrg.put(offKey + "\0" + desc, srgName);
                    methodOffNameToSrg.computeIfAbsent(offKey, k -> new HashSet<>()).add(srgName);
                } else {
                    skippedMalformed++;
                }
            }
        }

        classOfficialToSrg = classOffToSrg;
        classSrgToOfficial = classSrgToOff;
        methodOfficialToSrg = methodOffToSrg;
        methodOfficialNameToSrgNames = methodOffNameToSrg;
        fieldOfficialToSrg = fieldOffToSrg;

        loaded = true;
        LauncherLog.agent(3, "[SRG] Chargé : " + classOffToSrg.size() + " classes, "
            + methodOffToSrg.size() + " méthodes, " + fieldOffToSrg.size() + " champs"
            + (skippedMalformed > 0 ? " (" + skippedMalformed + " ligne(s) ignorée(s))" : ""));
    }

    /**
     * Lit le {@code joined.tsrg} contenu dans un zip MCPConfig
     * ({@code mcp_config-<version>.zip}), tel que déposé par Forge/NeoForge
     * dans {@code libraries/de/oceanlabs/mcp/mcp_config/}.
     *
     * <p>Deux emplacements sont tentés : celui de MCPConfig
     * ({@code config/joined.tsrg}) et la racine, parce qu'un fichier extrait
     * puis rezippé à la main — ce que fait un utilisateur qui dépanne son
     * installation — perd le préfixe de dossier.
     */
    public static synchronized void loadFromZip(String zipPath) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipPath)) {
            java.util.zip.ZipEntry entry = zip.getEntry("config/joined.tsrg");
            if (entry == null) entry = zip.getEntry("joined.tsrg");
            if (entry == null) {
                throw new IOException("config/joined.tsrg introuvable dans " + zipPath);
            }
            load(zip.getInputStream(entry));
        }
    }

    // ── SRG (Forge/NeoForge) — mêmes formes de clés que l'index intermediary ──

    public static String getSrgClass(String officialClass) {
        if (!loaded) return null;
        return classOfficialToSrg.get(officialClass);
    }

    /** Chemin retour : nom srg tel que vu à l'exécution → nom officiel. */
    public static String getOfficialClassFromSrg(String srgClass) {
        if (!loaded) return null;
        return classSrgToOfficial.get(srgClass);
    }

    /** Nom srg exact pour un (classe officielle, nom officiel, descripteur officiel). */
    public static String getSrgMethod(String officialClass, String officialMethod, String officialDesc) {
        if (!loaded) return null;
        return methodOfficialToSrg.get(officialClass + "\0" + officialMethod + "\0" + officialDesc);
    }

    /**
     * Tous les noms srg possibles pour (classe officielle, nom officiel), sans
     * descripteur — même usage que son équivalent intermediary : l'appelant
     * filtre déjà par signature après le nom. Peut contenir plusieurs entrées
     * si le nom officiel est surchargé.
     */
    public static Set<String> getSrgMethodNames(String officialClass, String officialMethod) {
        if (!loaded) return Collections.emptySet();
        Set<String> s = methodOfficialNameToSrgNames.get(officialClass + "\0" + officialMethod);
        return s != null ? s : Collections.emptySet();
    }

    public static String getSrgField(String officialClass, String officialField) {
        if (!loaded) return null;
        return fieldOfficialToSrg.get(officialClass + "\0" + officialField);
    }
}
