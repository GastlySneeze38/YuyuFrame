package com.yuyuframe.launcheragent.apimixin.version;

/**
 * Ce qu'il reste à savoir d'une version Minecraft UNE FOIS que le dispatch des
 * mixins est parti dans {@link com.yuyuframe.launcheragent.apimixin.MixinHookPointRegistry}
 * — remplace l'ancien {@code VersionBracket} (2026-09-09).
 *
 * <h2>Ce qui a disparu et où c'est parti</h2>
 * <ul>
 *   <li>{@code apiMixinConfigResource} — le JSON apimixin n'existe plus en
 *       ressource : il est GÉNÉRÉ depuis la table déclarative
 *       ({@code IsolatedBootstrap.buildApiMixinConfig()}), à partir du
 *       template partagé {@code mixins.launcheragent-apimixin.template.json} ;</li>
 *   <li>{@code matcher} ({@code Predicate<String>}) — remplacé par {@link
 *       #versions}, des LITTÉRAUX. Un prédicat par version était du code
 *       dupliqué N fois pour exprimer « égalité stricte » ; la règle de
 *       correspondance vit maintenant une seule fois dans
 *       {@link VersionProfileRegistry#resolve} ;</li>
 *   <li>{@code enabled}/{@code disabled()} — devenu {@link #frozen}, même
 *       comportement et même raison d'être (garder la déclaration et ses
 *       commentaires sans que la tranche ne soit tissée).</li>
 * </ul>
 *
 * Ne reste donc ici que ce qui se décide AVANT tout chargement Mixin et qui
 * n'a aucun rapport avec les points d'accroche : quels mappings charger, et
 * quelle ère de rendu sert la version.
 *
 * <p>Plus de config Mixin héritée (2026-09-16) : le package {@code mixin/} et
 * ses JSON par version ont été supprimés avec l'abandon des versions autres
 * que 1.8.9 et 1.21.11+. Toutes les tranches sont déclaratives.
 */
public final class VersionProfile {

    /** Identifiant lisible de la tranche (journalisation, messages d'erreur). */
    public final String key;

    /**
     * Versions MC couvertes, en littéraux — {@code "26.1.2"} pour une version
     * exacte, {@code "1.16.*"} pour toute une famille. Volontairement des
     * DONNÉES et pas un prédicat : voir la javadoc de classe.
     *
     * <p>Par défaut on reste sur des versions EXACTES. L'historique du projet
     * est clair là-dessus : une version qui tombe dans la mauvaise tranche
     * échoue SILENCIEUSEMENT (Mixin logue un WARN et rejette la classe), donc
     * élargir à une famille est un choix à faire version testée par version
     * testée, jamais par confiance dans un raisonnement.
     */
    public final String[] versions;

    /**
     * Version utilisée comme CLÉ dans la table déclarative
     * ({@code MixinHookPointRegistry}). Distincte de la version détectée au
     * runtime dès qu'un profil couvre une famille : plusieurs versions MC
     * partagent alors le même jeu de mixins.
     */
    public final String hookTableVersion;

    /** Fragment de nom recherché dans les jars Yarn du disque ({@code null} si la version n'est pas obfusquée). */
    public final String yarnJarNameHint;

    /**
     * Ère de rendu de cette version — {@code "gl3"},
     * {@code "blaze3d"}, ou {@code null} si aucun backend graphique ne la sert.
     *
     * <h2>Pourquoi une CHAÎNE et pas un type</h2>
     *
     * C'est la seule information de ce fichier destinée à {@code apigraphic}.
     * Or la règle de couches va {@code apigraphic → apimixin} : un type défini
     * dans le moteur graphique et référencé ICI créerait un cycle. La chaîne
     * est le vocabulaire de frontière ; le moteur la traduit dans SON
     * énumération ({@code apigraphic.backend.RenderEra}).
     *
     * <p>C'est aussi ce qui rend la règle vraie dans le bon sens : le moteur
     * ne DÉDUIT plus son ère d'une version (il ne lit plus
     * {@code launcheragent.mcVersion}, ne sonde plus la présence de
     * {@code GpuDevice}), il la REÇOIT de la couche dont c'est le métier.
     */
    public final String renderEra;

    /**
     * Vrai = tranche DÉCLARÉE mais pas active : {@link VersionProfileRegistry#resolve}
     * l'ignore, donc le bootstrap Mixin s'arrête proprement sur cette version
     * (message clair, agent qui continue de tourner, aucun mixin appliqué).
     *
     * <p>Existe pour geler les tranches en attente de rework sans supprimer
     * leur déclaration — celle-ci porte les versions couvertes, l'indice de jar
     * Yarn et surtout les commentaires expliquant POURQUOI chaque tranche est
     * resserrée. Les commenter ferait perdre tout ça ; un booléen les garde
     * relisibles et les réactive en un mot.
     */
    public final boolean frozen;

    public VersionProfile(String key, String[] versions, String hookTableVersion,
                          String yarnJarNameHint, String renderEra) {
        this(key, versions, hookTableVersion, yarnJarNameHint, renderEra, false);
    }

    public VersionProfile(String key, String[] versions, String hookTableVersion,
                          String yarnJarNameHint, String renderEra,
                          boolean frozen) {
        this.key = key;
        this.versions = versions;
        this.hookTableVersion = hookTableVersion;
        this.yarnJarNameHint = yarnJarNameHint;
        this.renderEra = renderEra;
        this.frozen = frozen;
    }

    /** Même tranche, gelée — voir {@link #frozen}. Fluent, pour que la déclaration reste lisible telle quelle. */
    public VersionProfile frozen() {
        return new VersionProfile(key, versions, hookTableVersion, yarnJarNameHint,
            renderEra, true);
    }

    /**
     * Cette version MC est-elle couverte ? Égalité stricte, ou préfixe si
     * l'entrée se termine par {@code ".*"} — les deux seules formes admises,
     * pour que {@link #versions} reste des données lisibles telles quelles.
     */
    public boolean covers(String mcVersion) {
        if (mcVersion == null) return false;
        for (String v : versions) {
            if (v == null) continue;
            if (v.endsWith(".*")) {
                // ".*" retiré EN ENTIER : "1.16.*" couvre "1.16" comme
                // "1.16.5" — reproduit exactement le startsWith("1.16") de
                // l'ancien matcher, où "1.16" tout court est une vraie version.
                if (mcVersion.startsWith(v.substring(0, v.length() - 2))) return true;
            } else if (v.equals(mcVersion)) {
                return true;
            }
        }
        return false;
    }
}
