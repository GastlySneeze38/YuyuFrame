package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.runtime.game.GameOptions;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;

import com.yuyuframe.launcheragent.runtime.game.ClientData;

/**
 * Zoom façon Essential Mod/Zoomify — touche maintenue réduit le FOV vers un
 * niveau de BASE, PUIS la molette (tant que la touche reste maintenue) zoome
 * ENCORE PLUS (jamais moins que la base — relâcher la touche est le seul moyen
 * de dézoomer complètement), relâchée restaure la valeur d'avant. Vanilla
 * 1.8.9 n'a pas de KeyBinding "zoom" dédié (contrairement à sneak/sprint, voir
 * MixinToggleSneak189/MixinToggleSprint189) donc pas de champ GameOptions à
 * rediriger — la touche est pollée directement via org.lwjgl.input.Keyboard
 * (API publique LWJGL2, pas obfusquée), même mécanisme que
 * UiInputPollerLegacy.readMenuKeyDown pour une touche configurable.
 *
 * Coopère avec FovModule (voir ModuleRegistry, enregistré JUSTE APRÈS lui pour
 * que tickAll() applique le zoom EN DERNIER) : {@code savedFov} capture la
 * valeur DÉJÀ EN PLACE au moment où la touche est pressée (celle que FovModule
 * ou vanilla ont posée cette même frame), pas une valeur vanilla figée à
 * l'avance — sinon activer/désactiver FovModule pendant un zoom, ou un
 * changement de fovValue en cours de route, restaurerait la mauvaise valeur.
 *
 * REFONTE (étudié github.com/isXander/Zoomify pour comprendre son approche,
 * demandé explicitement par l'utilisateur — 3 griefs distincts sur la version
 * précédente) :
 *
 * 1) "la molette ne marche pas" — BUG RÉEL trouvé : {@link
 *    UiInputPollerModern}/{@code UiInputPollerLegacy} drainent leur delta de
 *    molette une fois par FRAME (voir {@code UiInputPoller.poll()}, accroché
 *    sur le rendu), alors que ce module ne le lisait qu'une fois par TICK JEU
 *    (20/s, beaucoup plus rare que les frames dès que le FPS dépasse ~20) — un
 *    cran de molette donné entre deux ticks était presque toujours déjà
 *    écrasé à 0 par une frame ultérieure avant que ce module ait la moindre
 *    chance de le lire. Fix : {@link UiInputPoller#drainTickScroll()}, un
 *    compteur séparé qui ACCUMULE à chaque frame sans jamais se vider tout
 *    seul, drainé ici une fois par tick.
 *
 * 2) "pas de transition douce" — Zoomify ne touche JAMAIS directement
 *    GameOptions.fov : il intercepte Camera.calculateFov() par Mixin et
 *    divise le résultat par un diviseur recalculé CHAQUE FRAME via un système
 *    d'interpolation (partialTicks inclus, donc lissé même au-delà de 20/s).
 *    Reproduire cette architecture demanderait un nouveau Mixin par bracket
 *    de version supporté (5 dans ce projet) rien que pour cette fonctionnalité
 *    — hors de proportion avec la demande. Choix : garder l'architecture
 *    existante (écriture directe dans GameOptions.fov, cadencée au tick) mais
 *    lisser la VALEUR ÉCRITE elle-même via une interpolation linéaire dans le
 *    temps ({@link #transitionSeconds}) — moins fin qu'un vrai lissage par
 *    frame (plafonné à 20 pas/seconde), mais aucun nouveau Mixin, donc aucun
 *    risque de régression sur les autres brackets.
 *
 * 3) "sensibilité souris pas adaptée" — vanilla ne compense PAS la
 *    sensibilité de rotation caméra en fonction du FOV : à sensibilité égale,
 *    le même mouvement de souris fait tourner la caméra du même nombre de
 *    DEGRÉS que sans zoom, ce qui semble beaucoup plus rapide à l'écran une
 *    fois zoomé (le FOV réduit fait que ces degrés couvrent une plus grande
 *    fraction de l'écran). Zoomify corrige ça par Mixin sur la logique de
 *    rotation caméra (MouseHandler.turnPlayer) — encore un nouveau Mixin par
 *    bracket. Reproduit ici SANS Mixin : {@code GameOptions.sensitivity} est
 *    LUI AUSSI un {@code SimpleOption}/{@code OptionInstance} exactement
 *    comme {@code fov} (même mécanisme déjà en place, voir {@link
 *    McReflect#simpleOptionGetValue}/{@link McReflect#simpleOptionSetValue})
 *    — sauvegardé à l'entrée en zoom, réduit proportionnellement au niveau de
 *    zoom courant à chaque tick, restauré exactement à la sortie. Puisque
 *    c'est le RÉGLAGE réel qui est réduit (pas un Mixin sur le calcul de
 *    rotation), tout ce qui lit la sensibilité vanilla en profite
 *    automatiquement, sans dépendre du bracket de version.
 */
