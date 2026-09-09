package com.yuyuframe.launcheragent.apimixin.mapping;

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
 * Scheme : sous Fabric, les classes/méthodes/champs du jeu sont nommés
 * "intermediary" à l'exécution (ex: method_25426), pas "official" (les noms
 * obfusqués bruts de Mojang, ex: bg_) comme en vanilla. {@link #setScheme}
 * doit être appelé une fois (selon que Fabric est détecté ou non) avant tout
 * usage — voir IsolatedBootstrap. Tout le reste de ce registre (et donc tout
 * code qui passe par lui : Mixin, ScreenHelper, IconWidgets) devient alors
 * automatiquement cohérent avec le schéma actif.
 */
public final class MappingsRegistry implements IRemapper {

    public static final MappingsRegistry INSTANCE = new MappingsRegistry();
    private MappingsRegistry() {}

    public enum Scheme { OFFICIAL, INTERMEDIARY }

    private static volatile Scheme scheme = Scheme.OFFICIAL;

    public static void setScheme(Scheme s) { scheme = s; }
    public static Scheme getScheme() { return scheme; }

    public static boolean isLoaded() { return YarnMappings.isLoaded(); }

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
            // "launcheragent.intermediary" (ex "launcheragent.fabric", renommé
            // P0-3 — voir docs/launcher/audit/README-bugs-a-fix.md) : découplé
            // de la décision d'isolation classloader, Quilt inclus désormais
            // (Fabric ET Quilt tournent en mappings intermediary).
            boolean intermediary = "true".equals(System.getProperty("launcheragent.intermediary"));
            scheme = intermediary ? Scheme.INTERMEDIARY : Scheme.OFFICIAL;
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

    // ── Official → runtime (officiel en vanilla, intermediary sous Fabric) ──────

    private static String officialToRuntimeClass(String officialClass) {
        ensureInitialized();
        if (scheme == Scheme.INTERMEDIARY) {
            String inter = YarnMappings.getIntermediaryClass(officialClass);
            if (inter != null) return inter;
        }
        return officialClass;
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
        ensureInitialized();
        if (scheme == Scheme.INTERMEDIARY && isLoaded()) {
            String inter = YarnMappings.getIntermediaryField(officialClass, officialField);
            if (inter != null) return inter;
        }
        return officialField;
    }

    /** Méthode (avec descripteur officiel exact) : nom officiel → nom runtime. */
    public static String runtimeMethod(String officialClass, String officialMethod, String officialDesc) {
        ensureInitialized();
        if (scheme == Scheme.INTERMEDIARY && isLoaded()) {
            String inter = YarnMappings.getIntermediaryMethod(officialClass, officialMethod, officialDesc);
            if (inter != null) return inter;
        }
        return officialMethod;
    }

    /**
     * Méthode sans descripteur connu à l'avance — renvoie tous les noms runtime
     * possibles (généralement 1, plusieurs si le nom officiel est surchargé).
     * À utiliser quand l'appelant filtre déjà par signature après le nom (cas
     * fréquent dans ScreenHelper) : remplacer {@code "x".equals(m.getName())}
     * par {@code runtimeMethodNames("Class","x").contains(m.getName())}.
     */
    public static Set<String> runtimeMethodNames(String officialClass, String officialMethod) {
        ensureInitialized();
        if (scheme == Scheme.INTERMEDIARY && isLoaded()) {
            Set<String> names = YarnMappings.getIntermediaryMethodNames(officialClass, officialMethod);
            if (!names.isEmpty()) return names;
        }
        return java.util.Collections.singleton(officialMethod);
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
        if (scheme == Scheme.INTERMEDIARY) {
            String officialOwner = YarnMappings.getOfficialClass(yarnClass);
            if (officialOwner != null) {
                String inter = YarnMappings.getIntermediaryMethod(officialOwner, entry.officialName, entry.officialDesc);
                if (inter != null) return inter;
            }
        }
        return entry.officialName;
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
        if (scheme == Scheme.INTERMEDIARY) {
            String officialOwner = YarnMappings.getOfficialClass(yarnClass);
            if (officialOwner != null) {
                String inter = YarnMappings.getIntermediaryMethod(officialOwner, entry.officialName, entry.officialDesc);
                if (inter != null) return inter;
            }
        }
        return entry.officialName;
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
        if (scheme == Scheme.INTERMEDIARY) {
            String officialOwner = YarnMappings.getOfficialClass(yarnClass);
            if (officialOwner != null) {
                String inter = YarnMappings.getIntermediaryField(officialOwner, entry.officialName);
                if (inter != null) return inter;
            }
        }
        return entry.officialName;
    }

    /** Runtime (official ou intermediary selon le schéma actif) → named (Yarn). */
    private static String runtimeToNamed(String runtimeClass) {
        if (scheme == Scheme.INTERMEDIARY) {
            String named = YarnMappings.getNamedClassFromIntermediary(runtimeClass);
            return named != null ? named : runtimeClass;
        }
        String named = YarnMappings.getNamedClass(runtimeClass);
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

    @Override
    public String mapMethodName(String owner, String name, String desc) {
        if (!isLoaded() || name == null) return name;
        String namedOwner = runtimeToNamed(owner);
        YarnMappings.MethodEntry entry = YarnMappings.getOfficialMethod(namedOwner, name);
        if (entry == null) return name;
        if (scheme == Scheme.INTERMEDIARY) {
            String officialOwner = YarnMappings.getOfficialClass(namedOwner);
            if (officialOwner != null) {
                String inter = YarnMappings.getIntermediaryMethod(officialOwner, entry.officialName, entry.officialDesc);
                if (inter != null) return inter;
            }
        }
        return entry.officialName;
    }

    @Override
    public String mapFieldName(String owner, String name, String desc) {
        if (!isLoaded() || name == null) return name;
        String namedOwner = runtimeToNamed(owner);
        YarnMappings.FieldEntry entry = YarnMappings.getOfficialField(namedOwner, name);
        if (entry == null) return name;
        if (scheme == Scheme.INTERMEDIARY) {
            String officialOwner = YarnMappings.getOfficialClass(namedOwner);
            if (officialOwner != null) {
                String inter = YarnMappings.getIntermediaryField(officialOwner, entry.officialName);
                if (inter != null) return inter;
            }
        }
        return entry.officialName;
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
