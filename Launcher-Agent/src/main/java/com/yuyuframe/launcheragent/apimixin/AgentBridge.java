package com.yuyuframe.launcheragent.apimixin;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Ce que les mixins hub demandent à la couche du DESSUS, sans jamais la nommer.
 *
 * <h2>Le problème que ça résout (2026-09-09)</h2>
 *
 * Les mixins hub ({@code GlobalUiRenderMixin261}, {@code GlobalUiPresentMixin261},
 * {@code TitleScreenMixin261}) appelaient directement {@code ModuleRegistry},
 * {@code GlobalUiSettings}, {@code ClientCommandRegistry}, {@code HudOverlayRenderer},
 * {@code UiMainMenuScreen}, {@code UiScreenBase} et {@code ReadyEventSignal} —
 * huit imports de {@code runtime/} depuis {@code apimixin/}.
 *
 * <p>C'est un CYCLE : la règle de couches veut module → apigraphic → apimixin
 * → base, or {@code apigraphic} a besoin d'{@code apimixin} pour ses accessors.
 * Si {@code apimixin} nomme {@code runtime} en retour, les deux couches ne
 * peuvent plus être compilées ni raisonnées séparément.
 *
 * <h2>Pourquoi une résolution PAR NOM, et pas une simple inversion</h2>
 *
 * Ces appels directs n'étaient pas une négligence : ils sont le MÉCANISME par
 * lequel {@code runtime/} se charge dans le bon classloader. Le bootstrap avait
 * été déplacé dans le mixin hub exprès (voir l'historique dans {@code
 * LauncherAgent.premain}) — depuis {@code premain}, {@code runtime} serait
 * chargé par le classloader SYSTÈME, alors que le code tissé tourne dans celui
 * du jeu ; deux copies de {@code VanillaHookRegistry$HookHandler} et
 * {@code LinkageError} garanti.
 *
 * <p>D'où cette forme : le mixin appelle {@link #get(ClassLoader)} en passant
 * le classloader du code TISSÉ ({@code this.getClass().getClassLoader()}, comme
 * {@code UiRenderer.get} le fait déjà), et l'implémentation est chargée par nom
 * depuis CE classloader. La propriété est conservée à l'identique, la référence
 * de compilation disparaît.
 *
 * <p>Même patron que {@code AccessorRegistry} → {@code AccessorBindings261} :
 * une table littérale de noms, un {@code Class.forName}, un {@code Throwable}
 * attrapé et JOURNALISÉ, et un repli qui ne fait rien plutôt que de planter.
 */
public interface AgentBridge {

    /** Nom de l'implémentation, résolue par nom — voir la javadoc de classe. */
    String IMPL_CLASS = "com.yuyuframe.launcheragent.runtime.RuntimeBridge";

    /**
     * Initialisation one-shot, au premier appel du hub : registre de modules,
     * réglages globaux, commandes client, audit des HookPoint déclarés.
     */
    void bootstrap();

    /** Tick des modules, une fois par frame. */
    void tick();

    /** Le joueur a-t-il masqué le HUD vanilla (F1) ? */
    boolean hudHidden();

    /**
     * Overlays plein écran des modules.
     *
     * @param uiRenderer le {@code UiRenderer} vivant — passé en {@code Object}
     *                   parce que {@code apigraphic} est AU-DESSUS d'ici :
     *                   cette couche transporte l'objet, elle ne le nomme pas
     */
    void renderOverlay(Object uiRenderer, int fbWidth, int fbHeight);

    /** Écran principal de l'agent, à ouvrir sur la touche dédiée — {@code null} si indisponible. */
    Object mainMenuScreen();

    /** Cet écran est-il un écran de l'agent avec une navigation en attente ? */
    boolean hasPendingNavigation(Object screen);

    /** Cible de navigation en attente : un écran à ouvrir, ou {@code null} pour fermer. */
    Object consumePendingNavigation(Object screen);

    /** Signale au launcher que le jeu est prêt (écran-titre atteint). */
    void signalReady(String eventProperty);

    // ── Résolution ─────────────────────────────────────────────────────────

    /**
     * Implémentation active, jamais {@code null} : en cas d'échec de
     * chargement, un repli inerte est renvoyé et l'échec est journalisé une
     * seule fois. Un hub qui perd sa couche du dessus doit laisser le jeu
     * tourner, pas le faire tomber.
     *
     * @param gameLoader classloader du code TISSÉ, pas celui du mixin — voir
     *                   la javadoc de classe
     */
    static AgentBridge get(ClassLoader gameLoader) {
        return Holder.resolve(gameLoader);
    }

    /** Porte l'instance et la logique de résolution — une interface ne peut pas avoir de champ mutable. */
    final class Holder {
        private Holder() {}

        private static volatile AgentBridge instance;

        static AgentBridge resolve(ClassLoader gameLoader) {
            AgentBridge local = instance;
            if (local != null) return local;
            synchronized (Holder.class) {
                if (instance != null) return instance;
                instance = load(gameLoader);
                return instance;
            }
        }

        private static AgentBridge load(ClassLoader gameLoader) {
            try {
                Class<?> impl = Class.forName(IMPL_CLASS, true,
                    gameLoader != null ? gameLoader : Holder.class.getClassLoader());
                AgentBridge bridge = (AgentBridge) impl.getDeclaredConstructor().newInstance();
                LauncherLog.agent(3, "[AgentBridge] " + IMPL_CLASS + " chargé depuis " + gameLoader);
                return bridge;
            } catch (Throwable t) {
                LauncherLog.err("[AgentBridge] " + IMPL_CLASS + " introuvable ou non instanciable ("
                    + t + ") — l'agent tournera SANS modules ni interface. Le jeu, lui, continue.");
                return new Noop();
            }
        }
    }

    /** Repli inerte — même rôle que le backend « noop » d'un moteur graphique. */
    final class Noop implements AgentBridge {
        @Override public void bootstrap() {}
        @Override public void tick() {}
        @Override public boolean hudHidden() { return false; }
        @Override public void renderOverlay(Object uiRenderer, int fbWidth, int fbHeight) {}
        @Override public Object mainMenuScreen() { return null; }
        @Override public boolean hasPendingNavigation(Object screen) { return false; }
        @Override public Object consumePendingNavigation(Object screen) { return null; }
        @Override public void signalReady(String eventProperty) {}
    }
}