public final class ZoomModule extends LauncherModule {

    public String zoomKey = "C";

    public float zoomFov = 20f;

    @Override
    protected void settings(SettingList s) {
        s.keybind("zoomKey", "Touche de zoom", "Réglages", () -> zoomKey, v -> zoomKey = v);
        s.slider("zoomFov", "FOV en zoom (base)",
            "Niveau de zoom appliqué à l'appui sur la touche — voir \"FOV en zoom max\" pour la limite atteignable en scrollant.",
            "Réglages", 5f, 60f, 1f, null, () -> zoomFov, v -> zoomFov = v);
        s.slider("zoomFovMin", "FOV en zoom max",
            "Limite la plus zoomée atteignable en scrollant pendant le zoom (molette vers le haut = zoome plus).",
            "Réglages", 1f, 60f, 1f, null, () -> zoomFovMin, v -> zoomFovMin = v);
        s.slider("zoomScrollStep", "Pas de zoom (molette)",
            "Variation de FOV par cran de molette pendant le zoom.",
            "Réglages", 0.5f, 10f, 0.5f, null, () -> zoomScrollStep, v -> zoomScrollStep = v);
        s.slider("transitionSeconds", "Durée de transition (s)",
            "Temps pour atteindre le niveau de zoom cible en douceur, à l'appui comme au relâchement — 0 = instantané (comportement d'origine).",
            "Réglages", 0f, 1f, 0.05f, null, () -> transitionSeconds, v -> transitionSeconds = v);
        s.slider("sensitivityCompensation", "Réduction de la sensibilité en zoom (%)",
            "Ralentit la rotation de la caméra selon le niveau de zoom, sur une courbe qui suit ce que l'écran montre réellement : 100% = un mouvement de souris couvre la même portion d'écran zoomé ou non, au-delà = la réduction se creuse aux forts zooms, en dessous = plus douce, 0% = sensibilité inchangée.",
            "Réglages", 0f, 200f, 5f, null, () -> sensitivityCompensation, v -> sensitivityCompensation = v);
        s.dropdown("sensitivityCurve", "Courbe de réduction",
            "Exponentielle = suit ce que l'écran montre : peu de réduction aux zooms légers, très forte aux zooms forts. Linéaire = réduction régulière dès le début du zoom, qui plafonne aux zooms forts (comportement d'origine).",
            // Pas de condition d'activation : une liste déroulante ne sait pas
            // se griser (ConfigScreenBuilder journalise une erreur sinon).
            "Réglages", new String[]{ "Exponentielle", "Linéaire" }, null,
            () -> sensitivityCurve, v -> sensitivityCurve = v);
    }

    /** Courbe de {@link #applySensitivityScale} : {@link #CURVE_EXPONENTIAL} (défaut) ou {@link #CURVE_LINEAR}. */
    public int sensitivityCurve = CURVE_EXPONENTIAL;
    private static final int CURVE_EXPONENTIAL = 0;
    private static final int CURVE_LINEAR = 1;

    // Essential-style : scroller PENDANT le zoom va encore plus loin que la
    // base (jamais en-deçà, voir javadoc de classe) — demandé explicitement
    // par l'utilisateur. scrollOffsetFov REMIS À ZÉRO à chaque NOUVEL appui
    // sur la touche (pas conservé d'une session de zoom à l'autre) : plus
    // simple à comprendre ("toujours pareil au prochain appui") qu'un état
    // caché qui persiste silencieusement.
    public float zoomFovMin = 5f;

