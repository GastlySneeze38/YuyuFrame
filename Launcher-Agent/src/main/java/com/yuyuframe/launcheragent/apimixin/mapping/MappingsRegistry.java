package com.yuyuframe.launcheragent.apimixin.mapping;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.extensibility.IRemapper;

import java.util.Set;

/**
 * Registre de mappings fondé sur Yarn tiny v2 — copie indépendante de celle
 * du p2p-agent (voir [[YarnMappings]] pour la raison de la duplication).
 *
 * IRemapper pour Mixin :
 *   map(yarnNamed)       → classe runtime  (résolution de @Mixin(targets = "..."))
 *   unmap(classe runtime) → yarnNamed      (pour getClassNode())
 *
 * Scheme : le jeu ne porte pas les mêmes noms à l'exécution selon le loader.
 * {@link #setScheme} doit être appelé une fois avant tout usage — voir
 * IsolatedBootstrap. Tout le reste de ce registre (et donc tout code qui passe
 * par lui : Mixin, ScreenHelper, IconWidgets) devient alors automatiquement
 * cohérent avec le schéma actif.
 *
 * <h2>Les trois schémas</h2>
 *
 * <table border="1">
 *   <tr><th>Schéma</th><th>Loader</th><th>Classes</th><th>Membres</th></tr>
 *   <tr><td>{@code OFFICIAL}</td><td>vanilla</td><td>{@code gfj}</td><td>{@code a}</td></tr>
 *   <tr><td>{@code INTERMEDIARY}</td><td>Fabric, Quilt</td><td>{@code class_310}</td><td>{@code method_25426}</td></tr>
 *   <tr><td>{@code SRG}</td><td>Forge 1.13+, NeoForge</td><td>{@code net/minecraft/client/Minecraft}</td><td>{@code m_91087_}</td></tr>
 * </table>
 *
 * <p><b>Le pivot est toujours « official »</b> — les noms obfusqués bruts de
 * Mojang. C'est ce que le code de réflexion du projet écrit en dur, et c'est
 * la clé des deux index de traduction ({@link YarnMappings} pour intermediary,
 * {@link SrgMappings} pour srg). Ajouter un schéma revient donc à ajouter un
 * index vers ce pivot, plus une branche dans les CINQ méthodes d'aiguillage
 * privées ci-dessous — et nulle part ailleurs : les méthodes publiques
 * n'aiguillent pas, elles délèguent.
 *
 * <p>Cette factorisation date de l'ajout de {@code SRG} (2026-09-10). Avant,
 * chaque méthode publique portait son propre {@code if (scheme ==
 * INTERMEDIARY)} — quatorze en tout. Ajouter un troisième schéma aurait voulu
 * dire quatorze endroits à modifier ensemble, dont l'oubli d'un seul aurait
 * produit une traduction partielle, c'est-à-dire des noms qui résolvent à
 * moitié : le pire mode de défaillance possible ici, silencieux et localisé.
 */
public final class MappingsRegistry implements IRemapper {

    public static final MappingsRegistry INSTANCE = new MappingsRegistry();
    private MappingsRegistry() {}

    public enum Scheme { OFFICIAL, INTERMEDIARY, SRG }

    private static volatile Scheme scheme = Scheme.OFFICIAL;

    public static void setScheme(Scheme s) { scheme = s; }
    public static Scheme getScheme() { return scheme; }

    /**
     * Le pivot Yarn est-il chargé ? C'est lui qui porte {@code named →
     * official}, indispensable dans TOUS les schémas — y compris SRG, dont la
     * chaîne complète est {@code named → official → srg} (l'index SRG ne
     * connaît que sa seconde moitié).
     */
    public static boolean isLoaded() {
        // BUG TROUVÉ (2026-09-13, icônes 1.21.11) : ce test ne déclenchait PAS
        // le chargement paresseux. Or huit traducteurs nom→officiel
        // (getObfFieldName, getObfMethodName, namedToRuntimeMethod…) commencent
        // par « if (!isLoaded()) return nomYarn; » — sur la copie Knot de cette
        // classe, si l'un d'eux était le PREMIER appel de la session, il rendait
        // le nom Yarn non traduit sans jamais charger les mappings (seule
        // l'étape officiel→runtime appelait ensureInitialized, trop tard).
        // Vu en jeu : « champ state introuvable sur class_11228 » à 08:57:47,
        // et « [Yarn] Chargé » à la ligne suivante, déclenché par un autre
        // appelant. Un point unique : poser la question « est-ce chargé ? »
        // charge si ce n'est pas encore tenté. Sans coût ensuite.
        ensureInitialized();
        return YarnMappings.isLoaded();
    }

