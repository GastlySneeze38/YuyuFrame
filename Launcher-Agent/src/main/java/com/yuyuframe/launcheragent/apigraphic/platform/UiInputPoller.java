package com.yuyuframe.launcheragent.apigraphic.platform;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.HashMap;
import java.util.Map;

/**
 * État souris/clavier pollé chaque frame depuis un Mixin global sur le render
 * loop — pour la position/le survol/le drag continu (voir UiDrawable). Le
 * CLIC et la touche Échap eux-mêmes ne sont plus dispatchés depuis ce sondage
 * (voir historique de session) : {@code UiScreenBase} override désormais les
 * vraies méthodes {@code mouseClicked}/{@code keyPressed} du Screen vanilla,
 * pour éviter qu'un même clic/une même touche soit traité deux fois (une
 * fois par le vrai dispatch de Minecraft, une fois par ce sondage) — source
 * de plusieurs bugs (dont un crash) avant ce changement.
 *
 * Deux implémentations, une par famille LWJGL — choisie par le Mixin global
 * de CHAQUE version (GlobalUiRenderMixin en 1.21+, son équivalent v1_8 pour
 * 1.8.9), jamais de branchement runtime ici :
 *   - {@link UiInputPollerModern} : LWJGL3/GLFW (1.13+, dont 1.21)
 *   - {@link UiInputPollerLegacy} : LWJGL2 org.lwjgl.input.Mouse/Keyboard (1.8.9)
 *
 * Coordonnées Y — PAS symétriques entre les deux implémentations, mais
 * chacune normalise déjà vers l'espace pixels FRAMEBUFFER origine bas-gauche
 * (mêmes unités que gl_FragCoord en GLSL, voir UiRenderer) :
 *   - LWJGL2 Mouse.getY() : déjà dans cet espace nativement, aucune conversion.
 *   - LWJGL3 glfwGetCursorPos() : coordonnées "fenêtre" origine haut-gauche —
 *     UiInputPollerModern applique la mise à l'échelle fenêtre→framebuffer
 *     (écrans HiDPI) PUIS le flip Y, voir son readState().
 */
public abstract class UiInputPoller {

    public double mouseX, mouseY;
    /** Taille framebuffer courante (mêmes unités que mouseX/Y et gl_FragCoord) — pour layout plein écran. */
    public int fbWidth, fbHeight;
    public boolean leftDown, rightDown;
    protected boolean prevLeftDown, prevRightDown;
    public boolean leftClicked, rightClicked; // "juste pressé cette frame"

    /**
     * Roadmap Phase 5.6 (carence input : "seuls les index GLFW 0/1 sont
     * lus") — clic milieu (molette cliquée, index GLFW 2) + boutons
     * latéraux souris gaming (index GLFW 3-7, mouse4-mouse8), même motif
     * down/clicked que {@link #leftDown}/{@link #leftClicked}. Bracket
     * moderne (GLFW) uniquement — LWJGL2/1.8.9 n'expose que 2 boutons via
     * {@code org.lwjgl.input.Mouse.isButtonDown}, jamais renseignés côté
     * {@code UiInputPollerLegacy} (restent {@code false} en permanence là-bas,
     * pas un bug — ce bracket n'a simplement pas ces boutons).
     */
    public boolean middleDown, middleClicked;
    protected boolean prevMiddleDown;
    /** Index 0..4 = boutons GLFW 3..7 (mouse4..mouse8). */
    public final boolean[] sideButtonDown = new boolean[5];
    public final boolean[] sideButtonClicked = new boolean[5];
    protected final boolean[] prevSideButtonDown = new boolean[5];
    /** État brut Maj (gauche OU droite), renseigné par {@link #readState()} à chaque frame — voir {@code UiScreenBase} (raccourcis Maj+clic). PAS le même champ que editShiftHeld (celui-ci ne se met à jour que quand pollTextEdit() est appelé, c-à-d un UiTextField focus). */
    public boolean shiftDown;

    /**
     * Nom de la touche d'ouverture du menu — même format que
     * {@link #pollAnyKeyJustPressed()} ("RSHIFT", "F1"...), lu par
     * {@link #readMenuKeyDown()} dans chaque implémentation. STATIC (pas un
     * champ d'instance) : GlobalUiSettings (runtime.ui) l'écrit
     * directement, sans avoir besoin d'une référence vers l'instance active
     * (créée tardivement et paresseusement par le Mixin global).
     */
    public static volatile String menuKeyName = "RSHIFT";