    public float zoomScrollStep = 2f;

    public float transitionSeconds = 0.15f;

    public float sensitivityCompensation = 100f;

    private String cachedKeyName;
    private int cachedKeyCode = -1;

    private boolean zooming;
    /** Englobe {@link #zooming} ET la transition de sortie encore en cours après relâchement — voir onTick(). */
    private boolean active;
    private double savedFov = -1;
    private double effectiveFov = -1;
    /** Écart total base↔cible au moment de l'appui — sert de référence pour que {@link #transitionSeconds} corresponde à un temps CONSTANT, pas une vitesse en FOV/s. */
    private double transitionRange = 1;
    /** >= 0 — combien on a scrollé AU-DELÀ de la base cette session de zoom (voir javadoc de classe). Remis à 0 à chaque nouvel appui. */
    private double scrollOffsetFov = 0;
    private double savedSensitivity = -1;

    public ZoomModule() {
        super("zoom", "Zoom", "Maintenir une touche zoome en douceur, scroller pendant le zoom pour aller plus loin (façon Zoomify)", false);
        // Sorti du groupe "Confort visuel" le 2026-08-30 (demande explicite),
        // comme le Freelook juste avant : il a sa propre carte, donc il lui
        // faut une icône — sans elle, ModCard retomberait sur la
        // pastille-lettre. Nom vérifié (HTTP 200, image/png) selon la
        // convention de LauncherModule#icons8.
        iconUrl = icons8("binoculars");
    }

    @Override
    public void onTick() {
        try {
            // BUG TROUVÉ #4 (log en jeu : raw=-129/-24/5 à l'engagement du
            // zoom — impossible pour un seul cran physique) : drainé plus
            // bas, APRÈS "if (!active) return;", ce compteur n'était vidé
            // QUE pendant qu'on zoomait déjà — tout le scroll du jeu normal
            // (changement d'objet en main, coffres...) s'accumulait donc
            // indéfiniment dans UiInputPoller.tickScrollAccum jusqu'à la
            // PROCHAINE pression de la touche, où tout le backlog tombait
            // d'un coup dans scrollOffsetFov — pouvant le pousser bien
            // au-delà de maxOffset de façon invisible (rien n'affiche cette
            // valeur brute). Une fois saturé ainsi, scroller vers le haut ne
            // fait plus rien (déjà au max) et scroller vers le bas doit
            // d'abord rattraper ce surplus invisible avant le moindre effet
            // visible — d'où "le scroll ne marche pas" alors que le calcul
            // lui-même (confirmé par le diag ci-dessous) était correct. Fix :
            // drainer INCONDITIONNELLEMENT à chaque tick, backlog jeté quand
            // on ne zoome pas encore.
            int scroll = readScrollDelta();

            boolean down = isZoomKeyDown();
            Object fovHandle = fovHandle();
            Object options = optionsInstance();
            if (fovHandle == null || options == null) return;

            if (down && !zooming) {
                zooming = true;
                scrollOffsetFov = 0;
                if (!active) {
                    active = true;
                    savedFov = readOptionValue(fovHandle, options);
                    effectiveFov = savedFov;
                    transitionRange = Math.max(1.0, Math.abs(savedFov - zoomFov));
                    // Repart d'une horloge neuve : sans ça, le premier pas
                    // mesurerait le temps écoulé depuis le zoom PRÉCÉDENT
                    // (voir stepToward, où un dt énorme est ramené à 0,25 s —
                    // soit une transition déjà terminée à l'appui).
                    lastStepNanos = 0L;
                    saveSensitivity(options);
                }
            } else if (!down && zooming) {
                zooming = false;
                scrollOffsetFov = 0;
            }

            // Retour utilisateur : scroller pendant le zoom changeait AUSSI
            // l'objet en main dans la hotbar — voir javadoc de
            // UiInputPoller#suppressVanillaScroll. Suit directement l'état
            // "zooming" (pas "active", qui reste vrai pendant la transition
            // de sortie où on ne veut plus rien supprimer).
            UiInputPoller.suppressVanillaScroll = zooming;

            if (!active) return;

            double maxOffset = Math.max(0, zoomFov - zoomFovMin);
            if (zooming && scroll != 0) {
                // Molette vers le haut (delta > 0) = zoome PLUS = FOV plus
                // petit — scrollOffsetFov reste dans [0, maxOffset], jamais
                // négatif ni au-delà du max (voir BUG TROUVÉ #4 plus haut :
                // le clamp au-delà de maxOffset se faisait avant seulement à
                // la LECTURE dans targetFov, jamais sur la valeur stockée —
                // un dépassement restait possible et invisible, nécessitant
                // plusieurs crans "dans le vide" avant tout effet visible).
                scrollOffsetFov = Math.max(0, Math.min(maxOffset, scrollOffsetFov + scroll * zoomScrollStep));
            }

            double targetFov = zooming ? (zoomFov - scrollOffsetFov) : savedFov;

            effectiveFov = stepToward(effectiveFov, targetFov);
            writeOptionValue(fovHandle, options, effectiveFov);
            applySensitivityScale(options);

            // Transition de sortie terminée (touche relâchée ET la valeur
            // lissée a rejoint la valeur d'origine) : restauration EXACTE
            // (élimine toute erreur d'arrondi accumulée par le lissage) et
            // libération de l'état, pour qu'un futur appui reparte propre.
            if (!zooming && Math.abs(effectiveFov - savedFov) < 0.05) {
                writeOptionValue(fovHandle, options, savedFov);
                restoreSensitivity(options);
                active = false;
                savedFov = -1;
                effectiveFov = -1;
            }
        } catch (Throwable t) {
            LauncherLog.err("[ZoomModule] onTick: " + t);
        }
    }

