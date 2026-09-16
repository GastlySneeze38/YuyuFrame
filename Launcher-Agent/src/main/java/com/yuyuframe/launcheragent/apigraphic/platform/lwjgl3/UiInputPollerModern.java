package com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3;

import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implémentation LWJGL3/GLFW (1.13+, dont 1.21) de UiInputPoller.
 *
 * glfwGetCursorPos() renvoie des coordonnées "fenêtre" (origine HAUT-gauche),
 * alors que gl_FragCoord (utilisé par UiRenderer) est en pixels FRAMEBUFFER
 * (origine BAS-gauche) — deux conversions nécessaires, pas juste un flip :
 *   1. Passage fenêtre → framebuffer : les deux peuvent différer sur un écran
 *      HiDPI/Retina (framebuffer = fenêtre × content-scale), d'où le ratio
 *      framebufferSize/windowSize appliqué aux deux axes.
 *   2. Flip Y : uniquement après la mise à l'échelle, sur la hauteur
 *      framebuffer (pas la hauteur fenêtre).
 */
public final class UiInputPollerModern extends UiInputPoller {

    private final long windowHandle;
    private final ClassLoader gameClassLoader;
    private final Map<String, Method> glfwMethods = new HashMap<>();

    // GLFW n'a pas d'état de molette pollable (contrairement à LWJGL2
    // Mouse.getDWheel()) — uniquement un callback. Accumulé ici entre deux
    // poll(), consommé/remis à zéro par readScrollDelta().
    private volatile double pendingScroll;
    private final Object[] previousScrollCb = new Object[1];

    /**
     * Roadmap Phase 5.6 (carence "polling lié au FPS plutôt qu'au vrai
     * timing d'input") — état des boutons souris (index GLFW 0-7) tenu à
     * jour par un VRAI callback natif ({@link #registerMouseButtonCallback})
     * au lieu d'un {@code glfwGetMouseButton} échantillonné une fois par
     * frame RENDUE dans {@link #readState()} (l'ancienne approche, encore
     * utilisée par LEGACY GLFW ailleurs dans ce fichier pour {@code
     * shiftDown} — clavier, pas souris, carence pas signalée pour lui).
     * Minecraft appelle {@code glfwPollEvents()} depuis sa boucle
     * principale bien plus souvent que le FPS de rendu (le jeu continue de
     * traiter les événements même à bas FPS) — un appui+relâchement très
     * bref entre deux frames RENDUES, qu'un simple poll pouvait manquer,
     * déclenche quand même ce callback.
     */
    private final boolean[] buttonDown = new boolean[8];
    private final Object[] previousMouseButtonCb = new Object[1];

    /**
     * Audit input (retour utilisateur : "est-ce que le fait que ça passe par
     * frame et pas par OS input... est réglé ?") — MÊME carence "polling lié
     * au FPS" que {@link #buttonDown} ci-dessus, jamais étendue au clavier
     * jusqu'ici : {@link #readMenuKeyDown}/{@link #pollAnyKeyJustPressed}/
     * {@link #isKeyDownByName} lisaient tous l'état via {@code glfwGetKey},
     * sondé au mieux une fois par frame RENDUE — un tap très bref de la
     * touche d'ouverture du menu pile pendant une frame lente (chute de FPS)
     * pouvait donc ne jamais être vu, exactement comme pour les anciens
     * boutons souris. Tenu à jour par {@link #registerKeyCallback} au lieu
     * d'un poll direct. Indexé par code GLFW brut (GLFW_KEY_LAST=348, 350
     * de marge) — {@code glfwGetKey} reste utilisé ailleurs dans ce fichier
     * (pollTextEdit) là où le poll par frame n'a jamais posé de problème
     * (touches maintenues pour la répétition typematic, jamais des taps
     * isolés à risque de disparaître entre deux frames).
     */
    private final boolean[] keyDown = new boolean[350];
    private final Object[] previousKeyCb = new Object[1];