    /**
     * Vrai quand un {@code UiTextField} a le focus quelque part sur l'écran
     * custom courant — STATIC (même motif que {@link #menuKeyName}) : le
     * callback caractère GLFW ({@code UiInputPollerModern.registerCharCallback})
     * est enregistré UNE FOIS pour toute la session, dès la toute première
     * frame du jeu, bien avant qu'aucun écran custom n'existe — il n'a aucun
     * moyen propre d'obtenir une référence vers le champ de texte actif
     * autrement. Mis à jour par {@code UiTextField.setFocused} (seul point
     * d'entrée du focus, voir sa javadoc) ET remis à {@code false} par
     * {@code UiScreenBase.closeTo} (filet de sécurité si un écran se ferme
     * pendant qu'un champ était encore focus, ex: clic sur "Retour" sans
     * avoir d'abord perdu le focus). BUG TROUVÉ sans ce flag : le callback
     * bufferisait INCONDITIONNELLEMENT tout caractère tapé n'importe quand
     * (jeu normal, chat...), qui ressortait d'un coup dans le prochain champ
     * de recherche ouvert — voir javadoc de registerCharCallback.
     */
    public static volatile boolean textInputActive = false;

    /** Touche d'ouverture du menu (voir {@link #menuKeyName}) — utilisable même sans écran ouvert, voir readMenuKeyDown(). */
    public boolean menuKeyDown, menuKeyPressed;
    private boolean prevMenuKeyDown;

    /** Delta de molette de cette frame (positif = vers le haut) — voir readScrollDelta(). */
    public int scrollDelta;

    /**
     * Retour utilisateur (ZoomModule) : scroller pour zoomer plus loin
     * changeait AUSSI l'objet en main dans la hotbar en même temps — le
     * callback GLFW natif rechaîné vers celui de vanilla (voir
     * {@code UiInputPollerModern.registerScrollCallback}) transmet
     * TOUJOURS l'événement, donc {@code MouseHandler.onScroll} vanilla le
     * traite en parallèle de notre propre lecture. Drapeau STATIC (même
     * motif que {@link #menuKeyName}/{@link #textInputActive}) : un module
     * qui consomme le scroll pour son propre usage (ex: ZoomModule pendant
     * qu'on maintient la touche) le met à {@code true} le temps de sa
     * fenêtre d'usage — {@code false} en permanence sinon, pour ne jamais
     * casser le scroll vanilla (hotbar, longue-vue...) le reste du temps.
     */
    public static volatile boolean suppressVanillaScroll = false;

    /**
     * Implémentation actuellement active (Legacy ou Modern selon le
     * bracket) — n'importe quel module peut y accéder sans savoir lequel
     * des deux tourne, voir {@link #drainTickScroll()} pour le cas d'usage
     * qui a motivé son ajout.
     */
    public static volatile UiInputPoller ACTIVE;

    private int tickScrollAccum;

    /** À appeler une fois par frame, avant de lire mouseX/mouseY/leftClicked/etc. */
    public final void poll() {
        try {
            ACTIVE = this;
            prevLeftDown = leftDown;
            prevRightDown = rightDown;
            prevMiddleDown = middleDown;
            for (int i = 0; i < sideButtonDown.length; i++) prevSideButtonDown[i] = sideButtonDown[i];
            prevMenuKeyDown = menuKeyDown;
            readState();
            leftClicked = leftDown && !prevLeftDown;
            rightClicked = rightDown && !prevRightDown;
            middleClicked = middleDown && !prevMiddleDown;
            for (int i = 0; i < sideButtonDown.length; i++) sideButtonClicked[i] = sideButtonDown[i] && !prevSideButtonDown[i];
            menuKeyDown = readMenuKeyDown();
            menuKeyPressed = menuKeyDown && !prevMenuKeyDown;
            scrollDelta = readScrollDelta();
            tickScrollAccum += scrollDelta;
        } catch (Throwable t) {
            LauncherLog.err("[UiInputPoller] poll: " + t);
        }
    }

    /**
     * BUG TROUVÉ (ZoomModule — "la molette ne marche pas") : {@link #poll()}
     * tourne une fois par FRAME (accroché sur le rendu, voir
     * GlobalUiRenderMixin261/189/etc.), donc {@link #scrollDelta} y est
     * remis à zéro à CHAQUE frame. Un module cadencé au TICK JEU (20/s,
     * largement moins fréquent que les frames dès que le FPS dépasse ~20)
     * qui lit directement ce champ arrive presque toujours APRÈS qu'une
     * frame ultérieure ait déjà écrasé la vraie valeur à 0 — un cran de
     * molette donné entre deux ticks était donc perdu la quasi-totalité du
     * temps (c'est exactement ce qui rendait le zoom à la molette
     * inopérant). {@link #tickScrollAccum} accumule au contraire CHAQUE
     * frame sans jamais se remettre à zéro tout seul — cette méthode le lit
     * ET le vide en une seule opération, à appeler UNE FOIS PAR TICK par un
     * consommateur cadencé au tick plutôt que de lire {@link #scrollDelta}
     * (réservé aux consommateurs cadencés à la frame, ex: un écran custom
     * ouvert).
     */
    public final int drainTickScroll() {
        int v = tickScrollAccum;
        tickScrollAccum = 0;
        return v;
    }

    /** Doit renseigner mouseX/mouseY/fbWidth/fbHeight/leftDown/rightDown pour la frame courante. */
    protected abstract void readState() throws Exception;

    /** État courant de la touche Right Shift — chaque implémentation utilise sa propre constante native. */
    protected abstract boolean readMenuKeyDown() throws Exception;