    /**
     * Interpolation linéaire dans le TEMPS (pas juste "un pourcentage de
     * l'écart restant par appel", qui donnerait une vitesse variable) — un
     * pas proportionnel au temps RÉELLEMENT écoulé, pour que {@link
     * #transitionSeconds} corresponde à la durée réelle du parcours de {@link
     * #transitionRange} (l'écart base↔cible mesuré à l'entrée en zoom).
     * {@code transitionSeconds == 0} conserve le comportement d'origine
     * (saut instantané).
     *
     * <p>BUG TROUVÉ (2026-09-01, même famille que le clignotement d'AppleSkin
     * dans {@code SaturationModule}) : {@code ModuleRegistry.tickAll()} est
     * appelé une fois par FRAME RENDUE, pas une fois par tick de jeu — voir
     * {@code GlobalUiRenderMixin261}. Le pas fixe {@code range / (secondes ×
     * 20)} supposait 20 appels par seconde : à 130 FPS la transition
     * s'achevait 6,5 fois trop vite, et sa durée changeait avec le FPS. Le
     * réglage affiché en secondes ne correspondait donc à aucune durée réelle.
     * Mesuré ici sur l'horloge, la durée est celle annoncée quel que soit le
     * FPS.
     */
    private double stepToward(double current, double target) {
        if (transitionSeconds <= 0f) return target;
        long now = System.nanoTime();
        double dt = lastStepNanos == 0L ? 0.0 : (now - lastStepNanos) / 1_000_000_000.0;
        lastStepNanos = now;
        // Premier appel d'une session de zoom : pas d'écart mesurable, on ne
        // bouge pas encore (la frame suivante donnera un dt réel).
        if (dt <= 0.0) return current;
        // Borne haute : après une pause (fenêtre en arrière-plan, chargement
        // de monde), dt peut valoir plusieurs secondes et téléporterait le
        // zoom d'un coup — ce qui est justement ce que la transition évite.
        if (dt > 0.25) dt = 0.25;
        double maxStep = transitionRange * dt / transitionSeconds;
        double diff = target - current;
        if (Math.abs(diff) <= maxStep) return target;
        return current + Math.signum(diff) * maxStep;
    }

