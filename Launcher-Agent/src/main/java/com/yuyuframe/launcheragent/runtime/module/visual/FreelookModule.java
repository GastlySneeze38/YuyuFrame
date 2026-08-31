package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;

import com.yuyuframe.launcheragent.runtime.game.ClientData;

/**
 * Freelook façon OptiFine : maintenir une touche découple la CAMÉRA de la
 * rotation du joueur — bouger la souris ne fait tourner que le rendu (voir
 * autour de soi), le corps du joueur (direction de déplacement, visée/
 * attaque) reste figé dans sa direction d'origine tant que la touche est
 * maintenue. Relâcher la touche recale INSTANTANÉMENT la caméra sur la
 * vraie direction du joueur (pas de transition — demandé explicitement).
 *
 * Force AUSSI la vue à la 3e personne (dos) dès l'engagement, peu importe la
 * vue de départ (1re personne ou 3e personne déjà) — restaure la vue EXACTE
 * d'avant au désengagement, voir {@link #onTick()}.
 *
 * Ce module lui-même ne fait QUE porter le réglage (touche configurable) et
 * l'état partagé lu/écrit par deux Mixins dédiés PAR BRACKET (voir
 * {@code MouseHandlerFreelookMixin261}/{@code CameraFreelookMixin261} pour
 * 26.1.2, {@code MouseHandlerFreelookMixin}/{@code CameraFreelookMixin} —
 * package base, sans suffixe — pour 1.21.11, même gate {@code (IS_26_1 ||
 * IS_1_21_11)} que NoPumpkinOverlayModule) :
 * <pre>
 *   MouseHandlerFreelookMixin261 : annule MouseHandler.turnPlayer(D)V
 *   (@At HEAD, cancellable) quand la touche est maintenue — lit/vide
 *   accumulatedDX/DY (champs privés) lui-même au lieu de laisser vanilla
 *   tourner le joueur (LocalPlayer.turn(D,D), dernier appel de
 *   turnPlayer), applique la MÊME courbe de sensibilité que vanilla
 *   (sensibilité*0.6+0.2, au cube, ×8 — formule vanilla bien connue,
 *   reproduite ici plutôt que Mixin sur LocalPlayer/Entity — hiérarchie
 *   bien trop large, même risque que Screen, voir historique de session)
 *   et accumule le résultat via {@link #accumulate}.
 *
 *   CameraFreelookMixin261 : à la fin de Camera.update(DeltaTracker)V
 *   (@At TAIL, APRÈS alignWithEntity — confirmé par désassemblage bytecode
 *   du vrai jar 26.1.2 : alignWithEntity aligne xRot/yRot sur
 *   entity.getViewYRot/XRot PUIS appelle Camera.setRotation(F,F)), rappelle
 *   Camera.setRotation(F,F) UNE SECONDE FOIS avec xRot/yRot + nos offsets —
 *   JAMAIS d'écriture directe des champs privés xRot/yRot (contrairement à
 *   la tentation évidente) : setRotation() recalcule aussi le quaternion de
 *   rotation ET les vecteurs forward/up/left dérivés, qu'un simple champ
 *   écrasé laisserait périmés (caméra visuellement inchangée malgré le
 *   nouveau xRot/yRot).
 *
 *   MouseHandlerFreelookMixin (1.21.11) : même principe sur {@code
 *   Mouse.updateMouse(D)V} (Yarn ; réel {@code MouseHandler.turnPlayer(D)V})
 *   — champs {@code cursorDeltaX}/{@code cursorDeltaY} (Yarn) au lieu de
 *   {@code accumulatedDX}/{@code accumulatedDY} (réel 26.1.2).
 *
 *   CameraFreelookMixin (1.21.11) : architecture DIFFÉRENTE de 26.1.2 —
 *   {@code Camera.update(...)V} fait tout en une seule méthode (pas
 *   d'{@code alignWithEntity} séparée), avec DEUX call sites de {@code
 *   moveBy(FFF)V} (3e personne / vue rapprochée) au lieu d'un seul — hook
 *   direct sur {@code moveBy(FFF)V} lui-même (couvre les deux sites d'un
 *   coup) + TAIL de {@code update()} pour la 1re personne pure (jamais de
 *   moveBy) — voir sa javadoc de classe pour le détail vérifié par javap.
 * </pre>
 *
 * PAS de Mixin sur {@code LocalPlayer}/{@code Entity} (hiérarchie commune à
 * TOUTE entité du jeu, risque de casse identique à celui rencontré avec
 * {@code Screen}, qui avait cassé en cascade tous les Mixins de cette classe
 * cible — voir docs/LauncherAgent/module-bracket-audit.md) : le blocage
 * de la rotation réelle du joueur passe entièrement par l'annulation de
 * {@code MouseHandler.turnPlayer}/{@code Mouse.updateMouse} (classe
 * UTILITAIRE non sous-classée, même profil de risque que GameRenderer/
 * TitleScreen/ChatListener, déjà ciblés sans souci ailleurs dans ce projet),
 * jamais en touchant le joueur lui-même.
 *
 * 1.16.5/1.20.4/1.21.4/1.8.9 pas encore portés.
 */
