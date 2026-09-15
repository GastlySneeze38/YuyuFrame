package com.yuyuframe.launcheragent.apigraphic.backend;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Choisit le backend de l'ère active — UNE fois, au premier appel.
 *
 * <h2>Un seul backend chargé, et pourquoi ça compte</h2>
 *
 * L'implémentation est chargée PAR NOM, depuis une table littérale
 * {@link #classFor}. Aucune classe d'{@code era/} n'est nommée ici.
 *
 * <p>Ce n'est pas qu'une question de propreté : tant qu'une seule
 * implémentation de {@link UiBackend} est chargée dans le process, la JVM le
 * sait, et l'appel d'interface est dévirtualisé puis inliné — sans même un
 * test de type. Nommer les quatre backends ici les ferait tous charger, et le
 * site d'appel deviendrait mégamorphe : une vraie table virtuelle, sur le
 * chemin de frame. Le chargement par nom est ce qui rend le dispatch
 * multiversion littéralement gratuit.
 *
 * <p>Même patron que {@code AccessorRegistry} → {@code AccessorBindings261} et
 * {@code AgentBridge} → {@code RuntimeBridge} : table de noms, {@code
 * Class.forName}, {@code Throwable} attrapé et journalisé, repli inerte.
 */
public final class UiBackendRegistry {

    private UiBackendRegistry() {}

    /**
     * Table littérale ère → classe. {@code null} = pas encore de backend pour
     * cette ère : le repli {@code NoopBackend} décline tout, et les appelants
     * gardent leur chemin historique (celui des trois renderers de
     * {@code render/}, pas encore découpés).
     */
    private static String classFor(RenderEra era) {
        switch (era) {
            case BLAZE3D: return "com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DBackend";
            case GL3:     return "com.yuyuframe.launcheragent.apigraphic.era.gl3.Gl3Backend";
            default:      return null;
        }
    }

    private static volatile UiBackend instance;

    /** Backend actif — jamais {@code null}. */
    public static UiBackend get() {
        UiBackend local = instance;
        if (local != null) return local;
        synchronized (UiBackendRegistry.class) {
            if (instance != null) return instance;
            instance = load();
            return instance;
        }
    }

    private static UiBackend load() {
        RenderEra era = RenderEra.active();
        String className = classFor(era);
        if (className == null) {
            LauncherLog.agent(3, "[UiBackendRegistry] ère " + era
                + " sans backend — chemin historique conservé (NoopBackend)");
            return noop();
        }
        try {
            Class<?> impl = Class.forName(className, true, UiBackendRegistry.class.getClassLoader());
            UiBackend backend = (UiBackend) impl.getDeclaredConstructor().newInstance();
            LauncherLog.agent(3, "[UiBackendRegistry] backend actif : " + backend.id() + " (ère " + era + ")");
            return backend;
        } catch (Throwable t) {
            LauncherLog.err("[UiBackendRegistry] " + className + " non chargeable (" + t
                + ") — chemin historique conservé (NoopBackend)");
            return noop();
        }
    }

    /**
     * L'objet nul — nommé directement, contrairement aux backends d'ère.
     *
     * <p>Ce n'est pas une ère : c'est le repli qui garantit que {@link #get()}
     * ne rend jamais {@code null}. Le charger par réflexion n'apporterait rien
     * et imposerait un repli du repli.
     *
     * <p>Une première version passait par une implémentation ANONYME en dernier
     * recours. Retirée : le vérifieur de bytecode la chargeait à l'entrée de
     * cette méthode, ce qui mettait DEUX implémentations de {@link UiBackend}
     * en mémoire alors qu'une seule sert. Or c'est précisément « une seule
     * implémentation chargée » qui permet à la JVM de dévirtualiser l'appel
     * sans test de type — l'argument qui rend ce dispatch gratuit sur le
     * chemin de frame.
     */
    private static UiBackend noop() {
        return new com.yuyuframe.launcheragent.apigraphic.era.noop.NoopBackend();
    }
}