    /** Horodatage du dernier {@link #stepToward} — voir sa javadoc. */
    private long lastStepNanos;

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        if (active && savedFov >= 0) {
            try {
                Object fovHandle = fovHandle();
                Object options = optionsInstance();
                if (fovHandle != null && options != null) {
                    writeOptionValue(fovHandle, options, savedFov);
                    restoreSensitivity(options);
                }
            } catch (Throwable ignored) {
            }
        }
        zooming = false;
        active = false;
        savedFov = -1;
        effectiveFov = -1;
        scrollOffsetFov = 0;
        lastStepNanos = 0L;
        UiInputPoller.suppressVanillaScroll = false;
    }

    /**
     * BUG TROUVÉ ("la molette ne marche pas") : {@code UiInputPoller.poll()}
     * tourne une fois par FRAME (voir GlobalUiRenderMixin) et y remettait à
     * zéro le delta de molette à CHAQUE frame — ce module, cadencé au TICK
     * JEU (20/s, beaucoup plus rare que les frames), lisait donc presque
     * toujours 0 : la vraie valeur avait déjà été écrasée par une frame
     * ultérieure avant même d'être lue ici. Fix : {@link
     * UiInputPoller#drainTickScroll()}, un compteur séparé qui accumule
     * plutôt que de se remettre à zéro tout seul à chaque frame — fonctionne
     * identiquement sur Legacy (LWJGL2) et Modern (GLFW), les deux passant
     * par la même classe de base.
     */
    private int readScrollDelta() {
        UiInputPoller poller = UiInputPoller.ACTIVE;
        return poller != null ? poller.drainTickScroll() : 0;
    }

    /**
     * BUG TROUVÉ (audit modules, voir historique de session) : {@code
     * GameOptions.fov} est un {@code float} en 1.8.9 mais un {@code double}
     * en 1.13-1.16.5 (vérifié dans les mappings 1.16.5 : {@code f D aO
     * field_1826 fov}) — {@code getFloat}/{@code setFloat} lève {@code
     * IllegalArgumentException} sur ce dernier, avalée silencieusement,
     * zoom totalement inopérant. Lit le VRAI type du champ au lieu de
     * supposer, fonctionne sur les trois.
     *
     * BUG TROUVÉ #2 (1.20.4, test utilisateur) : depuis la refonte
     * "SimpleOption" (~1.19-1.20), {@code fov} n'est même plus un float/double
     * DU TOUT — c'est un objet {@code SimpleOption} FINAL (vérifié : {@code f
     * Levl; bM field_1826 fov}, et sur 26.1.2 réel {@code OptionInstance<
     * Integer>}, confirmé par javap) — voir {@link
     * McReflect#simpleOptionGetValue}/{@link McReflect#simpleOptionSetValue}
     * pour le repli, qui gère déjà le boxing Integer/Float/Double.
     *
     * Généralisé (ex-readFov/writeFov) pour aussi servir à {@code
     * sensitivity} (même famille d'objet sur 26.1.2 : {@code OptionInstance<
     * Double>}, confirmé par javap) — voir applySensitivityScale().
     *
     * {@code handle} est une poignée d'option OPAQUE (voir {@link
     * #fovHandle()}/{@link #sensitivityHandle}) — le repli {@code Field}
     * multi-bracket a été supprimé le 2026-08-27. Le champ {@code .value} de
     * {@code OptionInstance} est écrit DIRECTEMENT via l'accessor, jamais via
     * {@code setValue()} : ce dernier déclenche la validation vanilla, qui
     * clampe fov et sensibilité.
     */
    private double readOptionValue(Object handle, Object options) throws Exception {
        return GameOptions.value(handle, 0d);
    }

    private void writeOptionValue(Object handle, Object options, double value) throws Exception {
        // Le boxing d'après le type de la valeur COURANTE (fov =
        // OptionInstance<Integer>, sensitivity = OptionInstance<Double>) vit
        // désormais dans GameOptions — cette règle était recopiée à
        // l'identique ici, dans FovModule et dans FullbrightModule.
        GameOptions.setValue(handle, value);
    }

    /**
     * Réduit la vitesse de rotation caméra selon le niveau de zoom COURANT
     * (suit {@link #effectiveFov}, donc la réduction s'installe/se retire EN
     * DOUCEUR avec le FOV) — voir javadoc de classe § 3 pour le pourquoi.
     *
     * <h2>Refonte du 2026-09-13 — deux erreurs dans l'ancienne courbe</h2>
     *
     * Retour utilisateur : « on suit une courbe linéaire, il faudrait une
     * courbe exponentielle ». L'ancien calcul prenait
     * {@code 1 − (1 − fov/fovBase) × compensation} et multipliait la VALEUR
     * de l'option par ce facteur. Deux fautes :
     * <ol>
     *   <li><b>Linéaire en FOV.</b> Ce qu'un mouvement de souris fait parcourir
     *       à l'ÉCRAN dépend de {@code tan(fov/2)}, pas du FOV : de 90° à 20°
     *       le rapport vrai est 0,176, le linéaire donnait 0,222 — la caméra
     *       restait trop rapide, d'autant plus que le zoom est fort.</li>
     *   <li><b>Le jeu cube la sensibilité.</b> Relu dans le bytecode
     *       ({@code MouseHandler.turnPlayer} 26.1.2, {@code Mouse}
     *       {@code class_312} 1.21.11) : rotation = souris × {@code 8 × d³}
     *       avec {@code d = 0,6 × sensibilité + 0,2}. Réduire l'option de
     *       moitié ne réduit donc pas la rotation de moitié, et le {@code + 0,2}
     *       empêchait de descendre sous (0,2 / d)³ — 6,4 % de la vitesse à la
     *       sensibilité par défaut, quel que soit le curseur.</li>
     * </ol>
     *
     * <h2>Nouveau calcul</h2>
     *
     * On vise directement le MULTIPLICATEUR DE ROTATION :
     * {@code m = (tan(fov/2) / tan(fovBase/2)) ^ (compensation / 100)}.
     * À 100 %, un mouvement de souris couvre la même fraction d'écran zoomé
     * ou non ; au-dessus, l'exposant creuse la courbe (plus lent aux forts
     * zooms, très peu aux faibles) ; en dessous, il l'aplatit. Puis on inverse
     * la formule du jeu pour trouver la valeur d'option qui produit {@code m} :
     * {@code d' = ∛m × d}, {@code sensibilité' = (d' − 0,2) / 0,6}.
     *
     * <p>La valeur écrite peut être NÉGATIVE (jusqu'à −1/3 exclu) : c'est ce
     * qui lève le plancher de 6,4 %. Écrite directement dans l'option, sans la
     * validation vanilla (voir {@link #writeOptionValue}), et restaurée à la
     * sortie du zoom ; le zoom ne tourne jamais écran ouvert, donc jamais
     * pendant que le jeu sauvegarde ses options.
     */
    private void applySensitivityScale(Object options) {
        if (savedSensitivity < 0 || sensitivityCompensation <= 0f || savedFov <= 0) return;
        try {
            Object handle = sensitivityHandle(options);
            if (handle == null) return;
            if (sensitivityCurve == CURVE_LINEAR) {
                writeOptionValue(handle, options, savedSensitivity * linearOptionScale());
                return;
            }
            double screenRatio = Math.tan(Math.toRadians(effectiveFov) / 2.0)
                / Math.tan(Math.toRadians(savedFov) / 2.0);
            screenRatio = Math.max(0.0, Math.min(1.0, screenRatio));
            double multiplier = Math.pow(screenRatio, sensitivityCompensation / 100.0);
            // Plancher : très lent, jamais figé (0 rendrait la visée
            // impossible) — et d' reste positif, donc jamais inversé.
            multiplier = Math.max(MIN_TURN_MULTIPLIER, Math.min(1.0, multiplier));

            double d = savedSensitivity * SENS_MUL + SENS_ADD;
            double zoomedD = Math.cbrt(multiplier) * d;
            writeOptionValue(handle, options, (zoomedD - SENS_ADD) / SENS_MUL);
        } catch (Throwable t) {
            LauncherLog.err("[ZoomModule] applySensitivityScale: " + t);
        }
    }

    /**
     * Courbe LINÉAIRE — l'ancien calcul, conservé À L'IDENTIQUE à la demande
     * de l'utilisateur (2026-09-13, « ce n'est pas du tout la même
     * sensation ») : facteur {@code 1 − (1 − fov/fovBase) × compensation}
     * appliqué à la VALEUR de l'option. Ses « défauts » décrits plus haut
     * (linéaire en FOV, passé au cube par le jeu, plancher vers 7 %) font
     * précisément son ressenti : forte réduction dès le début du zoom, qui
     * plafonne ensuite. Le corriger en ferait une troisième courbe.
     */
    private double linearOptionScale() {
        double ratio = Math.min(1.0, effectiveFov / savedFov);
        double scale = 1.0 - (1.0 - ratio) * (sensitivityCompensation / 100.0);
        // Au-delà de 100 %, l'expression passe par zéro puis devient négative
        // (caméra inversée) : plancher à 2 % de la valeur d'origine.
        return Math.max(0.02, Math.min(1.0, scale));
    }

    /** {@code d = sensibilité × 0,6 + 0,2} — constantes du jeu, voir {@link #applySensitivityScale}. */
    private static final double SENS_MUL = 0.6000000238418579;
    private static final double SENS_ADD = 0.20000000298023224;
    /** Multiplicateur de rotation minimal en zoom : 0,5 % de la vitesse d'origine. */
    private static final double MIN_TURN_MULTIPLIER = 0.005;

    private void saveSensitivity(Object options) {
        try {
            Object handle = sensitivityHandle(options);
            savedSensitivity = handle != null ? readOptionValue(handle, options) : -1;
        } catch (Throwable t) {
            savedSensitivity = -1;
        }
    }

    private void restoreSensitivity(Object options) {
        if (savedSensitivity < 0) return;
        try {
            Object handle = sensitivityHandle(options);
            if (handle != null) writeOptionValue(handle, options, savedSensitivity);
        } catch (Throwable ignored) {
        }
        savedSensitivity = -1;
    }

    /**
     * Sans réflexion — {@code AccessPoint.OPTIONS_SENSITIVITY} via
     * {@link GameOptions} (champ privé). Repli : Yarn "mouseSensitivity" (nom historique 1.8.9)
     * → réel "sensitivity" (vérifié par javap sur le vrai jar 26.1.2 :
     * {@code OptionInstance<Double> sensitivity}) — si la résolution échoue
     * sur un bracket non testé, {@link #applySensitivityScale} se dégrade
     * silencieusement (le zoom lui-même continue de fonctionner, seule la
     * compensation de sensibilité ne s'applique pas).
     */
    private Object sensitivityHandle(Object options) {
        return GameOptions.sensitivityHandle();
    }

    /**
     * BUG TROUVÉ (audit modules) : passait TOUJOURS par {@code
     * org.lwjgl.input.Keyboard} (LWJGL2) — inexistant sous LWJGL3/GLFW
     * (1.13+), échec silencieux total sur ces versions. Utilise désormais
     * l'instance {@code UiInputPollerModern} active (créée par le Mixin de
     * rendu global de son bracket) quand disponible, sinon retombe sur
     * LWJGL2 (1.8.9, comportement d'origine inchangé).
     */
    private boolean isZoomKeyDown() throws Exception {
        UiInputPollerModern modern = UiInputPollerModern.ACTIVE;
        if (modern != null) return modern.isKeyDownByName(zoomKey);

        Class<?> keyboard = McReflect.rawClass("org.lwjgl.input.Keyboard");
        if (!zoomKey.equals(cachedKeyName)) {
            cachedKeyName = zoomKey;
            cachedKeyCode = (int) McReflect.rawMethod(keyboard, "getKeyIndex", String.class).invoke(null, zoomKey);
        }
        if (cachedKeyCode < 0) return false;
        return (boolean) McReflect.rawMethod(keyboard, "isKeyDown", int.class).invoke(null, cachedKeyCode);
    }

    /**
     * Options par l'accessor Mixin, via {@code ClientData} — zéro réflexion
     * (repli multi-bracket supprimé le 2026-08-27).
     */
    private Object optionsInstance() throws Exception {
        // optionsObject() et NON options() : ce module tourne sur toutes les
        // versions, et la variante typée lierait le type 26.1.2 (voir sa javadoc).
        return ClientData.optionsObject();
    }

    /**
     * Poignée d'option de {@code Options.fov} (champ privé) via
     * {@link GameOptions}, donc l'accessor de la tranche active — voir
     * {@link #sensitivityHandle}, même principe.
     */
    private Object fovHandle() throws Exception {
        return GameOptions.fovHandle();
    }
}