    /** Delta de molette depuis le dernier poll() — chaque implémentation gère sa propre source (event/callback). */
    protected abstract int readScrollDelta() throws Exception;

    /**
     * À appeler UNIQUEMENT quand un widget est en mode "capture" (UiKeybindButton
     * en attente d'une touche) — PAS chaque frame inconditionnellement, pour ne
     * jamais interférer avec le jeu quand aucun widget n'écoute. Renvoie le nom
     * de la première touche pressée cette frame (ex: "A", "F1", "SPACE"),
     * null si aucune. Le nom sert de format d'échange indépendant de la version
     * (les codes numériques LWJGL2/GLFW ne coïncident pas d'une version à l'autre).
     */
    public abstract String pollAnyKeyJustPressed();

    /**
     * Intentions d'édition d'un {@code UiTextField} focus pour LA FRAME
     * COURANTE — champs publics directement sur {@code UiInputPoller} (PAS un
     * type imbriqué dédié : un premier jet avec une classe {@code
     * TextEditFrame} causait un {@code LinkageError: loader constraint
     * violation} en jeu sous Fabric — "knot" (classloader du jeu, utilisé
     * pour tisser Mixin) et "app" (classloader normal de launcher-agent.jar)
     * chargeaient CHACUN leur propre copie de ce type, vues comme deux
     * classes incompatibles par la JVM dès qu'une valeur créée par l'un
     * traversait vers du code vérifié par l'autre — AUCUN écran ne pouvait
     * plus taper le moindre caractère. {@code String}/{@code boolean} (comme
     * ici) sont chargés par le classloader BOOTSTRAP, partagé par les deux,
     * donc jamais sujets à ce problème — même raison que {@link #mouseX}/
     * {@link #leftDown} déjà exposés en champs plats sur cette même classe
     * plutôt qu'un objet "InputState" dédié). AUCUNE logique de
     * curseur/sélection/texte ici — tout ça vit dans UiTextField, seul
     * endroit qui connaît le contenu actuel et la position du curseur.
     * Chaque champ "action" est déjà débruité par l'implémentation
     * (répétition typematic pour backspace/delete/flèches via
     * {@link #keyRepeatFire}, un seul {@code true} par appui réel pour le
     * reste) — {@code UiTextField} n'a jamais à gérer de front montant.
     */
    public String editTyped = "";
    public boolean editBackspace, editDelete;
    public boolean editLeft, editRight, editHome, editEnd;
    public boolean editEnter;
    public boolean editSelectAll, editCopy, editCut, editPaste;
    public boolean editShiftHeld;

    // Répétition typematic (Backspace/Suppr/flèches maintenus) — délai initial
    // avant la première répétition, puis cadence régulière, exactement le
    // motif standard des champs de texte OS (pas de dépendance à un système
    // "repeat events" LWJGL2/GLFW natif, qui n'existe pas de façon uniforme
    // entre les deux : implémenté ICI, dans la classe ABSTRAITE, pour que
    // Legacy et Modern partagent la même temporisation sans dupliquer la
    // logique — chaque implémentation lui fournit juste l'état brut "touche
    // enfoncée cette frame" via {@link #keyRepeatFire}.
    private static final long REPEAT_INITIAL_DELAY_MS = 400L;
    private static final long REPEAT_INTERVAL_MS = 40L;
    private final Map<String, long[]> keyRepeatState = new HashMap<>();

    /**
     * Motif "maintenir pour répéter" — {@code true} à l'appui initial, PUIS
     * de façon répétée après {@link #REPEAT_INITIAL_DELAY_MS}, tant que
     * {@code down} reste {@code true}. {@code key} identifie la touche
     * logique (ex: "backspace") — namespacé par l'appelant si plusieurs
     * touches partagent cette map (voir UiInputPollerLegacy/Modern).
     */
    protected final boolean keyRepeatFire(String key, boolean down) {
        long now = System.currentTimeMillis();
        if (!down) {
            keyRepeatState.remove(key);
            return false;
        }
        long[] state = keyRepeatState.get(key);
        if (state == null) {
            keyRepeatState.put(key, new long[]{ now, now });
            return true; // premier appui
        }
        if (now - state[0] < REPEAT_INITIAL_DELAY_MS) return false;
        if (now - state[1] >= REPEAT_INTERVAL_MS) {
            state[1] = now;
            return true;
        }
        return false;
    }

    /**
     * À appeler UNIQUEMENT quand un UiTextField a le focus — renseigne
     * TOUS les champs {@code edit*} ci-dessus en une seule passe sur
     * l'état/la file clavier de cette frame. Sur LWJGL2 (Legacy), plusieurs
     * de ces intentions partagent la MÊME file d'événements consommable
     * (Keyboard.next()) — toutes doivent donc être lues ICI, jamais
     * réparties sur plusieurs appels (voir historique : Backspace capturé
     * deux fois par pollAnyKeyJustPressed + ceci cassait déjà l'un des deux
     * avant ce regroupement).
     */
    public abstract void pollTextEdit();
}