public final class FreelookModule extends LauncherModule {

    public String freelookKey = "V";

    public int mode = 0;

    /**
     * La dépendance du curseur de sensibilité est ici un {@code BooleanSupplier}
     * relu à chaque frame — l'ancien couple {@code dependsOnField}/{@code
     * dependsOnValue} désignait le champ par son NOM, résolu par réflexion, et
     * ne savait comparer que des {@code int}.
     */
    @Override
    protected void settings(SettingList s) {
        s.keybind("freelookKey", "Touche de freelook", "Réglages",
            () -> freelookKey, v -> freelookKey = v);
        s.dropdown("mode", "Mode",
            "\"Maintenir\" : freelook actif tant que la touche est enfoncée (défaut). \"Basculer\" : un appui active, un second désactive.",
            "Réglages", new String[]{ "Maintenir", "Basculer" }, null,
            () -> mode, v -> mode = v);
        s.dropdown("thirdPersonView", "Vue 3e personne",
            "Vue forcée à l'engagement du freelook (voir onTick). \"Avant\" (défaut, face au joueur — pas de blocage en regardant vers le haut) ou \"Arrière\" (dos au joueur, la caméra se plaque contre le joueur en regardant tout en haut — limitation vanilla).",
            "Réglages", new String[]{ "Arrière", "Avant" }, null,
            () -> thirdPersonView, v -> thirdPersonView = v);
        s.dropdown("sensitivityMode", "Sensibilité",
            "\"Sensibilité du jeu\" (défaut) : suit le réglage de sensibilité de la souris du jeu, comme le reste du gameplay. \"Personnalisée\" : ignore ce réglage, utilise une valeur dédiée au freelook.",
            "Réglages", new String[]{ "Sensibilité du jeu", "Personnalisée" }, null,
            () -> sensitivityMode, v -> sensitivityMode = v);
        s.slider("customSensitivity", "Sensibilité personnalisée",
            "Utilisée seulement si \"Sensibilité\" ci-dessus est réglée sur \"Personnalisée\" — même unité que le curseur de sensibilité du jeu (pourcentage, 50% = valeur par défaut du jeu).",
            "Réglages", 0f, 100f, 1f,
            () -> sensitivityMode == 1,
            () -> customSensitivity, v -> customSensitivity = v);
    }

    // Défaut sur "Avant" (2026-08-25, §15) — retour utilisateur : en vue
    // Arrière, regarder tout en haut plaque la caméra contre le joueur
    // (comportement natif de getMaxZoom()/move() en vue arrière, voir la
    // javadoc de CameraFreelookMixin261 pour le détail vérifié par javap) ;
    // la vue Avant n'a PAS ce problème (son propre retournement de pitch
    // vanilla inverse le sens du rayon de recul).
    public int thirdPersonView = 1;

    // Demandé explicitement ("utilise la sensibilité du jeu normal, et
    // ajoute le choix entre sensi du jeu ou personnalisée") — le freelook
    // lisait DÉJÀ Options.sensitivity() par défaut (voir readSensitivity()
    // dans chaque Mixin MouseHandlerFreelook*), mais sans aucune alternative
    // configurable. {@link #resolveSensitivity} est le point UNIQUE où ce
    // choix est tranché, appelé par chaque Mixin après sa propre lecture
    // réflexive de la sensibilité RÉELLE du jeu (jamais dupliquée ici).
    public int sensitivityMode = 0;