    private static volatile boolean autoInitAttempted = false;

    /**
     * Sous Fabric, le code tissé par Mixin (les corps de méthode @Inject) est
     * résolu par KnotClassLoader — qui charge
     * "com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry" comme une
     * classe SÉPARÉE de celle utilisée par IsolatedBootstrap (chargée, elle,
     * via le classloader isolé dédié à Mixin/ASM). Deux classloaders
     * différents chargeant "la même" classe produisent deux objets Class
     * indépendants, donc deux états statiques indépendants : cette copie n'a
     * jamais reçu setScheme()/YarnMappings.load() — d'où "scheme" resté à
     * OFFICIAL (sa valeur par défaut) malgré le fait que le bootstrap a bien
     * configuré l'AUTRE copie. Seule une System property (table globale,
     * indépendante des classloaders) traverse cette frontière — voir
     * LauncherAgent.premain(). Appelé paresseusement par toutes les méthodes
     * de traduction ci-dessous : sans coût une fois isLoaded()==true.
     */
    private static void ensureInitialized() {
        if (YarnMappings.isLoaded() || autoInitAttempted) return;
        autoInitAttempted = true;
        try {
            scheme = schemeFromProperties();

            // L'index SRG doit être rechargé de ce côté de la frontière pour
            // la même raison que le pivot Yarn juste après : cette copie de
            // classe a son propre état statique, vide. Sans ça, tout code
            // tissé (donc résolu par Knot/ModLauncher) verrait scheme==SRG
            // mais un SrgMappings vide — chaque traduction retomberait
            // silencieusement sur le nom officiel, c'est-à-dire sur le seul
            // nom qui n'existe PAS à l'exécution sous Forge.
            String srgPath = System.getProperty("launcheragent.srgPath");
            if (scheme == Scheme.SRG && srgPath != null && !srgPath.isEmpty()
                    && !SrgMappings.isLoaded()) {
                loadSrg(srgPath);
            }

            String yarnPath = System.getProperty("launcheragent.yarnPath");
            if (yarnPath == null || yarnPath.isEmpty()) return;
            if (yarnPath.endsWith(".jar") || yarnPath.endsWith(".zip")) {
                YarnMappings.loadFromJar(yarnPath);
            } else {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(yarnPath)) {
                    YarnMappings.load(fis);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Schéma déduit des System properties — la seule table qui traverse la
     * frontière de classloader décrite ci-dessus.
     *
     * <p>{@code launcheragent.mappingScheme} est la source explicite, posée
     * par {@code LauncherAgent.premain()}. {@code launcheragent.intermediary}
     * (booléen, antérieur) est conservé comme repli : il reste écrit par les
     * builds du launcher Rust qui ne connaissent pas encore la nouvelle
     * propriété, et le retirer ferait retomber Fabric en {@code OFFICIAL} —
     * une régression silencieuse sur le seul loader réellement testé.
     */
    public static Scheme schemeFromProperties() {
        String explicit = System.getProperty("launcheragent.mappingScheme");
        if (explicit != null && !explicit.isEmpty()) {
            try {
                return Scheme.valueOf(explicit.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                LauncherLog.warn("[Mappings] launcheragent.mappingScheme=\"" + explicit
                    + "\" inconnu — valeurs admises : official, intermediary, srg. Repli sur la détection héritée.");
            }
        }
        // "launcheragent.intermediary" (ex "launcheragent.fabric", renommé
        // P0-3 — voir docs/launcher/audit/README-bugs-a-fix.md) : découplé
        // de la décision d'isolation classloader, Quilt inclus désormais
        // (Fabric ET Quilt tournent en mappings intermediary).
        return "true".equals(System.getProperty("launcheragent.intermediary"))
            ? Scheme.INTERMEDIARY : Scheme.OFFICIAL;
    }

    /** Charge l'index SRG depuis un zip MCPConfig ou un {@code joined.tsrg} brut. */
    public static void loadSrg(String path) throws java.io.IOException {
        if (path.endsWith(".zip") || path.endsWith(".jar")) {
            SrgMappings.loadFromZip(path);
        } else {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(path)) {
                SrgMappings.load(fis);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── AIGUILLAGE PAR SCHÉMA — les cinq seules méthodes qui testent
    //    "scheme". Tout le reste du fichier délègue ici.
    //
    // Chacune suit la même règle : tenter la traduction propre au schéma
    // actif, et retomber sur l'entrée OFFICIELLE si l'index ne connaît pas
    // cette entrée. Ce repli n'est pas une commodité — en OFFICIAL il EST la
    // réponse juste, et dans les deux autres il vaut mieux qu'une exception :
    // une entrée manquante isolée (classe interne récente, synthétique) ne
    // doit pas faire tomber tout le chemin de résolution.
    // ══════════════════════════════════════════════════════════════════════

    private static String officialToRuntimeClass(String officialClass) {
        ensureInitialized();
        if (scheme == Scheme.INTERMEDIARY) {
            String inter = YarnMappings.getIntermediaryClass(officialClass);
            if (inter != null) return inter;
        } else if (scheme == Scheme.SRG) {
            String srg = SrgMappings.getSrgClass(officialClass);
            if (srg != null) return srg;
        }
        return officialClass;
    }

    private static String officialToRuntimeField(String officialClass, String officialField) {
        ensureInitialized();
        if (scheme == Scheme.INTERMEDIARY && isLoaded()) {
            String inter = YarnMappings.getIntermediaryField(officialClass, officialField);
            if (inter != null) return inter;
        } else if (scheme == Scheme.SRG) {
            String srg = SrgMappings.getSrgField(officialClass, officialField);
            if (srg != null) return srg;
        }
        return officialField;
    }

    private static String officialToRuntimeMethod(String officialClass, String officialMethod,
                                                   String officialDesc) {
        ensureInitialized();
        if (scheme == Scheme.INTERMEDIARY && isLoaded()) {
            String inter = YarnMappings.getIntermediaryMethod(officialClass, officialMethod, officialDesc);
            if (inter != null) return inter;
        } else if (scheme == Scheme.SRG) {
            String srg = SrgMappings.getSrgMethod(officialClass, officialMethod, officialDesc);
            if (srg != null) return srg;
        }
        return officialMethod;
    }

    private static Set<String> officialToRuntimeMethodNames(String officialClass, String officialMethod) {
        ensureInitialized();
        if (scheme == Scheme.INTERMEDIARY && isLoaded()) {
            Set<String> names = YarnMappings.getIntermediaryMethodNames(officialClass, officialMethod);
            if (!names.isEmpty()) return names;
        } else if (scheme == Scheme.SRG) {
            Set<String> names = SrgMappings.getSrgMethodNames(officialClass, officialMethod);
            if (!names.isEmpty()) return names;
        }
        return java.util.Collections.singleton(officialMethod);
    }

    /**
     * Chemin RETOUR : nom de classe tel que vu à l'exécution → nom officiel.
     *
     * <p>Sert au dé-mapping ({@code unmap}, {@code runtimeToNamed}), là où on
     * part d'une classe réellement chargée pour remonter vers le vocabulaire
     * Yarn. En {@code OFFICIAL} c'est l'identité.
     */
    private static String runtimeToOfficialClass(String runtimeClass) {
        if (scheme == Scheme.SRG) {
            String official = SrgMappings.getOfficialClassFromSrg(runtimeClass);
            if (official != null) return official;
        }
        return runtimeClass;
    }

    /**
     * Traduit un descripteur de méthode/constructeur ÉCRIT EN OFFICIAL (ex:
     * "(Lgsb;Lgfo;)V") vers sa forme runtime (slash, comme stocké côté
     * intermediary) — nécessaire pour construire la valeur d'une entrée de
     * refmap visant un constructeur ou toute méthode dont le descripteur
     * référence des types d'objets : le nom "<init>" ne change jamais, mais
     * les TYPES dans le descripteur si, sous Fabric. Contrairement à
     * {@link #mapDesc} (IRemapper, attend du "named"), celui-ci attend
     * directement de l'official — pas de détour par YarnMappings.getOfficialClass().
     */
    public static String runtimeDesc(String officialDesc) {
        StringBuilder sb = new StringBuilder(officialDesc.length());
        int i = 0;
        while (i < officialDesc.length()) {
            char c = officialDesc.charAt(i++);
            if (c == 'L') {
                int semi = officialDesc.indexOf(';', i);
                if (semi < 0) { sb.append('L').append(officialDesc.substring(i)); break; }
                String cls = officialDesc.substring(i, semi);
                sb.append('L').append(officialToRuntimeClass(cls)).append(';');
                i = semi + 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Champ : nom officiel littéral écrit en dur dans le code de réflexion → nom runtime. */
    public static String runtimeField(String officialClass, String officialField) {
        return officialToRuntimeField(officialClass, officialField);
    }

    /** Méthode (avec descripteur officiel exact) : nom officiel → nom runtime. */
    public static String runtimeMethod(String officialClass, String officialMethod, String officialDesc) {
        return officialToRuntimeMethod(officialClass, officialMethod, officialDesc);
    }

    /**
     * Méthode sans descripteur connu à l'avance — renvoie tous les noms runtime
     * possibles (généralement 1, plusieurs si le nom officiel est surchargé).
     * À utiliser quand l'appelant filtre déjà par signature après le nom (cas
     * fréquent dans ScreenHelper) : remplacer {@code "x".equals(m.getName())}
     * par {@code runtimeMethodNames("Class","x").contains(m.getName())}.
     */
    public static Set<String> runtimeMethodNames(String officialClass, String officialMethod) {
        return officialToRuntimeMethodNames(officialClass, officialMethod);
    }

    /**
     * Classe : nom officiel littéral (ex: "gjc") → nom runtime, format à POINTS
     * (binary name) — tous les appelants (ScreenHelper, IconWidgets) l'utilisent
     * directement avec Class.forName()/getName(), qui exigent des points, pas
     * des slashes. officialToRuntimeClass() (interne, utilisé par map() pour
     * Mixin/ASM) renvoie lui du slash form — converti ici uniquement pour ce
     * point d'entrée public.
     */
    public static String runtimeClass(String officialClass) {
        return officialToRuntimeClass(officialClass).replace('/', '.');
    }

    // ── Named (Yarn) ↔ runtime — utilisé par discoverMixinTargets/ScreenStubPatcher ──

    /**
     * named (Yarn/Mojmap) → "official" (obfusqué brut Mojang), jamais le
     * schéma actif.
     *
     * ⚠️ CORRECTION 2026-08-25 (voir [[project_mc_261_port]] §12) — la
     * version précédente de ce commentaire affirmait que le jar Minecraft
     * sur le classpath contient TOUJOURS les classes sous leur nom
     * "official", même sous Fabric, et que les noms "intermediary"
     * n'existeraient qu'en mémoire. **C'EST FAUX.** Sous Fabric/Quilt, le jar
     * réellement sur le classpath de Knot est
     * {@code .fabric/remappedJars/<mc>-<loader>/client-intermediary.jar}, et
     * ses entrées sont bel et bien nommées {@code net/minecraft/class_1297.class}
     * — vérifié par listing du zip. Le nom official n'y existe pas.
     *
     * Conséquence : une lecture-disque qui n'essaie QUE le nom official
     * échoue systématiquement sous Fabric. {@code
     * LauncherMixinService.getClassNode()} essaie donc les deux (official
     * PUIS intermediary via {@link #getObfClassDot}) — ne pas retirer le
     * second essai, il est la seule voie fonctionnelle sous Fabric.
     */
    public static String getOfficialClassAlways(String yarnClass) {
        String obf = YarnMappings.getOfficialClass(yarnClass);
        return obf != null ? obf : yarnClass;
    }

    public static String getObfClassDot(String yarnClass) {
        String obf = YarnMappings.getOfficialClass(yarnClass);
        if (obf == null) return yarnClass.replace('/', '.');
        return officialToRuntimeClass(obf).replace('/', '.');
    }

    public static Class<?> loadClass(String yarnClass) throws ClassNotFoundException {
        String name = getObfClassDot(yarnClass);
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            try { return Class.forName(name, false, ctx); } catch (ClassNotFoundException ignored) {}
        }
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            String fallback = yarnClass.replace('/', '.');
            if (!fallback.equals(name)) {
                if (ctx != null) {
                    try { return Class.forName(fallback, false, ctx); } catch (ClassNotFoundException ignored) {}
                }
                return Class.forName(fallback);
            }
            throw e;
        }
    }

    public static boolean isInstance(Object obj, String yarnClass) {
        if (obj == null) return false;
        try {
            return loadClass(yarnClass).isInstance(obj);
        } catch (ClassNotFoundException e) {
            String simpleName = yarnClass.substring(yarnClass.lastIndexOf('/') + 1);
            return obj.getClass().getName().contains(simpleName);
        }
    }

    public static String getObfMethodName(String yarnClass, String yarnMethod) {
        if (!isLoaded()) return yarnMethod;
        YarnMappings.MethodEntry entry = YarnMappings.getOfficialMethod(yarnClass, yarnMethod);
        if (entry == null) return yarnMethod;
        String officialOwner = YarnMappings.getOfficialClass(yarnClass);
        if (officialOwner == null) return entry.officialName;
        return officialToRuntimeMethod(officialOwner, entry.officialName, entry.officialDesc);
    }

    /**
     * Variante par NOM + DESCRIPTEUR OFFICIEL — indispensable dès qu'une
     * classe a plusieurs méthodes du même nom Yarn (surcharges) — la
     * résolution par nom seul ci-dessus retombe alors sur UNE des surcharges,
     * potentiellement la mauvaise. Cas concret trouvé (1.16.5, historique de
     * session) : {@code MinecraftClient} a DEUX méthodes nommées "setScreen"
     * (une prenant {@code Screen}, une prenant un autre type) — la résolution
     * par nom seul est ambiguë ; pire, une résolution par FORME côté appelant
     * ({@code (Screen):void}) retombe AUSSI sur {@code disconnect(Screen)}
     * (même forme exacte, méthode complètement différente) selon l'ordre non
     * garanti de {@code Class.getMethods()} — non déterministe d'une
     * exécution JVM à l'autre. Le descripteur OFFICIEL (types déjà
     * obfusqués, ex: {@code "(Ldot;)V"}) lève l'ambiguïté des DEUX côtés à la
     * fois : plus besoin de deviner par forme après coup.
     *
     * @param officialDesc descripteur JVM utilisant les noms de classes DÉJÀ
     *                      obfusqués (ex: obtenu via {@code
     *                      nativeClass.getName().replace('.','/')}), PAS les
     *                      noms Yarn named.
     */
    public static String getObfMethodName(String yarnClass, String yarnMethod, String officialDesc) {
        if (!isLoaded()) return yarnMethod;
        YarnMappings.MethodEntry entry = YarnMappings.getOfficialMethod(yarnClass, yarnMethod, officialDesc);
        if (entry == null) return yarnMethod;
        String officialOwner = YarnMappings.getOfficialClass(yarnClass);
        if (officialOwner == null) return entry.officialName;
        return officialToRuntimeMethod(officialOwner, entry.officialName, entry.officialDesc);
    }

    /**
     * {@code true} SEULEMENT si ce champ Yarn named existe réellement dans
     * les mappings chargées pour cette classe — PAS une simple absence
     * d'exception. Indispensable avant de tenter une résolution "ancien
     * chemin vs nouveau chemin" (voir historique de session, audit modules) :
     * {@link #getObfFieldName} retombe sur le nom Yarn INCHANGÉ quand
     * l'entrée est introuvable — un appelant qui utilise ensuite ce nom tel
     * quel en réflexion brute peut retomber, PAR COÏNCIDENCE, sur un VRAI
     * champ obfusqué portant ce même nom court (1-2 lettres, très probable
     * vu l'alphabet réduit des noms obfusqués) — un faux positif silencieux,
     * pas juste un échec propre.
     */
    public static boolean hasFieldMapping(String yarnClass, String yarnField) {
        return isLoaded() && YarnMappings.getOfficialField(yarnClass, yarnField) != null;
    }

    public static String getObfFieldName(String yarnClass, String yarnField) {
        if (!isLoaded()) return yarnField;
        YarnMappings.FieldEntry entry = YarnMappings.getOfficialField(yarnClass, yarnField);
        if (entry == null) return yarnField;
        String officialOwner = YarnMappings.getOfficialClass(yarnClass);
        if (officialOwner == null) return entry.officialName;
        return officialToRuntimeField(officialOwner, entry.officialName);
    }

    /**
     * Runtime (selon le schéma actif) → named (Yarn).
     *
     * <p>{@code INTERMEDIARY} a son propre index direct dans {@link
     * YarnMappings} ; {@code SRG} passe par le pivot officiel, puis par la
     * table {@code official → named} commune. Deux chemins, une seule
     * destination.
     */
    private static String runtimeToNamed(String runtimeClass) {
        if (scheme == Scheme.INTERMEDIARY) {
            String named = YarnMappings.getNamedClassFromIntermediary(runtimeClass);
            return named != null ? named : runtimeClass;
        }
        String named = YarnMappings.getNamedClass(runtimeToOfficialClass(runtimeClass));
        return named != null ? named : runtimeClass;
    }

    // ── IRemapper (Mixin) ────────────────────────────────────────────────────

    @Override
    public String map(String typeName) {
        if (!isLoaded() || typeName == null) return typeName;
        String obf = YarnMappings.getOfficialClass(typeName);
        if (obf == null) return typeName;
        return officialToRuntimeClass(obf);
    }

    @Override
    public String unmap(String typeName) {
        if (!isLoaded() || typeName == null) return typeName;
        return runtimeToNamed(typeName);
    }

    /**
     * Sensible au DESCRIPTEUR depuis le 2026-09-11. Auparavant la recherche se
     * faisait par nom seul, ce qui retombait sur UNE des surcharges, pas
     * forcément la bonne — le bug déjà documenté dans
     * {@code LauncherMixinService.resolveOfficialMethodName} ({@code
     * Screen.init()} reciblé vers {@code init(int,int)}). Tant que ce chemin ne
     * servait presque jamais, ça passait inaperçu ; depuis {@code REFMAP_REMAP}
     * il traduit toutes les cibles {@code @At(target=…)} et tous les
     * {@code @Invoker} d'une version obfusquée, surcharges comprises.
     *
     * <p>Le descripteur reçu est dans l'espace SOURCE (noms Yarn) quand la
     * référence vient directement d'une annotation. S'il est déjà en noms
     * runtime (sortie du refmap), sa traduction ne trouve rien et la recherche
     * retombe sur le nom seul — puis sur le nom inchangé : c'est ce qui rend
     * ce chemin idempotent.
     */
    @Override
    public String mapMethodName(String owner, String name, String desc) {
        if (!isLoaded() || name == null) return name;
        String namedOwner = runtimeToNamed(owner);
        YarnMappings.MethodEntry entry = desc != null
            ? YarnMappings.getOfficialMethod(namedOwner, name, namedDescToOfficial(desc))
            : null;
        if (entry == null) entry = YarnMappings.getOfficialMethod(namedOwner, name);
        if (entry == null) return inheritedMethodName(namedOwner, name, desc);
        String officialOwner = YarnMappings.getOfficialClass(namedOwner);
        if (officialOwner == null) return entry.officialName;
        return officialToRuntimeMethod(officialOwner, entry.officialName, entry.officialDesc);
    }

    @Override
    public String mapFieldName(String owner, String name, String desc) {
        if (!isLoaded() || name == null) return name;
        String namedOwner = runtimeToNamed(owner);
        YarnMappings.FieldEntry entry = YarnMappings.getOfficialField(namedOwner, name);
        if (entry == null) return name;
        String officialOwner = YarnMappings.getOfficialClass(namedOwner);
        if (officialOwner == null) return entry.officialName;
        return officialToRuntimeField(officialOwner, entry.officialName);
    }

    @Override
    public String mapDesc(String desc) {
        if (!isLoaded() || desc == null) return desc;
        return remapDesc(desc, true);
    }

    @Override
    public String unmapDesc(String desc) {
        if (!isLoaded() || desc == null) return desc;
        return remapDesc(desc, false);
    }

    /**
     * Repli « méthode HÉRITÉE » (2026-09-11) : un site d'appel nomme la classe
     * STATIQUE du receveur, Yarn range la méthode sous sa classe DÉCLARANTE.
     * Cas réel : {@code Mouse.updateMouse} fait {@code invokevirtual
     * ClientPlayerEntity.changeLookDirection(DD)V} (vérifié au désassemblage du
     * jar 1.21.11), méthode déclarée sur {@code Entity} — la recherche directe
     * échoue et le nom Yarn ressortait tel quel, donc aucune correspondance.
     *
     * <p>Le repli cherche la méthode par nom + descripteur dans TOUTES les
     * classes, et n'accepte le résultat que si toutes les candidates donnent
     * le MÊME nom runtime. C'est vrai d'une méthode redéfinie le long d'une
     * hiérarchie (un obfuscateur garde le nom à travers les redéfinitions, et
     * intermediary donne un seul {@code method_XXXX} par famille), et faux de
     * deux méthodes sans rapport qui se trouveraient porter le même nom Yarn —
     * cas où l'on refuse de deviner et où le nom ressort inchangé, comme avant.
     */
    private static String inheritedMethodName(String namedOwner, String name, String desc) {
        if (desc == null) return name;
        java.util.Map<String, YarnMappings.MethodEntry> candidates =
            YarnMappings.findMethodsByNamedNameAndDesc(name, namedDescToOfficial(desc));
        String result = null;
        for (java.util.Map.Entry<String, YarnMappings.MethodEntry> c : candidates.entrySet()) {
            String officialOwner = YarnMappings.getOfficialClass(c.getKey());
            if (officialOwner == null) continue;
            String runtime = officialToRuntimeMethod(officialOwner, c.getValue().officialName, c.getValue().officialDesc);
            if (result == null) {
                result = runtime;
            } else if (!result.equals(runtime)) {
                LauncherLog.warn("[Mappings] " + namedOwner + "." + name + desc
                    + " : plusieurs classes candidates aux noms runtime différents — non traduit");
                return name;
            }
        }
        if (result == null) return name;
        LauncherLog.agent(1, "[Mappings] " + namedOwner + "." + name + desc
            + " résolue comme méthode héritée → " + result);
        return result;
    }

    /**
     * Méthode Yarn « named » (propriétaire, nom, descripteur en noms Yarn) →
     * nom RUNTIME du schéma actif. Pour {@code YarnNamedRemapper}, qui traduit
     * au chargement le code typé compilé contre des stubs aux noms Yarn.
     *
     * <p>Garde-fou volontaire : un propriétaire INCONNU de Yarn (nos propres
     * classes, {@code java.*}, JOML…) garde son nom tel quel, sans jamais
     * passer par le repli « méthode héritée » — ce repli cherche dans TOUTES
     * les classes et pourrait renommer une de nos méthodes qui porterait par
     * hasard le même nom et descripteur qu'une méthode du jeu.
     *
     * @param allowInherited autorise le repli « méthode héritée » (appel via
     *        une sous-classe d'une méthode déclarée plus haut) ; {@code false}
     *        pour décider du renommage d'une DÉCLARATION qui redéfinit.
     */
    public static String namedToRuntimeMethod(String namedOwner, String namedName, String namedDesc,
                                              boolean allowInherited) {
        if (!isLoaded() || namedName == null || namedName.startsWith("<")) return namedName;
        String officialOwner = YarnMappings.getOfficialClass(namedOwner);
        if (officialOwner == null) return namedName;
        YarnMappings.MethodEntry entry = namedDesc != null
            ? YarnMappings.getOfficialMethod(namedOwner, namedName, namedDescToOfficial(namedDesc))
            : null;
        if (entry == null) {
            return allowInherited ? inheritedMethodName(namedOwner, namedName, namedDesc) : namedName;
        }
        return officialToRuntimeMethod(officialOwner, entry.officialName, entry.officialDesc);
    }

    /** Champ Yarn « named » → nom RUNTIME. Même garde-fou que {@link #namedToRuntimeMethod}. */
    public static String namedToRuntimeField(String namedOwner, String namedField) {
        if (!isLoaded() || namedField == null || YarnMappings.getOfficialClass(namedOwner) == null) return namedField;
        return getObfFieldName(namedOwner, namedField);
    }

    /** {@code true} si Yarn connaît cette classe (nom « named ») — voir {@code YarnNamedRemapper}. */
    public static boolean isNamedClass(String namedClass) {
        return isLoaded() && namedClass != null && YarnMappings.getOfficialClass(namedClass) != null;
    }

    /**
     * Référence de champ pour une entrée de refmap d'{@code @Accessor} :
     * {@code nomRuntime:descripteurRuntime}, ou {@code null} si Yarn ne
     * connaît pas ce champ. Le descripteur est indispensable — voir
     * {@link YarnMappings.FieldEntry#officialDesc}.
     */
    public static String runtimeFieldReference(String yarnClass, String yarnField) {
        if (!isLoaded()) return null;
        YarnMappings.FieldEntry entry = YarnMappings.getOfficialField(yarnClass, yarnField);
        String officialOwner = YarnMappings.getOfficialClass(yarnClass);
        if (entry == null || officialOwner == null) return null;
        return officialToRuntimeField(officialOwner, entry.officialName) + ":" + runtimeDesc(entry.officialDesc);
    }

    /**
     * Descripteur en noms Yarn → descripteur en noms OFFICIELS (la clé des
     * index de {@link YarnMappings}). Un type introuvable reste tel quel :
     * type Java, bibliothèque non obfusquée, ou nom déjà runtime.
     */
    private static String namedDescToOfficial(String namedDesc) {
        StringBuilder sb = new StringBuilder(namedDesc.length());
        int i = 0;
        while (i < namedDesc.length()) {
            char c = namedDesc.charAt(i++);
            if (c == 'L') {
                int semi = namedDesc.indexOf(';', i);
                if (semi < 0) { sb.append('L').append(namedDesc.substring(i)); break; }
                String cls = namedDesc.substring(i, semi);
                String official = YarnMappings.getOfficialClass(cls);
                sb.append('L').append(official != null ? official : cls).append(';');
                i = semi + 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String remapDesc(String desc, boolean namedToRuntime) {
        StringBuilder sb = new StringBuilder(desc.length());
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i++);
            if (c == 'L') {
                int semi = desc.indexOf(';', i);
                if (semi < 0) { sb.append('L').append(desc.substring(i)); break; }
                String cls = desc.substring(i, semi);
                String mapped;
                if (namedToRuntime) {
                    String obf = YarnMappings.getOfficialClass(cls);
                    mapped = obf != null ? officialToRuntimeClass(obf) : cls;
                } else {
                    mapped = runtimeToNamed(cls);
                }
                sb.append('L').append(mapped).append(';');
                i = semi + 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