    /**
     * Touches que GLFW ne sait PAS nommer ({@code GLFW_KEY_UNKNOWN}, code
     * {@code -1}) — touches multimédia, touches OEM de certains claviers —
     * indexées par leur SCANCODE, seul identifiant stable qu'il en donne.
     * Sans ce suivi, elles étaient invisibles pour la capture : le code −1
     * sortait de {@link #keyDown} et l'appui était perdu sans un mot.
     */
    private final java.util.Set<Integer> scancodeDown =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());

    /**
     * APPUI JAMAIS PERDU (2026-09-13). L'état est lu par échantillonnage —
     * à chaque frame pour la capture, à chaque tick pour les macros. Un appui
     * dont l'enfoncement ET le relâchement tombent entre deux lectures n'était
     * donc jamais vu. Ce n'est pas théorique : sous Windows, Impr. écran
     * n'envoie ses deux événements qu'au relâchement, ensemble — la touche
     * était impossible à capturer. Un clic très bref pouvait aussi échapper
     * à une macro.
     *
     * <p>Règle : un appui reste visible au moins {@link #MIN_VISIBLE_NANOS}
     * à partir de sa PREMIÈRE lecture ; un relâchement arrivé avant est
     * différé jusque-là. Une fenêtre de TEMPS et non « une lecture » : chaque
     * frame, plusieurs consommateurs lisent la même entrée (l'interface, puis
     * les modules au tick) — si la première lecture appliquait le
     * relâchement, la seconde raterait l'appui. 60 ms couvre un tick de jeu
     * (50 ms) ; seul un tap plus court que ça est allongé, jamais un appui
     * normal. Indexé comme {@link #keyDown} et {@link #buttonDown}.
     */
    private static final long MIN_VISIBLE_NANOS = 60_000_000L;
    /** Instant de première lecture de l'appui en cours, {@code 0} = pas encore lu. */
    private final long[] keySeenAt = new long[350];
    private final boolean[] keyReleasePending = new boolean[350];
    private final long[] buttonSeenAt = new long[8];
    private final boolean[] buttonReleasePending = new boolean[8];
    private final Map<Integer, Long> scancodeSeenAt = new java.util.concurrent.ConcurrentHashMap<Integer, Long>();
    private final java.util.Set<Integer> scancodeReleasePending =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());

    /** Lecture d'une entrée avec la règle « appui jamais perdu » — voir {@link #keySeenAt}. */
    private static boolean readLatched(boolean[] down, long[] seenAt, boolean[] releasePending, int index) {
        if (index < 0 || index >= down.length || !down[index]) return false;
        long now = System.nanoTime();
        if (seenAt[index] == 0L) seenAt[index] = now;
        if (releasePending[index] && now - seenAt[index] >= MIN_VISIBLE_NANOS) {
            // Assez vu : le relâchement différé s'applique maintenant.
            down[index] = false;
            releasePending[index] = false;
            return false;
        }
        return true;
    }

    /** Écriture depuis un callback natif, pendant de {@link #readLatched}. */
    private static void writeLatched(boolean[] down, long[] seenAt, boolean[] releasePending, int index, boolean pressed) {
        if (index < 0 || index >= down.length) return;
        if (pressed) {
            // PRESS ou REPEAT. Un REPEAT ne remet pas l'horloge à zéro : seul
            // un vrai nouvel appui (depuis l'état relâché) le fait.
            if (!down[index]) seenAt[index] = 0L;
            down[index] = true;
            releasePending[index] = false;
        } else if (seenAt[index] != 0L && System.nanoTime() - seenAt[index] >= MIN_VISIBLE_NANOS) {
            down[index] = false;
        } else {
            releasePending[index] = true;
        }
    }

    private boolean scancodeDownLatched(int scancode) {
        if (!scancodeDown.contains(scancode)) return false;
        long now = System.nanoTime();
        Long seen = scancodeSeenAt.get(scancode);
        if (seen == null) { seen = now; scancodeSeenAt.put(scancode, now); }
        if (scancodeReleasePending.contains(scancode) && now - seen >= MIN_VISIBLE_NANOS) {
            scancodeReleasePending.remove(scancode);
            scancodeDown.remove(scancode);
            return false;
        }
        return true;
    }

    /** Préfixe d'une touche connue de GLFW mais absente de {@link #CAPTURABLE_KEYS} — suivi du code GLFW. */
    private static final String KEYCODE_PREFIX = "KEY";
    /** Préfixe d'une touche sans code GLFW — suivi du scancode, voir {@link #scancodeDown}. */
    private static final String SCANCODE_PREFIX = "SCAN";

    // Touches "capturables" pour UiKeybindButton — codes GLFW standards (API
    // publique stable, pas obfusqués, littéraux sûrs comme les constantes GL
    // ailleurs dans ce package). Pas de callback clavier ici (contrairement à
    // la molette) : un simple scan isKeyDown/frame suffit et reste dans le
    // même style "poll" que le reste de cette classe, uniquement appelé par
    // le widget en mode écoute (jamais chaque frame inconditionnellement).
    private static final Object[][] CAPTURABLE_KEYS = buildCapturableKeys();
    private final Map<Integer, Boolean> prevKeyDown = new HashMap<>();

    // Saisie de texte (UiTextField) — GLFW n'a pas non plus d'état "caractères
    // tapés" pollable, uniquement un callback (comme la molette). Backspace,
    // lui, n'a PAS besoin de callback : contrairement à LWJGL2 où tout passe
    // par une seule file d'événements partagée (Keyboard.next()), GLFW expose
    // key callback et char callback comme deux flux INDÉPENDANTS — un simple
    // scan isKeyDown suffit donc pour Backspace, sans risquer de "voler" les
    // événements caractère.
    private final StringBuilder pendingChars = new StringBuilder();
    private final Object[] previousCharCb = new Object[1];
    private boolean prevEnterCombinedDown;
    private boolean prevCtrlADown, prevCtrlCDown, prevCtrlVDown, prevCtrlXDown;

    /**
     * Instance active — voir historique de session : {@code ZoomModule}
     * (et tout futur module ayant besoin d'une touche configurable pollée
     * "à la demande", pas via {@link #pollAnyKeyJustPressed()} qui consomme
     * un état de front montant partagé) utilisait auparavant {@code
     * org.lwjgl.input.Keyboard} (LWJGL2) inconditionnellement — inexistant
     * sous LWJGL3/GLFW (1.13+), donc toujours en échec silencieux sur ce
     * bracket. Exposée ici pour qu'un module puisse vérifier une touche par
     * NOM sans dépendre de LWJGL2.
     */
    public static volatile UiInputPollerModern ACTIVE;

    public UiInputPollerModern(long windowHandle, ClassLoader gameClassLoader) {
        this.windowHandle = windowHandle;
        this.gameClassLoader = gameClassLoader;
        registerScrollCallback();
        registerCharCallback();
        registerMouseButtonCallback();
        registerKeyCallback();
        ACTIVE = this;
    }

    /**
     * État courant (maintenue ou non) d'une touche nommée arbitraire — même
     * table/format que {@link #menuKeyCode} (pas limité au champ statique
     * {@code menuKeyName}). Contrairement à {@link #pollAnyKeyJustPressed()},
     * ne consomme AUCUN état partagé (pas de front montant, juste l'état brut
     * GLFW instantané) — donc appelable librement par plusieurs consommateurs
     * indépendants (un module de touche configurable, etc.) sans interférence.
     */
    public boolean isKeyDownByName(String name) {
        try {
            // BUG TROUVÉ (roadmap Phase 5.6, confirmé : la touche zoom "C"
            // s'active en tapant dans le chat) : contrairement au système
            // KeyMapping natif de Minecraft (qui se désactive automatiquement
            // dès qu'un écran vanilla — chat, renommage, recherche
            // d'inventaire — est ouvert), ce poll brut n'avait aucune notion
            // d'écran ouvert. Un seul endroit centralisé (au lieu de dupliquer
            // le check dans chaque module appelant) — ZoomModule/FreelookModule
            // partagent déjà ce point d'entrée, voir leur javadoc.
            if (isVanillaScreenOpen()) return false;
            if (name == null || name.isEmpty() || "NONE".equals(name)) return false;

            // COMBINAISON : « LCTRL+K » n'est vrai que si TOUTES ses touches
            // sont maintenues. Le « + » est un séparateur sûr — aucun nom de
            // la table ne le contient (le plus du pavé s'appelle NUMADD).
            if (name.indexOf('+') >= 0) {
                for (String part : name.split("\\+")) {
                    String key = part.trim();
                    if (key.isEmpty()) continue;
                    if (!isNamedInputDown(key)) return false;
                }
                return true;
            }

            return isNamedInputDown(name);
        } catch (Exception e) {
            return false;
        }
    }

    /** Préfixe des noms de boutons de souris — voir {@link #mouseIndexForName}. */
    private static final String MOUSE_PREFIX = "MOUSE";

    /**
     * Index GLFW (0-7) du bouton de souris désigné par {@code name}
     * (« MOUSE1 » = clic gauche, « MOUSE2 » = droit, « MOUSE3 » = molette,
     * « MOUSE4 »/« MOUSE5 » = boutons latéraux), {@code -1} si ce n'est pas
     * un nom de bouton.
     *
     * <p>BUG TROUVÉ (retour utilisateur 2026-09-01, « bouton de la souris pas
     * détecté ») : toute la chaîne de raccourcis — capture dans {@code
     * UiKeybindButton} comme test d'état ici — ne connaissait que le CLAVIER,
     * via {@link #CAPTURABLE_KEYS}. Les boutons latéraux (le binding le plus
     * naturel pour un zoom) étaient donc invisibles : la capture ne retenait
     * rien et le module ne se déclenchait jamais. L'état des boutons, lui,
     * était déjà tenu à jour par un vrai callback natif (voir {@link
     * #buttonDown}) — il ne manquait qu'un nom pour le désigner.
     */
    private static int mouseIndexForName(String name) {
        if (name == null || !name.startsWith(MOUSE_PREFIX)) return -1;
        try {
            int n = Integer.parseInt(name.substring(MOUSE_PREFIX.length()));
            return (n >= 1 && n <= buttonDownLength()) ? n - 1 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Taille de {@link #buttonDown} — statique parce que {@link #mouseIndexForName} l'est. */
    private static int buttonDownLength() { return 8; }

    /** Vrai si l'entrée nommée (touche OU bouton de souris) est actuellement maintenue. */
    private boolean isNamedInputDown(String name) {
        int mouse = mouseIndexForName(name);
        if (mouse >= 0) return readLatched(buttonDown, buttonSeenAt, buttonReleasePending, mouse);
        int scancode = numberAfter(name, SCANCODE_PREFIX);
        if (scancode >= 0) return scancodeDownLatched(scancode);
        return readLatched(keyDown, keySeenAt, keyReleasePending, menuKeyCode(name));
    }

    /**
     * {@code true} si un écran vanilla (chat, inventaire, renommage...) OU
     * un de nos écrans custom ({@code UiScreenBase}, ex: le menu principal)
     * est actuellement affiché — point d'accès {@code CLIENT_SCREEN}, lié sur
     * les trois versions supportées (lu par réflexion jusqu'au 2026-09-16).
     */
    private static boolean isVanillaScreenOpen() {
        try {
            return com.yuyuframe.launcheragent.runtime.game.ClientData.screenObject() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object[][] buildCapturableKeys() {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < 26; i++) m.put(65 + i, String.valueOf((char) ('A' + i)));
        for (int i = 0; i <= 9; i++) m.put(48 + i, String.valueOf(i));
        for (int i = 0; i < 12; i++) m.put(290 + i, "F" + (i + 1));
        m.put(32, "SPACE"); m.put(257, "ENTER"); m.put(258, "TAB"); m.put(256, "ESCAPE");
        m.put(340, "LSHIFT"); m.put(344, "RSHIFT"); m.put(341, "LCTRL"); m.put(345, "RCTRL");
        m.put(342, "LALT"); m.put(346, "RALT");
        m.put(263, "LEFT"); m.put(262, "RIGHT"); m.put(265, "UP"); m.put(264, "DOWN");
        m.put(259, "BACKSPACE"); m.put(261, "DELETE"); m.put(280, "CAPSLOCK"); m.put(96, "GRAVE");
        m.put(260, "INSERT"); m.put(268, "HOME"); m.put(269, "END");
        m.put(266, "PAGEUP"); m.put(267, "PAGEDOWN");
        m.put(45, "MINUS"); m.put(61, "EQUAL");
        m.put(91, "LBRACKET"); m.put(93, "RBRACKET"); m.put(92, "BACKSLASH");
        m.put(59, "SEMICOLON"); m.put(39, "APOSTROPHE");
        m.put(44, "COMMA"); m.put(46, "PERIOD"); m.put(47, "SLASH");

        // PAVÉ NUMÉRIQUE — absent jusqu'ici, donc invisible pour la capture
        // de touche (demande utilisateur). Les codes GLFW du pavé sont
        // DISTINCTS de ceux de la rangée de chiffres : KP_0 vaut 320, pas 48.
        // Sans ces entrées, appuyer sur le 4 du pavé ne produisait rien du
        // tout, sans le moindre message.
        for (int i = 0; i <= 9; i++) m.put(320 + i, "NUM" + i);
        m.put(330, "NUMDECIMAL"); m.put(331, "NUMDIVIDE"); m.put(332, "NUMMULTIPLY");
        m.put(333, "NUMSUBTRACT"); m.put(334, "NUMADD"); m.put(335, "NUMENTER"); m.put(336, "NUMEQUAL");

        // COUVERTURE COMPLÈTE du clavier GLFW (demande utilisateur
        // 2026-09-13, « toutes les touches et combinaisons »). Codes relus
        // dans lwjgl-glfw-3.2.2.jar (javap -constants) : ce sont TOUTES les
        // constantes GLFW_KEY_* qui manquaient encore à la table.
        for (int i = 0; i < 13; i++) m.put(302 + i, "F" + (13 + i));   // F13-F25
        m.put(281, "SCROLLLOCK"); m.put(282, "NUMLOCK"); m.put(283, "PRINTSCREEN"); m.put(284, "PAUSE");
        m.put(343, "LSUPER"); m.put(347, "RSUPER"); m.put(348, "MENU");
        // Touches « non-US » : la touche en plus à gauche de W sur les
        // claviers ISO (< > sur AZERTY), entre autres.
        m.put(161, "WORLD1"); m.put(162, "WORLD2");
        Object[][] out = new Object[m.size()][2];
        int idx = 0;
        for (Map.Entry<Integer, String> e : m.entrySet()) out[idx++] = new Object[]{ e.getKey(), e.getValue() };
        return out;
    }

    /**
     * S'abonne au callback de molette GLFW en CHAÎNANT vers celui déjà en
     * place (retourné par glfwSetScrollCallback, ce qui remplace TOUJOURS le
     * précédent) — sans ça, on casserait silencieusement le scroll vanilla
     * (sélection hotbar, zoom longue-vue) partout dans le jeu, pas seulement
     * quand un de nos écrans custom est ouvert.
     *
     * CORRECTIF IMPORTANT (crash natif 0xC0000409 en jeu, trouvé après
     * élimination de tous les mods tiers — voir historique du projet) : la
     * version précédente passait le {@code java.lang.reflect.Proxy}
     * directement à {@code glfwSetScrollCallback}. Un Proxy implémente bien
     * l'interface Java {@code GLFWScrollCallbackI}, mais ce n'est PAS un vrai
     * objet natif-appelable — LWJGL construit ses callbacks via des classes
     * dédiées ({@code GLFWScrollCallback extends Callback}) qui mettent en
     * place un vrai pont natif (libffi), ce qu'un Proxy ne fait jamais. Passer
     * un Proxy brut à une fonction GLFW native est exactement le genre de
     * chose qui peut corrompre la pile quand GLFW essaie réellement d'invoquer
     * ce "callback" plus tard (molette/saisie), plutôt que d'échouer proprement
     * à chaque fois. Fix : envelopper le Proxy via la fabrique officielle
     * {@code GLFWScrollCallback.create(GLFWScrollCallbackI)} — elle renvoie un
     * VRAI objet Callback natif-appelable dont l'implémentation délègue en
     * simple appel Java normal vers notre Proxy (donc notre logique de
     * chaînage vers l'ancien callback reste inchangée), c'est CET objet qu'il
     * faut passer à glfwSetScrollCallback, jamais le Proxy brut.
     */
    private void registerScrollCallback() {
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW", true, gameClassLoader);
            Class<?> cbIface = Class.forName("org.lwjgl.glfw.GLFWScrollCallbackI", true, gameClassLoader);
            Class<?> cbClass = Class.forName("org.lwjgl.glfw.GLFWScrollCallback", true, gameClassLoader);
            Object proxy = Proxy.newProxyInstance(gameClassLoader, new Class[]{ cbIface }, (p, method, args) -> {
                // Cause RÉELLE du NPE trouvée en test (voir historique du projet) :
                // GLFWScrollCallbackI hérite de CallbackI, qui a des méthodes DEFAULT
                // (le vrai pont natif "callback(long)" que le proxy doit honorer, pas
                // juste notre "invoke" SAM) — les ignorer et renvoyer null pour tout ce
                // qui n'est pas notre "invoke" cassait justement CE pont natif.
                if (method.isDefault()) return invokeDefault(p, method, args);
                if (args != null && args.length == 3 && "invoke".equals(method.getName())) {
                    pendingScroll += (Double) args[2];
                    // Retour utilisateur : scroller pour zoomer plus loin
                    // changeait AUSSI l'objet en main — voir javadoc de
                    // UiInputPoller#suppressVanillaScroll. Tant qu'un module
                    // consomme le scroll pour son propre usage, on n'invoque
                    // PAS le callback vanilla chaîné (donc plus de
                    // changement de slot hotbar pendant qu'on zoome) ; le
                    // reste du temps, chaînage inchangé (scroll vanilla
                    // jamais cassé hors zoom).
                    if (!UiInputPoller.suppressVanillaScroll) {
                        Object prev = previousScrollCb[0];
                        if (prev != null) {
                            try { method.invoke(prev, args); } catch (Throwable ignored) {}
                        }
                    }
                }
                return null;
            });
            Method create = cbClass.getMethod("create", cbIface);
            Object realCallback = create.invoke(null, proxy);
            Method setCb = glfwClass.getMethod("glfwSetScrollCallback", long.class, cbIface);
            previousScrollCb[0] = setCb.invoke(null, windowHandle, realCallback);
        } catch (Throwable t) {
            LauncherLog.err("[UiInputPollerModern] registerScrollCallback: " + rootCause(t));
        }
    }

    /**
     * Même principe de chaînage ET du même correctif (wrapping via la fabrique
     * officielle) que registerScrollCallback() — ne casse jamais la saisie de
     * texte vanilla (chat, champs d'écrans).
     *
     * BUG TROUVÉ (confirmé en jeu — "des touches tapées en jeu (hors menu)
     * réapparaissent d'un coup dans la barre de recherche à la prochaine
     * ouverture") : ce callback est enregistré UNE FOIS, dès la toute première
     * frame du jeu (voir GlobalUiRenderMixin, bien avant l'ouverture du moindre
     * écran custom), et reste actif tout le reste de la session — SANS le
     * filtre {@link UiInputPoller#textInputActive} ci-dessous, il bufferisait
     * INCONDITIONNELLEMENT tout caractère tapé n'importe quand (chat vanilla,
     * renommage d'objet, etc.), même quand AUCUN de nos champs de texte n'était
     * focus. {@link UiTextField#pollTextEdit} ne draine {@code pendingChars}
     * QUE quand un champ a le focus — tout ce qui s'accumulait entre-temps
     * ressortait d'un coup, en bloc, à la frappe suivante. Fix : ne bufferiser
     * QUE si {@code textInputActive} est vrai (mis à jour par
     * {@code UiTextField.setFocused}) — le chaînage vers le callback vanilla
     * PRÉCÉDENT, lui, reste inconditionnel (ne doit jamais casser le chat/etc.).
     */
    private void registerCharCallback() {
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW", true, gameClassLoader);
            Class<?> cbIface = Class.forName("org.lwjgl.glfw.GLFWCharCallbackI", true, gameClassLoader);
            Class<?> cbClass = Class.forName("org.lwjgl.glfw.GLFWCharCallback", true, gameClassLoader);
            Object proxy = Proxy.newProxyInstance(gameClassLoader, new Class[]{ cbIface }, (p, method, args) -> {
                if (method.isDefault()) return invokeDefault(p, method, args);
                if (args != null && args.length == 2 && "invoke".equals(method.getName())) {
                    if (UiInputPoller.textInputActive) {
                        int codepoint = (Integer) args[1];
                        synchronized (pendingChars) {
                            pendingChars.append(Character.toChars(codepoint));
                        }
                    }
                    Object prev = previousCharCb[0];
                    if (prev != null) {
                        try { method.invoke(prev, args); } catch (Throwable ignored) {}
                    }
                }
                return null;
            });
            Method create = cbClass.getMethod("create", cbIface);
            Object realCallback = create.invoke(null, proxy);
            Method setCb = glfwClass.getMethod("glfwSetCharCallback", long.class, cbIface);
            previousCharCb[0] = setCb.invoke(null, windowHandle, realCallback);
        } catch (Throwable t) {
            LauncherLog.err("[UiInputPollerModern] registerCharCallback: " + rootCause(t));
        }
    }

    /**
     * Même principe de chaînage ET du même correctif (wrapping via la
     * fabrique officielle {@code GLFWMouseButtonCallback.create(...)}) que
     * {@link #registerScrollCallback}/{@link #registerCharCallback} — ne
     * casse jamais le clic vanilla en dehors de nos écrans custom. Action
     * GLFW pour un bouton souris est TOUJOURS 0 (RELEASE) ou 1 (PRESS),
     * jamais 2 (REPEAT, réservé au clavier) — {@code action != 0} suffit.
     */
    private void registerMouseButtonCallback() {
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW", true, gameClassLoader);
            Class<?> cbIface = Class.forName("org.lwjgl.glfw.GLFWMouseButtonCallbackI", true, gameClassLoader);
            Class<?> cbClass = Class.forName("org.lwjgl.glfw.GLFWMouseButtonCallback", true, gameClassLoader);
            Object proxy = Proxy.newProxyInstance(gameClassLoader, new Class[]{ cbIface }, (p, method, args) -> {
                if (method.isDefault()) return invokeDefault(p, method, args);
                if (args != null && args.length == 4 && "invoke".equals(method.getName())) {
                    // (long window, int button, int action, int mods)
                    int button = (Integer) args[1];
                    int action = (Integer) args[2];
                    writeLatched(buttonDown, buttonSeenAt, buttonReleasePending, button, action != 0);
                    Object prev = previousMouseButtonCb[0];
                    if (prev != null) {
                        try { method.invoke(prev, args); } catch (Throwable ignored) {}
                    }
                }
                return null;
            });
            Method create = cbClass.getMethod("create", cbIface);
            Object realCallback = create.invoke(null, proxy);
            Method setCb = glfwClass.getMethod("glfwSetMouseButtonCallback", long.class, cbIface);
            previousMouseButtonCb[0] = setCb.invoke(null, windowHandle, realCallback);
        } catch (Throwable t) {
            LauncherLog.err("[UiInputPollerModern] registerMouseButtonCallback: " + rootCause(t));
        }
    }

    /**
     * Même principe de chaînage ET du même correctif (wrapping via la
     * fabrique officielle {@code GLFWKeyCallback.create(...)}) que {@link
     * #registerScrollCallback}/{@link #registerCharCallback}/{@link
     * #registerMouseButtonCallback} — ne casse jamais les touches vanilla
     * (déplacement, raccourcis, ouverture d'inventaire...). Voir {@link
     * #keyDown} pour le bug fermé par ce callback. {@code action != 0}
     * (GLFW_PRESS ou GLFW_REPEAT) = enfoncée, {@code action == 0}
     * (GLFW_RELEASE) = relâchée — même convention que {@link
     * #registerMouseButtonCallback} (REPEAT n'existe que pour le clavier,
     * mais reste correctement "toujours enfoncée" avec ce test).
     */
    private void registerKeyCallback() {
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW", true, gameClassLoader);
            Class<?> cbIface = Class.forName("org.lwjgl.glfw.GLFWKeyCallbackI", true, gameClassLoader);
            Class<?> cbClass = Class.forName("org.lwjgl.glfw.GLFWKeyCallback", true, gameClassLoader);
            Object proxy = Proxy.newProxyInstance(gameClassLoader, new Class[]{ cbIface }, (p, method, args) -> {
                if (method.isDefault()) return invokeDefault(p, method, args);
                if (args != null && args.length == 5 && "invoke".equals(method.getName())) {
                    // (long window, int key, int scancode, int action, int mods)
                    int key = (Integer) args[1];
                    int scancode = (Integer) args[2];
                    int action = (Integer) args[3];
                    if (key >= 0 && key < keyDown.length) {
                        writeLatched(keyDown, keySeenAt, keyReleasePending, key, action != 0);
                    } else if (key < 0) {
                        // GLFW_KEY_UNKNOWN : seul le scancode l'identifie.
                        // Même règle « appui jamais perdu » que writeLatched.
                        if (action != 0) {
                            if (!scancodeDown.contains(scancode)) scancodeSeenAt.remove(scancode);
                            scancodeDown.add(scancode);
                            scancodeReleasePending.remove(scancode);
                        } else {
                            Long seen = scancodeSeenAt.get(scancode);
                            if (seen != null && System.nanoTime() - seen >= MIN_VISIBLE_NANOS) {
                                scancodeDown.remove(scancode);
                            } else {
                                scancodeReleasePending.add(scancode);
                            }
                        }
                    }
                    Object prev = previousKeyCb[0];
                    if (prev != null) {
                        try { method.invoke(prev, args); } catch (Throwable ignored) {}
                    }
                }
                return null;
            });
            Method create = cbClass.getMethod("create", cbIface);
            Object realCallback = create.invoke(null, proxy);
            Method setCb = glfwClass.getMethod("glfwSetKeyCallback", long.class, cbIface);
            previousKeyCb[0] = setCb.invoke(null, windowHandle, realCallback);
        } catch (Throwable t) {
            LauncherLog.err("[UiInputPollerModern] registerKeyCallback: " + rootCause(t));
        }
    }

    /** InvocationTargetException.toString() cache la vraie cause — la déballer pour un log utile. */
    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        java.io.StringWriter sw = new java.io.StringWriter();
        cur.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static Method invocationHandlerInvokeDefault;
    private static boolean invocationHandlerInvokeDefaultFailed;

    /**
     * {@code InvocationHandler.invokeDefault(Object, Method, Object...)}
     * (JDK 16+) exécute la VRAIE implémentation par défaut d'une méthode
     * d'interface pour un Proxy donné — indispensable ici car les interfaces
     * de callback LWJGL (ex: GLFWScrollCallbackI) héritent de méthodes
     * default de {@code org.lwjgl.system.CallbackI} qui font le vrai pont
     * natif ; sans ça, notre handler renvoyait null pour toute méthode qui
     * n'était pas notre "invoke" attendu, cassant ce pont (NPE constaté :
     * "Cannot invoke Long.longValue() because the return value of
     * InvocationHandler.invoke(...) is null").
     *
     * Résolu par réflexion (pas d'appel direct compilé) : ce module compile
     * avec {@code --release 8} (voir build.bat), qui masque cette méthode au
     * moment de la compilation même si le JDK qui exécute réellement cette
     * branche (1.21.11, JAVA_17 mini) l'a bien à l'exécution.
     */
    private static Object invokeDefault(Object proxy, Method method, Object[] args) throws Throwable {
        if (invocationHandlerInvokeDefault == null && !invocationHandlerInvokeDefaultFailed) {
            try {
                invocationHandlerInvokeDefault = java.lang.reflect.InvocationHandler.class
                    .getMethod("invokeDefault", Object.class, Method.class, Object[].class);
            } catch (Throwable t) {
                invocationHandlerInvokeDefaultFailed = true;
            }
        }
        if (invocationHandlerInvokeDefault == null) return null;
        try {
            return invocationHandlerInvokeDefault.invoke(null, proxy, method, args == null ? new Object[0] : args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Override
    protected void readState() throws Exception {
        double[] cx = new double[1];
        double[] cy = new double[1];
        glfwGetCursorPos(windowHandle, cx, cy);

        int[] winW = new int[1], winH = new int[1];
        glfwGetWindowSize(windowHandle, winW, winH);
        int[] fbW = new int[1], fbH = new int[1];
        glfwGetFramebufferSize(windowHandle, fbW, fbH);

        // winW/winH peuvent valoir 0 juste après création de fenêtre — repli
        // sans mise à l'échelle (ratio 1) plutôt qu'une division par zéro.
        double scaleX = winW[0] > 0 ? (double) fbW[0] / winW[0] : 1.0;
        double scaleY = winH[0] > 0 ? (double) fbH[0] / winH[0] : 1.0;

        mouseX = cx[0] * scaleX;
        mouseY = fbH[0] - (cy[0] * scaleY); // flip après mise à l'échelle, sur la hauteur framebuffer

        // BUG TROUVÉ : glfwGetFramebufferSize renvoie (0,0) quand la fenêtre
        // est minimisée (iconifiée) — fbWidth/fbHeight à 0 se propagent
        // jusqu'à glOrtho(0, vpWidth, 0, vpHeight, -1, 1) dans UiRenderer,
        // où (right-left) ou (top-bottom) devient nul, d'où le spam
        // GL_INVALID_VALUE "View frustum must not have a zero values".
        // On garde la dernière taille connue plutôt que d'écraser avec 0.
        if (fbW[0] > 0) fbWidth = fbW[0];
        if (fbH[0] > 0) fbHeight = fbH[0];

        // Callback natif (registerMouseButtonCallback), plus un poll —
        // voir sa javadoc pour le pourquoi (carence "polling lié au FPS").
        // Lu avec la règle « appui jamais perdu » (voir keySeenAt) : un clic
        // plus bref qu'une frame est désormais vu par l'interface aussi.
        leftDown = readLatched(buttonDown, buttonSeenAt, buttonReleasePending, 0);
        rightDown = readLatched(buttonDown, buttonSeenAt, buttonReleasePending, 1);
        middleDown = readLatched(buttonDown, buttonSeenAt, buttonReleasePending, 2);
        for (int i = 0; i < sideButtonDown.length; i++) {
            sideButtonDown[i] = readLatched(buttonDown, buttonSeenAt, buttonReleasePending, 3 + i);
        }

        shiftDown = glfwGetKey(windowHandle, 340) == 1 || glfwGetKey(windowHandle, 344) == 1; // GLFW_KEY_LEFT/RIGHT_SHIFT
        altDown = glfwGetKey(windowHandle, 342) == 1 || glfwGetKey(windowHandle, 346) == 1;   // GLFW_KEY_LEFT/RIGHT_ALT
    }

    @Override
    protected boolean readMenuKeyDown() throws Exception {
        // Callback natif (registerKeyCallback), lu avec la règle « appui jamais perdu ».
        return readLatched(keyDown, keySeenAt, keyReleasePending, menuKeyCode(menuKeyName));
    }

    /**
     * Noms des touches capturables ACTUELLEMENT maintenues, modificateurs
     * d'abord — pour {@link com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiKeybindButton},
     * qui compose une combinaison.
     *
     * <p>Ordre canonique et non ordre d'appui : « LCTRL+MAJ+K » doit
     * s'afficher pareil qu'on ait pressé Ctrl ou Maj en premier, sinon deux
     * captures de la même combinaison donneraient deux chaînes différentes,
     * donc deux réglages incompatibles.
     *
     * <p>Instantané appelé chaque frame pendant la capture, avec la règle
     * « appui jamais perdu » (voir {@link #keySeenAt}) : c'est ce qui rend
     * Impr. écran capturable.
     */
    public java.util.List<String> heldCapturableKeys() {
        java.util.List<String> modifiers = new java.util.ArrayList<String>();
        java.util.List<String> others = new java.util.ArrayList<String>();
        for (Object[] entry : CAPTURABLE_KEYS) {
            int code = (Integer) entry[0];
            if (!readLatched(keyDown, keySeenAt, keyReleasePending, code)) continue;
            String name = (String) entry[1];
            if (isModifier(name)) modifiers.add(name);
            else others.add(name);
        }
        // Tout code GLFW maintenu que la table ne nomme pas : capturé sous
        // « KEY<code> » plutôt qu'ignoré. La table couvre aujourd'hui toutes
        // les constantes GLFW_KEY_* ; ce repli protège d'une version de GLFW
        // qui en ajouterait, au lieu de retomber dans « l'appui ne fait rien ».
        for (int code = 0; code < keyDown.length; code++) {
            if (keyDown[code] && menuKeyNameForCode(code) == null
                && readLatched(keyDown, keySeenAt, keyReleasePending, code)) {
                others.add(KEYCODE_PREFIX + code);
            }
        }
        // Touches sans code GLFW, par scancode — voir scancodeDown. Triées :
        // l'ordre d'itération d'un ensemble concurrent n'est pas stable, et
        // la même combinaison doit toujours donner la même chaîne.
        java.util.List<Integer> scancodes = new java.util.ArrayList<Integer>(scancodeDown);
        java.util.Collections.sort(scancodes);
        for (Integer sc : scancodes) {
            if (scancodeDownLatched(sc)) others.add(SCANCODE_PREFIX + sc);
        }
        // Boutons de souris — capturables au même titre qu'une touche (voir
        // mouseIndexForName). Jamais des modificateurs : « MOUSE4 » se
        // combine comme une lettre, pas comme un Ctrl.
        for (int i = 0; i < buttonDown.length; i++) {
            if (readLatched(buttonDown, buttonSeenAt, buttonReleasePending, i)) others.add(MOUSE_PREFIX + (i + 1));
        }
        modifiers.addAll(others);
        return modifiers;
    }

    private static boolean isModifier(String name) {
        return "LCTRL".equals(name) || "RCTRL".equals(name)
            || "LSHIFT".equals(name) || "RSHIFT".equals(name)
            || "LALT".equals(name) || "RALT".equals(name)
            // Touche Windows / Cmd : modificateur au même titre que Ctrl, donc
            // placée en tête d'une combinaison.
            || "LSUPER".equals(name) || "RSUPER".equals(name);
    }

    /** Résout un nom de touche (même format que CAPTURABLE_KEYS/pollAnyKeyJustPressed) vers son code GLFW — {@code -1} si inconnu. */
    private static int menuKeyCode(String name) {
        for (Object[] entry : CAPTURABLE_KEYS) {
            if (entry[1].equals(name)) return (Integer) entry[0];
        }
        // « KEY<code> » — voir heldCapturableKeys.
        return numberAfter(name, KEYCODE_PREFIX);
    }

    /** Nom de table d'un code GLFW, {@code null} s'il n'y figure pas. */
    private static String menuKeyNameForCode(int code) {
        for (Object[] entry : CAPTURABLE_KEYS) {
            if (((Integer) entry[0]) == code) return (String) entry[1];
        }
        return null;
    }

    /**
     * Entier qui suit {@code prefix} dans {@code name} (« SCAN57 » → 57),
     * {@code -1} si le nom n'a pas cette forme. Exige des CHIFFRES seuls après
     * le préfixe : « KEYPAD » ou « SCANNER » ne doivent pas être lus comme
     * des codes.
     */
    private static int numberAfter(String name, String prefix) {
        if (name == null || !name.startsWith(prefix) || name.length() == prefix.length()) return -1;
        for (int i = prefix.length(); i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Sens INVERSE de {@link #menuKeyCode} — nom lisible d'un code GLFW —
     * utilisé par {@code KeystrokesModule} (voir historique de session,
     * audit des modules) : {@code KeyBinding.code} (int direct) a disparu en
     * 1.13+, remplacé par {@code KeyBinding.boundKey} (objet {@code
     * InputUtil.Key}), dont {@code getCode()} renvoie un code GLFW — plus de
     * {@code org.lwjgl.input.Keyboard.getKeyName(int)} (LWJGL2) pour le
     * traduire en texte. Cherche d'abord dans {@link #CAPTURABLE_KEYS} (F1-F12,
     * flèches, modificateurs — sans représentation imprimable, {@code
     * glfwGetKeyName} renvoie {@code null} pour eux), sinon retombe sur
     * {@code glfwGetKeyName} (touches imprimables A-Z/0-9/ponctuation).
     */
    public static String nameForKeyCode(int code, ClassLoader gameClassLoader) {
        for (Object[] entry : CAPTURABLE_KEYS) {
            if (((Integer) entry[0]) == code) return (String) entry[1];
        }
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW", true, gameClassLoader);
            Method m = glfwClass.getMethod("glfwGetKeyName", int.class, int.class);
            String name = (String) m.invoke(null, code, 0);
            return name == null ? null : name.toUpperCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected synchronized int readScrollDelta() throws Exception {
        int delta = (int) Math.round(pendingScroll);
        pendingScroll = 0;
        return delta;
    }

    @Override
    public String pollAnyKeyJustPressed() {
        try {
            for (Object[] entry : CAPTURABLE_KEYS) {
                int code = (Integer) entry[0];
                boolean down = readLatched(keyDown, keySeenAt, keyReleasePending, code); // callback natif, voir registerKeyCallback et keySeenAt
                boolean was = Boolean.TRUE.equals(prevKeyDown.get(code));
                prevKeyDown.put(code, down);
                if (down && !was) return (String) entry[1];
            }
        } catch (Exception e) {
            LauncherLog.err("[UiInputPollerModern] pollAnyKeyJustPressed: " + e);
        }
        return null;
    }

    /**
     * Contrairement à LWJGL2 (Legacy), GLFW expose CHAQUE touche comme un
     * état indépendant pollable ({@code glfwGetKey}) — pas de file
     * d'événements partagée à se disputer, donc toutes les intentions
     * (Backspace/Suppr/flèches/Origine/Fin/Ctrl+A/C/X/V/Entrée) sont lues en
     * simple scan ici, chacune avec son propre suivi de front montant (ou
     * {@link UiInputPoller#keyRepeatFire} pour celles qui doivent se répéter
     * en maintenant la touche).
     */
    @Override
    public void pollTextEdit() {
        editTyped = "";
        synchronized (pendingChars) {
            if (pendingChars.length() > 0) {
                editTyped = pendingChars.toString();
                pendingChars.setLength(0);
            }
        }
        try {
            boolean ctrl = glfwGetKey(windowHandle, 341) == 1 || glfwGetKey(windowHandle, 345) == 1;   // GLFW_KEY_LEFT/RIGHT_CONTROL
            editShiftHeld = glfwGetKey(windowHandle, 340) == 1 || glfwGetKey(windowHandle, 344) == 1; // GLFW_KEY_LEFT/RIGHT_SHIFT

            editBackspace = keyRepeatFire("modern.backspace", glfwGetKey(windowHandle, 259) == 1); // GLFW_KEY_BACKSPACE
            editDelete    = keyRepeatFire("modern.delete",    glfwGetKey(windowHandle, 261) == 1); // GLFW_KEY_DELETE
            editLeft      = keyRepeatFire("modern.left",      glfwGetKey(windowHandle, 263) == 1); // GLFW_KEY_LEFT
            editRight     = keyRepeatFire("modern.right",     glfwGetKey(windowHandle, 262) == 1); // GLFW_KEY_RIGHT
            editHome      = keyRepeatFire("modern.home",      glfwGetKey(windowHandle, 268) == 1); // GLFW_KEY_HOME
            editEnd       = keyRepeatFire("modern.end",       glfwGetKey(windowHandle, 269) == 1); // GLFW_KEY_END

            // Entrée / pavé numérique Entrée — un seul déclenchement par appui,
            // jamais de répétition (soumission, pas édition continue).
            boolean enterDown = glfwGetKey(windowHandle, 257) == 1 || glfwGetKey(windowHandle, 335) == 1; // GLFW_KEY_ENTER / KP_ENTER
            editEnter = enterDown && !prevEnterCombinedDown;
            prevEnterCombinedDown = enterDown;

            if (ctrl) {
                boolean aDown = glfwGetKey(windowHandle, physicalKeyForLetter('a')) == 1;
                editSelectAll = aDown && !prevCtrlADown;
                prevCtrlADown = aDown;
                boolean cDown = glfwGetKey(windowHandle, physicalKeyForLetter('c')) == 1;
                editCopy = cDown && !prevCtrlCDown;
                prevCtrlCDown = cDown;
                boolean vDown = glfwGetKey(windowHandle, physicalKeyForLetter('v')) == 1;
                editPaste = vDown && !prevCtrlVDown;
                prevCtrlVDown = vDown;
                boolean xDown = glfwGetKey(windowHandle, physicalKeyForLetter('x')) == 1;
                editCut = xDown && !prevCtrlXDown;
                prevCtrlXDown = xDown;
            } else {
                editSelectAll = editCopy = editPaste = editCut = false;
                prevCtrlADown = prevCtrlCDown = prevCtrlVDown = prevCtrlXDown = false;
            }
        } catch (Exception e) {
            LauncherLog.err("[UiInputPollerModern] pollTextEdit: " + e);
        }
    }

    private int glfwGetKey(long handle, int key) throws Exception {
        return (int) glfw("glfwGetKey", long.class, int.class).invoke(null, handle, key);
    }

    // Codes physiques A-Z (65-90) résolus une fois pour la lettre voulue —
    // BUG TROUVÉ (confirmé : "Ctrl+A ne marche pas", clavier AZERTY français) :
    // les constantes GLFW_KEY_A..Z sont des positions PHYSIQUES calées sur un
    // clavier US QWERTY, PAS la lettre réellement produite. Sur AZERTY, la
    // touche physique portant le label "A" occupe la position QWERTY de "Q" —
    // glfwGetKey(handle, 65 /*GLFW_KEY_A*/) ne devenait donc JAMAIS vrai quand
    // l'utilisateur pressait sa touche "A". Fix : {@code glfwGetKeyName(code,
    // 0)} renvoie le label RÉELLEMENT affiché sur le clavier de l'utilisateur
    // pour un code physique donné (déjà utilisé ailleurs dans ce fichier, voir
    // {@link #nameForKeyCode}) — on scanne les 26 positions A-Z une seule fois
    // et on garde celle dont le label correspond à la lettre voulue. Repli sur
    // le mapping US direct (A=65 etc.) si glfwGetKeyName échoue/renvoie null
    // (layout sans labels imprimables résolus, très rare).
    private final Map<Character, Integer> letterKeyCache = new HashMap<>();

    private int physicalKeyForLetter(char lower) {
        Integer cached = letterKeyCache.get(lower);
        if (cached != null) return cached;
        int resolved = 65 + (lower - 'a'); // repli US QWERTY
        try {
            Method getKeyName = glfw("glfwGetKeyName", int.class, int.class);
            for (int code = 65; code <= 90; code++) {
                String name = (String) getKeyName.invoke(null, code, 0);
                if (name != null && name.length() == 1 && Character.toLowerCase(name.charAt(0)) == lower) {
                    resolved = code;
                    break;
                }
            }
        } catch (Exception e) {
            LauncherLog.err("[UiInputPollerModern] physicalKeyForLetter('" + lower + "'): " + e);
        }
        letterKeyCache.put(lower, resolved);
        return resolved;
    }

    // ── GLFW via réflexion (org.lwjgl.glfw.GLFW — API publique, pas obfusquée) ──

    private Method glfw(String name, Class<?>... params) throws Exception {
        String key = name + java.util.Arrays.toString(params);
        Method m = glfwMethods.get(key);
        if (m != null) return m;
        Class<?> c = Class.forName("org.lwjgl.glfw.GLFW", true, gameClassLoader);
        m = c.getMethod(name, params);
        glfwMethods.put(key, m);
        return m;
    }

    private void glfwGetCursorPos(long handle, double[] xOut, double[] yOut) throws Exception {
        glfw("glfwGetCursorPos", long.class, double[].class, double[].class).invoke(null, handle, xOut, yOut);
    }

    private void glfwGetWindowSize(long handle, int[] wOut, int[] hOut) throws Exception {
        glfw("glfwGetWindowSize", long.class, int[].class, int[].class).invoke(null, handle, wOut, hOut);
    }

    private void glfwGetFramebufferSize(long handle, int[] wOut, int[] hOut) throws Exception {
        glfw("glfwGetFramebufferSize", long.class, int[].class, int[].class).invoke(null, handle, wOut, hOut);
    }
}