    // BUG TROUVÉ (retour utilisateur : "vanilla ne va que jusqu'à 100%",
    // pas 200% comme supposé au départ) — corrigé pour coller EXACTEMENT à
    // la plage réelle du curseur vanilla (0%-100%, valeur brute×100, PAS
    // ×200). Stockée en pourcentage — voir resolveSensitivity pour la
    // conversion vers l'échelle brute au moment de l'appliquer. Défaut 50%
    // (= 0.5 brut, valeur par défaut vanilla).
    public float customSensitivity = 50f;

    // Écrits UNIQUEMENT par MouseHandlerFreelookMixin261 (une fois par
    // frame, avant que CameraFreelookMixin261 ne les lise — les deux Mixins
    // tournent dans la même frame, voir javadoc de classe) — jamais
    // l'inverse. STATIC (comme UiInputPoller.ACTIVE) : les deux Mixins
    // n'ont aucune référence directe l'un vers l'autre.
    private static volatile boolean active;
    private static volatile double yawOffset;
    private static volatile double pitchOffset;

    // État du mode "Basculer" (voir isFreelookEngaged) — front montant de la
    // touche détecté ICI (pas dans UiInputPoller, qui ne connaît pas cette
    // touche configurable spécifiquement, même limitation que ZoomModule).
    private static volatile boolean prevKeyDown;
    private static volatile boolean toggledOn;

    // Force la vue à la 3e personne (dos) à l'engagement du freelook — demandé
    // explicitement par l'utilisateur, peu importe la vue de départ (1re
    // personne ou 3e personne déjà) — restaurée EXACTEMENT à la vue d'avant
    // au relâchement/désengagement, voir onTick(). Champs D'INSTANCE (pas
    // static comme le reste) : onTick() tourne toujours sur LA MÊME instance
    // singleton enregistrée dans ModuleRegistry, pas besoin de partage
    // inter-Mixin ici (contrairement à active/yawOffset/pitchOffset).
    private boolean wasEngaged;
    private Object savedCameraType;

    public FreelookModule() {
        super("freelook", "Freelook", "Maintenir (ou basculer) une touche pour regarder autour de soi sans changer la direction du personnage (façon OptiFine).", false,
            HookPoint.FREELOOK_TURN_INTERCEPT, HookPoint.FREELOOK_CAMERA_ROTATION_OFFSET);
        // Sorti du groupe "Confort visuel" le 2026-08-30 (demande explicite) :
        // il a désormais sa propre carte sur l'accueil, donc il lui faut une
        // icône comme tout module non groupé — sans elle, ModCard retomberait
        // sur la pastille-lettre. Nom vérifié (HTTP 200, image/png) selon la
        // convention de LauncherModule#icons8.
        iconUrl = icons8("rotate-camera");
        // 26.1.2 — migration apimixin (ROADMAP-agent.md §4) : MouseHandlerFreelookMixin261/
        // CameraFreelookMixin261 n'importent plus FreelookModule directement,
        // ils dispatchent via VanillaHookRegistry — c'est CE module qui
        // s'enregistre dessus (bon sens de dépendance : runtime → apimixin).
        VanillaHookRegistry.register(HookPoint.FREELOOK_TURN_INTERCEPT, this::interceptTurn);
        VanillaHookRegistry.registerValue(HookPoint.FREELOOK_CAMERA_ROTATION_OFFSET, this::cameraRotationOffset);
    }

    /**
     * Facteur appliqué par {@code Entity.turn(double yo, double xo)} À SES
     * DEUX ARGUMENTS avant de les ajouter à xRot/yRot — vérifié au bytecode
     * (javap du vrai jar 26.1.2) :
     * <pre>
     *   0: dload_3 / d2f / ldc 0.15f / fmul   → pitch
     *   8: dload_1 / d2f / ldc 0.15f / fmul   → yaw
     * </pre>
     * {@code MouseHandlerFreelookMixin261} intercepte l'appel à {@code turn()}
     * AVANT son exécution : les deltas reçus sont donc déjà passés par la
     * courbe de sensibilité vanilla (calculée dans {@code turnPlayer}) mais
     * PAS encore par ce facteur. Les accumuler bruts donne 1/0.15 ≈ 6,67× trop
     * de rotation — exactement le symptôme « sensibilité trop rapide » remonté
     * en jeu, et la même racine que le bug historique de l'ancienne
     * implémentation (voir {@link #resolveSensitivity}), qui reconstruisait la
     * courbe à la main et oubliait aussi ce facteur final.
     *
     * Omnilook fait le même calcul ({@code addRotation(yRot * 0.15, xRot * 0.15)}).
     */
    private static final double VANILLA_TURN_FACTOR = 0.15;

