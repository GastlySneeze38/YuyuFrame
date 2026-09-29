package com.yuyuframe.launcheragent.apimixin;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Relie chaque {@link AccessPoint} à l'accessor de la tranche ACTIVE — pendant
 * de {@code VanillaHookRegistry} côté injection, dans l'autre sens : ici c'est
 * {@code runtime/} qui INTERROGE, et la tranche qui FOURNIT.
 *
 * <h2>Comment la liaison arrive</h2>
 *
 * Chaque tranche a UNE classe de liaisons ({@code AccessorBindings261} pour
 * 26.1.2) dont le {@code <clinit>} appelle {@link #bind} pour chaque accès
 * qu'elle sait servir. Cette classe n'est chargée QUE si sa version est
 * active : {@link #ensureInitialized()} en dérive le nom depuis la version MC
 * détectée ({@code launcheragent.mcVersion}, posée par {@code LauncherAgent})
 * et la charge par nom.
 *
 * <p><b>Pourquoi par nom et pas par référence directe.</b> Une classe de
 * liaisons référence les accessors ET les classes réelles du jeu de SA version
 * ({@code net.minecraft.client.Minecraft} n'existe sous ce nom qu'à partir de
 * la 26.1). La nommer depuis un point neutre déclencherait un
 * {@code NoClassDefFoundError} au chargement sur toute autre tranche — d'où le
 * chargement par nom, avec {@code Throwable} attrapé et JOURNALISÉ (jamais
 * avalé : une liaison absente rend des modules entiers inertes, ça doit se
 * voir dans le log, pas se deviner).
 *
 * <p>C'est de la réflexion UNE FOIS, au premier accès, pour trouver la classe
 * de liaisons — pas à chaque lecture. Les accès eux-mêmes restent des appels
 * d'accessors tissés, donc résolus au chargement : la garantie « zéro
 * réflexion » de {@code PlayerData}/{@code ClientData} est intacte.
 *
 * <h2>Ce que ça déplace, et ce que ça ne déplace pas</h2>
 *
 * Ajouter une version MC = écrire ses accessors et SA classe de liaisons.
 * Aucun appelant ne change. En revanche les TYPES DE RETOUR exposés par les
 * façades ({@code LocalPlayer}, {@code Options}…) restent des types du jeu :
 * ils sont stables sur la ligne 26.x (non obfusquée), mais une tranche
 * obfusquée (1.8-1.21.x, gelées) n'a pas ces classes du tout. Rendre
 * {@code runtime/} utilisable sur une tranche obfusquée demanderait des types
 * neutres à nous, ce qui toucherait chaque module — chantier distinct, pas
 * couvert ici.
 */
public final class AccessorRegistry {

    private AccessorRegistry() {}

    /**
     * Une liaison : sert un {@link AccessPoint} pour la tranche active.
     *
     * @param receiver l'objet porteur ({@code Minecraft}, {@code Options},
     *                 {@code FoodData}…), ou {@code null} pour un accès
     *                 statique ({@link AccessPoint#CLIENT_FPS} par exemple)
     * @param args     arguments d'un {@code @Invoker} ou valeur d'une écriture ;
     *                 {@link #NO_ARGS} pour une simple lecture
     */
    public interface AccessBinding {
        Object invoke(Object receiver, Object[] args);
    }

    /** Tableau d'arguments vide partagé — évite une allocation par lecture. */
    public static final Object[] NO_ARGS = new Object[0];

    private static final Map<AccessPoint, AccessBinding> BINDINGS = new EnumMap<>(AccessPoint.class);
    /** Accès déjà signalés comme non liés — un log par accès, pas un par frame. */
    private static final Set<AccessPoint> REPORTED = EnumSet.noneOf(AccessPoint.class);

    private static volatile boolean initialized;
    private static String boundVersion;

    /**
     * Nom de la classe de liaisons d'une version — table littérale, comme la
     * liste des tranches. Une version absente d'ici n'a simplement aucune
     * liaison : tout renvoie la valeur de repli, et le premier accès le dit
     * dans le log.
     */
    private static String bindingsClassFor(String mcVersion) {
        if ("26.1.2".equals(mcVersion)) {
            return "com.yuyuframe.launcheragent.apimixin.v26_1_2.core.AccessorBindings261";
        }
        if ("26.1".equals(mcVersion)) {
            return "com.yuyuframe.launcheragent.apimixin.v26_1_0.core.AccessorBindings2610";
        }
        if ("26.1.1".equals(mcVersion)) {
            return "com.yuyuframe.launcheragent.apimixin.v26_1_1.core.AccessorBindings2611";
        }
        if ("26.2".equals(mcVersion)) {
            return "com.yuyuframe.launcheragent.apimixin.v26_2.core.AccessorBindings262";
        }
        if ("1.21.11".equals(mcVersion)) {
            // Sur cette version, l essentiel des données est public, donc
            // atteint sans accessor. Rangée à côté de ses accessors, comme
            // AccessorBindings261.
            return "com.yuyuframe.launcheragent.apimixin.v1_21_11.core.AccessorBindings1211";
        }
        if ("1.8.9".equals(mcVersion)) {
            // Même principe que la 1.21.11 : presque tout est public, trois
            // accessors seulement (épuisement, historique du chat, alwaysEdible).
            return "com.yuyuframe.launcheragent.apimixin.v1_8_9.core.AccessorBindings189";
        }
        return null;
    }

    /** Appelé par la classe de liaisons de la tranche active, jamais ailleurs. */
    public static void bind(AccessPoint point, AccessBinding binding) {
        BINDINGS.put(point, binding);
    }

    private static void ensureInitialized() {
        if (initialized) return;
        synchronized (AccessorRegistry.class) {
            if (initialized) return;
            initialized = true;   // posé AVANT la tentative : un échec ne doit pas la relancer à chaque accès
            String version = System.getProperty("launcheragent.mcVersion", "");
            String className = bindingsClassFor(version);
            if (className == null) {
                LauncherLog.warn("[AccessorRegistry] aucune classe de liaisons pour la version \"" + version
                    + "\" — tous les accès au jeu renverront leur valeur de repli");
                return;
            }
            try {
                Class.forName(className, true, AccessorRegistry.class.getClassLoader());
                boundVersion = version;
                LauncherLog.agent(3, "[AccessorRegistry] " + BINDINGS.size() + " accès liés pour la version "
                    + version + " (" + className + ")");
            } catch (Throwable t) {
                LauncherLog.err("[AccessorRegistry] chargement de " + className + " échoué (" + t
                    + ") — tous les accès au jeu renverront leur valeur de repli");
            }
        }
    }

    /** Version dont les liaisons sont chargées, ou {@code null} si aucune. */
    public static String boundVersion() {
        ensureInitialized();
        return boundVersion;
    }

    /** Cet accès est-il servi par la tranche active ? Utile pour un module qui préfère se désactiver plutôt que lire un repli. */
    public static boolean isBound(AccessPoint point) {
        ensureInitialized();
        return BINDINGS.containsKey(point);
    }

    /**
     * Lecture — {@code null} si l'accès n'est pas lié, ou si le receveur est
     * {@code null} alors que l'accès en attend un.
     */
    public static Object get(AccessPoint point, Object receiver) {
        return invoke(point, receiver, NO_ARGS);
    }

    /** Lecture d'un accès statique (sans receveur). */
    public static Object get(AccessPoint point) {
        return invoke(point, null, NO_ARGS);
    }

    /**
     * Forme générale — écriture ({@link AccessPoint#OPTION_VALUE_SET}) ou
     * {@code @Invoker} à arguments ({@link AccessPoint#CHAT_ADD_MESSAGE}).
     *
     * <p>Toute exception levée par la liaison est journalisée et convertie en
     * {@code null} : un accessor non tissé lève (voir l'idiome des accessors
     * statiques, qui refusent de renvoyer une valeur fausse), et ça ne doit
     * jamais faire tomber la frame en cours.
     */
    public static Object invoke(AccessPoint point, Object receiver, Object... args) {
        ensureInitialized();
        AccessBinding binding = BINDINGS.get(point);
        if (binding == null) {
            reportOnce(point, "non lié pour cette version");
            return null;
        }
        try {
            return binding.invoke(receiver, args == null ? NO_ARGS : args);
        } catch (Throwable t) {
            reportOnce(point, "liaison en échec : " + t);
            return null;
        }
    }

    /** Lecture typée — {@code null} si non lié, ou si la valeur n'est pas du type attendu. */
    public static <T> T as(Class<T> type, AccessPoint point, Object receiver) {
        Object value = get(point, receiver);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /** Lecture entière, {@code fallback} si non lié. Couvre aussi les champs {@code long}/{@code short} via {@link Number}. */
    public static int getInt(AccessPoint point, Object receiver, int fallback) {
        Object value = get(point, receiver);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    /** Lecture flottante, {@code fallback} si non lié. */
    public static float getFloat(AccessPoint point, Object receiver, float fallback) {
        Object value = get(point, receiver);
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    /** Lecture décimale, {@code fallback} si non lié. */
    public static double getDouble(AccessPoint point, Object receiver, double fallback) {
        Object value = get(point, receiver);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    /** Lecture booléenne, {@code fallback} si non lié. */
    public static boolean getBoolean(AccessPoint point, Object receiver, boolean fallback) {
        Object value = get(point, receiver);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static void reportOnce(AccessPoint point, String why) {
        synchronized (REPORTED) {
            if (!REPORTED.add(point)) return;
        }
        LauncherLog.warn("[AccessorRegistry] " + point + " : " + why
            + " — les appelants recevront leur valeur de repli (signalé une seule fois)");
    }
}
