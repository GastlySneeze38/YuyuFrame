package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigDropdown;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPollerModern;

import java.lang.reflect.Method;

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
 * IS_1_21_11)} que ShulkerPreviewModule/NoPumpkinOverlayModule) :
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
 * {@code Screen} pour ShulkerPreviewModule — voir sa javadoc) : le blocage
 * de la rotation réelle du joueur passe entièrement par l'annulation de
 * {@code MouseHandler.turnPlayer}/{@code Mouse.updateMouse} (classe
 * UTILITAIRE non sous-classée, même profil de risque que GameRenderer/
 * TitleScreen/ChatListener, déjà ciblés sans souci ailleurs dans ce projet),
 * jamais en touchant le joueur lui-même.
 *
 * 1.16.5/1.20.4/1.21.4/1.8.9 pas encore portés.
 */
public final class FreelookModule extends LauncherModule {

    @ConfigKeybind(name = "Touche de freelook", category = "Réglages")
    public String freelookKey = "V";

    @ConfigDropdown(name = "Mode", description = "\"Maintenir\" : freelook actif tant que la touche est enfoncée (défaut). \"Basculer\" : un appui active, un second désactive.",
        category = "Réglages", options = { "Maintenir", "Basculer" })
    public int mode = 0;

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
        super("freelook", "Freelook", "Maintenir (ou basculer) une touche pour regarder autour de soi sans changer la direction du personnage (façon OptiFine).", false);
    }

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
     * Force la vue à la 3e personne (dos) dès que le freelook s'engage, peu
     * importe la vue de départ — demandé explicitement par l'utilisateur
     * ("péu importe dans quelle vue on est ça nous mette a la 3e personne").
     * Restaure la vue EXACTE d'avant (même si l'utilisateur a lui-même
     * changé de vue PENDANT le freelook via F5 — {@link #savedCameraType}
     * garde la valeur d'AVANT l'engagement, jamais réécrasée entre-temps) au
     * désengagement. Réflexion directe (noms RÉELS, bracket 26.1.2
     * uniquement comme le reste de ce module) : {@code Options.getCameraType()}/
     * {@code setCameraType(CameraType)}, {@code CameraType.THIRD_PERSON_BACK}
     * (constante statique publique) — vérifiés par désassemblage bytecode du
     * vrai jar 26.1.2.
     */
    @Override
    public void onTick() {
        try {
            boolean engaged = isFreelookEngaged();
            if (engaged == wasEngaged) return;
            wasEngaged = engaged;

            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            java.lang.reflect.Field fOptions = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options");
            if (fOptions == null) return;
            Object options = fOptions.get(mc);
            if (options == null) return;

            Method getCameraType = McReflect.noArgMethod(options.getClass(),
                "net/minecraft/client/option/GameOptions", "getPerspective", "getCameraType");
            if (getCameraType == null) return;
            Method setCameraType = McReflect.method(options.getClass(),
                "net/minecraft/client/option/GameOptions", "setPerspective", "setCameraType",
                getCameraType.getReturnType());
            if (setCameraType == null) return;

            if (engaged) {
                savedCameraType = getCameraType.invoke(options);
                Class<?> cameraTypeClass = McReflect.yarnClass(
                    "net/minecraft/client/option/Perspective", "net.minecraft.client.CameraType");
                if (cameraTypeClass == null) return;
                java.lang.reflect.Field fThirdPersonBack = McReflect.field(cameraTypeClass,
                    "net/minecraft/client/option/Perspective", "THIRD_PERSON_BACK");
                if (fThirdPersonBack == null) return;
                setCameraType.invoke(options, fThirdPersonBack.get(null));
            } else if (savedCameraType != null) {
                setCameraType.invoke(options, savedCameraType);
                savedCameraType = null;
            }
        } catch (Throwable t) {
            LauncherLog.err("[FreelookModule] onTick: " + t);
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
            if (wasEngaged && savedCameraType != null) {
                try {
                    Object mc = McReflect.minecraftClient();
                    if (mc != null) {
                        java.lang.reflect.Field fOptions = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options");
                        Object options = fOptions != null ? fOptions.get(mc) : null;
                        if (options != null) {
                            Method setCameraType = McReflect.method(options.getClass(),
                                "net/minecraft/client/option/GameOptions", "setPerspective", "setCameraType",
                                savedCameraType.getClass());
                            if (setCameraType != null) setCameraType.invoke(options, savedCameraType);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            wasEngaged = false;
            savedCameraType = null;
        }
    }

    /** Changement de mode en cours de partie (ex: touche restée enfoncée en passant de "Basculer" à "Maintenir") — repart d'un état propre plutôt que de garder un état "basculé" fantôme. */
    @Override
    public void onConfigChanged() {
        toggledOn = false;
        prevKeyDown = false;
    }
}