    // Front montant de l'engagement — INDÉPENDANT de LauncherModule#wasEngaged
    // (utilisé par onTick() pour la bascule de CameraType, cadence différente
    // : tick de jeu vs callback souris) pour ne pas coupler deux chemins
    // d'exécution distincts. Consulté par cameraRotationOffset (PAS par
    // interceptTurn) : voir sa javadoc pour pourquoi le calcul de mise à
    // niveau vue Avant a besoin du xRot y étant disponible.
    private static volatile boolean turnEngagedPrev;
    private static volatile boolean pendingLevelOnEngage;

    /** Handler de {@link HookPoint#FREELOOK_TURN_INTERCEPT} — voir MouseHandlerFreelookMixin261. {@code ctx} = {@code double[]{yRot, xRot}}, valeurs BRUTES telles que passées à {@code turn()} (pré-{@link #VANILLA_TURN_FACTOR}). */
    private boolean interceptTurn(Object ctx) {
        if (!(ctx instanceof double[])) return false;
        double[] delta = (double[]) ctx;
        boolean engaged = isFreelookEngaged();
        if (!engaged) {
            turnEngagedPrev = false;
            deactivate();
            return false;
        }
        if (!turnEngagedPrev) {
            turnEngagedPrev = true;
            pendingLevelOnEngage = true;
        }
        accumulate(delta[0] * VANILLA_TURN_FACTOR, delta[1] * VANILLA_TURN_FACTOR);
        return true;
    }

    /**
     * Handler de {@link HookPoint#FREELOOK_CAMERA_ROTATION_OFFSET} — voir
     * CameraFreelookMixin261. {@code ctx} = {@code float[]{yRot, xRot}} —
     * {@code xRot} est {@code entity.getViewXRot(partialTick)}, GELÉ pour
     * toute la durée de l'engagement (le vrai {@code turn()} du joueur est
     * annulé pendant ce temps par {@code MouseHandlerFreelookMixin261}) —
     * renvoie {@code float[]{newYRot, newXRot}} ou {@code null} (freelook
     * inactif, aucun changement).
     *
     * Cadrage fixe en vue Avant SEULEMENT, au premier appel de chaque
     * engagement (2026-08-25, §15-§18 — retours utilisateur successifs :
     * "regarde le ciel" → mis à l'horizontale → "pas assez, remonte" (erreur
     * de sens de ma part, corrigée) → "il faut regarder vers le BAS [le sol],
     * descends plutôt à 40°"). Cause vérifiée au bytecode (voir javadoc de
     * {@code CameraFreelookMixin261}) : notre point d'injection (ordinal 1)
     * tourne AVANT le retournement propre à {@code isMirrored()}, qui calcule
     * {@code xRot final = -(xRot + pitchOffset)}. Pour atteindre un cadrage
     * FIXE {@code xRot final = FRONT_VIEW_TARGET_PITCH} (positif = vers le
     * bas, convention vanilla déjà établie ailleurs dans ce fichier) quel que
     * soit le {@code xRot} de départ :
     * <pre>
     *   xRot final = -(xRot + pitchOffset) = FRONT_VIEW_TARGET_PITCH
     *   ⟹ pitchOffset = -FRONT_VIEW_TARGET_PITCH - xRot
     * </pre>
     * Valeur choisie par estimation (pas mesurable sans jeu) — ajuster
     * {@link #FRONT_VIEW_TARGET_PITCH} sur retour utilisateur plutôt que de
     * la retoucher à l'aveugle. Le joueur ajuste ensuite librement par-dessus,
     * comme avant. AUCUNE négation des DELTAS de souris eux-mêmes : contrôles
     * confirmés NON inversés par l'utilisateur, voir historique.
     */
    private static final double FRONT_VIEW_TARGET_PITCH = 30.0;

    private Object cameraRotationOffset(Object ctx) {
        boolean active = isActive();
        if (!active || !(ctx instanceof float[])) return null;
        float[] in = (float[]) ctx;
        float yRot = in[0];
        float xRot = in[1];
        if (pendingLevelOnEngage) {
            pendingLevelOnEngage = false;
            if (thirdPersonView == 1) {
                pitchOffset = -FRONT_VIEW_TARGET_PITCH - xRot;
            }
        }
        float newXRot = clampFloat(xRot + (float) pitchOffset(), -90f, 90f);
        float newYRot = yRot + (float) yawOffset();
        return new float[]{newYRot, newXRot};
    }

