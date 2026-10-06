package com.yuyuframe.launcheragent.celeritas;

/**
 * L'interrupteur du renderer de terrain — notre code, pas celui de Celeritas.
 *
 * <p>Deux états distincts, pour ne jamais changer de renderer au milieu d'une
 * image :
 * <ul>
 *   <li>{@link #wanted()} — ce que demande le module « Optimisation » ;</li>
 *   <li>{@link #active()} — ce qui dessine réellement. Il ne suit
 *       {@code wanted} qu'au rechargement des chunks (voir
 *       {@code RendererHooks189.onReload}), le seul moment où l'ancien
 *       renderer n'a plus rien à l'écran.</li>
 * </ul>
 *
 * <p>Éteint par défaut : tant que {@code active} est faux, les mixins de
 * {@code apimixin/v1_8_9/renderer} laissent le rendu vanilla intact.
 */
public final class RendererSwitch {

    private RendererSwitch() {
    }

    private static volatile boolean wanted;
    private static volatile boolean active;

    /**
     * Le moteur a besoin de JOML et de fastutil, que la 1.8.9 n'embarque pas :
     * c'est le launcher qui les met sur le classpath. Un launcher plus ancien
     * que l'agent ne les fournit pas — le renderer reste alors éteint, et rien
     * de ce qui en dépend n'est chargé.
     */
    private static final boolean AVAILABLE = probe();

    private static boolean probe() {
        try {
            // Deux instanciations directes : si une bibliothèque manque, la JVM
            // lève NoClassDefFoundError ici plutôt qu'au milieu d'une image.
            new org.joml.Matrix4f();
            new it.unimi.dsi.fastutil.longs.LongArrayList();
            return true;
        } catch (LinkageError missing) {
            com.yuyuframe.launcheragent.base.log.LauncherLog.warn(
                "[Renderer] JOML ou fastutil absent du classpath — renderer de terrain indisponible "
                    + "(launcher plus ancien que l'agent) : " + missing);
            return false;
        }
    }

    /** Faux si les bibliothèques du moteur manquent : aucune de ses classes ne doit alors être touchée. */
    public static boolean available() {
        return AVAILABLE;
    }

    public static boolean wanted() {
        return wanted;
    }

    /** Sans effet si le moteur n'est pas disponible. */
    public static void setWanted(boolean value) {
        wanted = value && AVAILABLE;
    }

    public static boolean active() {
        return active;
    }

    /** Réservé au rechargement des chunks — voir la javadoc de classe. */
    public static void latch(boolean value) {
        active = value;
    }
}