    private static float clampFloat(float v, float lo, float hi) { return v < lo ? lo : Math.min(v, hi); }

    /**
     * Appelé par MouseHandlerFreelookMixin261 (une fois par frame) — vrai si
     * le freelook doit être actif CE FRAME, selon le {@link #mode} choisi :
     * "Maintenir" reflète directement l'état de la touche ; "Basculer"
     * inverse un état interne à chaque appui (front montant).
     */
    public static boolean isFreelookEngaged() {
        LauncherModule self = ModuleRegistry.get("freelook");
        if (!(self instanceof FreelookModule) || !self.isEnabled()) {
            prevKeyDown = false;
            toggledOn = false;
            return false;
        }
        FreelookModule module = (FreelookModule) self;
        UiInputPollerModern modern = UiInputPollerModern.ACTIVE;
        boolean down = modern != null && modern.isKeyDownByName(module.freelookKey);

        if (module.mode == 1) {
            if (down && !prevKeyDown) toggledOn = !toggledOn;
            prevKeyDown = down;
            return toggledOn;
        }
        prevKeyDown = down;
        toggledOn = false;
        return down;
    }

    /**
     * Tranche entre sensibilité du jeu et personnalisée (voir {@link
     * #sensitivityMode}/{@link #customSensitivity}) — appelée par chaque
     * Mixin MouseHandlerFreelook* APRÈS sa propre lecture réflexive de
     * {@code gameSensitivity} (la vraie valeur vanilla BRUTE, 0..1, jamais
     * dupliquée ici). {@code customSensitivity} est stockée en POURCENTAGE
     * (0..100, même plage RÉELLE que le curseur vanilla — PAS 0..200,
     * corrigé après retour utilisateur) — reconvertie ici vers l'échelle
     * brute (÷100) pour rester compatible avec la même formule de courbe
     * que {@code gameSensitivity} (voir MouseHandlerFreelookMixin*).
     * Repli sur {@code gameSensitivity} telle quelle si le module n'est,
     * pour une raison quelconque, pas résolu (ne devrait jamais arriver —
     * cette méthode n'est appelée que depuis un chemin déjà gardé par
     * {@link #isFreelookEngaged}).
     *
     * BUG TROUVÉ (retour utilisateur : "la sensibilité n'a jamais été la
     * même que celle du jeu" — mesure précise fournie : "même coup de
     * souris, 2 tours en freelook contre un demi-tour en F5", soit un ratio
     * mesuré d'environ 4×, PAS un simple ressenti de perspective) — la
     * cause n'était PAS la vue 3e personne (tentative de compensation par
     * un facteur arbitraire, retirée : mauvaise piste, voir historique git).
     * Vraie cause trouvée par désassemblage complet (javap) de {@code
     * Entity.turn(double,double)} du vrai jar 26.1.2 — la MÉTHODE que notre
     * Mixin court-circuite entièrement (voir MouseHandlerFreelookMixin261)
     * multiplie ENCORE xo/yo par {@code 0.15f} avant de les ajouter à xRot/
     * yRot ({@code (float)xo * 0.15f}, confirmé bytecode) : notre calcul
     * reproduisait la courbe de sensibilité vanilla (sens*0.6+0.2, cubée,
     * ×8) mais s'arrêtait LÀ, sans jamais appliquer ce facteur final —
     * environ 1/0.15 ≈ 6.7× trop de rotation, cohérent avec la mesure
     * utilisateur. Voir {@link com.yuyuframe.launcheragent.mixin.client.v26_1.MouseHandlerFreelookMixin261}
     * pour où ce facteur est désormais appliqué.
     */
    public static double resolveSensitivity(double gameSensitivity) {
        LauncherModule self = ModuleRegistry.get("freelook");
        if (!(self instanceof FreelookModule)) return gameSensitivity;
        FreelookModule module = (FreelookModule) self;
        return module.sensitivityMode == 1 ? module.customSensitivity / 100.0 : gameSensitivity;
    }

    /** @return {@code CameraType.THIRD_PERSON_FRONT} ou {@code THIRD_PERSON_BACK} selon {@link #thirdPersonView} — les deux sont des constantes statiques publiques (vérifiées par javap du vrai jar 26.1.2). */
    private CameraType targetCameraType() {
        return thirdPersonView == 1 ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK;
    }


    /**
     * Force la vue 3e personne choisie ({@link #thirdPersonView}) dès que le
     * freelook s'engage, peu importe la vue de départ — demandé explicitement
     * par l'utilisateur ("péu importe dans quelle vue on est ça nous mette a
     * la 3e personne"). Restaure la vue EXACTE d'avant (même si l'utilisateur
     * a lui-même changé de vue PENDANT le freelook via F5 — {@link
     * #savedCameraType} garde la valeur d'AVANT l'engagement, jamais
     * réécrasée entre-temps) au désengagement. Réflexion directe (noms RÉELS,
     * bracket 26.1.2 uniquement comme le reste de ce module) : {@code
     * Options.getCameraType()}/{@code setCameraType(CameraType)} — vérifiés
     * par désassemblage bytecode du vrai jar 26.1.2.
     */
    @Override
    public void onTick() {
        try {
            boolean engaged = isFreelookEngaged();
            if (engaged == wasEngaged) return;
            wasEngaged = engaged;

            // Repli réflexif (résolution de getPerspective/getCameraType, de
            // setPerspective/setCameraType et de la constante Perspective)
            // supprimé le 2026-08-27 : tout passe par l'accessor.
            applyCameraTypeViaAccessor(engaged);
        } catch (Throwable t) {
            LauncherLog.err("[FreelookModule] onTick: " + t);
        }
    }

    /**
     * Force/restaure la vue 3e personne — zéro réflexion : options par
     * l'accessor Mixin ({@code ClientData} → {@code
     * MinecraftAccessor261#la$options()}), vue par les méthodes publiques
     * {@code Options.getCameraType()}/{@code setCameraType(CameraType)}.
     * {@code false} hors bracket 26.1.2 (plus aucun repli depuis le
     * 2026-08-27 : l'appelant n'a plus rien vers quoi retomber).
     *
     * Garde son propre try/catch, distinct de celui de l'appelant : les noms
     * de classes référencés ici sont RÉELS, donc absents des brackets
     * obfusqués — le {@code NoClassDefFoundError} qui en découle est attendu
     * et doit rester silencieux, jamais logué comme une erreur.
     */
    private boolean applyCameraTypeViaAccessor(boolean engaged) {
        try {
            Options options = ClientData.options();
            if (options == null) return false;
            if (engaged) {
                savedCameraType = options.getCameraType();
                options.setCameraType(targetCameraType());
            } else if (savedCameraType != null) {
                options.setCameraType((CameraType) savedCameraType);
                savedCameraType = null;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Accumule un delta caméra-only (degrés) au lieu de tourner le joueur — voir MouseHandlerFreelookMixin261. */
    public static void accumulate(double dYaw, double dPitch) {
        active = true;
        yawOffset += dYaw;
        pitchOffset = clamp(pitchOffset + dPitch, -90.0, 90.0);
    }

    /** Touche relâchée (ou module désactivé) — la caméra se recale INSTANTANÉMENT sur la vraie direction du joueur, voir javadoc de classe. */
    public static void deactivate() {
        active = false;
        yawOffset = 0;
        pitchOffset = 0;
    }

    public static boolean isActive() { return active; }
    public static double yawOffset() { return yawOffset; }
    public static double pitchOffset() { return pitchOffset; }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (!enabled) {
            deactivate();
            prevKeyDown = false;
            toggledOn = false;
            // Module désactivé PENDANT un engagement (vue forcée en 3e
            // personne) — restaure la vue d'origine plutôt que de la
            // laisser bloquée en 3e personne, même chemin que onTick()
            // sinon suivi (le module ne tourne plus, wasEngaged ne
            // redeviendrait jamais faux tout seul).
            if (wasEngaged && savedCameraType != null) restoreCameraTypeViaAccessor();
            wasEngaged = false;
            savedCameraType = null;
        }
    }

    /** Chemin SANS réflexion pour la restauration — voir {@link #applyCameraTypeViaAccessor}, même principe (y compris le try/catch dédié). */
    private boolean restoreCameraTypeViaAccessor() {
        try {
            Options options = ClientData.options();
            if (options == null) return false;
            options.setCameraType((CameraType) savedCameraType);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Changement de mode en cours de partie (ex: touche restée enfoncée en passant de "Basculer" à "Maintenir") — repart d'un état propre plutôt que de garder un état "basculé" fantôme. */
    @Override
    public void onConfigChanged() {
        toggledOn = false;
        prevKeyDown = false;
    }
}
