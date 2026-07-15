package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPollerModern;

import java.lang.reflect.Field;

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

    @ConfigKeybind(name = "Touche de zoom", category = "Réglages")
    public String zoomKey = "C";

    @ConfigSlider(name = "FOV en zoom (base)", description = "Niveau de zoom appliqué à l'appui sur la touche — voir \"FOV en zoom max\" pour la limite atteignable en scrollant.",
        category = "Réglages", min = 5f, max = 60f, step = 1f)
    public float zoomFov = 20f;

    // Essential-style : scroller PENDANT le zoom va encore plus loin que la
    // base (jamais en-deçà, voir javadoc de classe) — demandé explicitement
    // par l'utilisateur. scrollOffsetFov REMIS À ZÉRO à chaque NOUVEL appui
    // sur la touche (pas conservé d'une session de zoom à l'autre) : plus
    // simple à comprendre ("toujours pareil au prochain appui") qu'un état
    // caché qui persiste silencieusement.
    @ConfigSlider(name = "FOV en zoom max", description = "Limite la plus zoomée atteignable en scrollant pendant le zoom (molette vers le haut = zoome plus).",
        category = "Réglages", min = 1f, max = 60f, step = 1f)
    public float zoomFovMin = 5f;

    @ConfigSlider(name = "Pas de zoom (molette)", description = "Variation de FOV par cran de molette pendant le zoom.",
        category = "Réglages", min = 0.5f, max = 10f, step = 0.5f)
    public float zoomScrollStep = 2f;

    @ConfigSlider(name = "Durée de transition (s)", description = "Temps pour atteindre le niveau de zoom cible en douceur, à l'appui comme au relâchement — 0 = instantané (comportement d'origine).",
        category = "Réglages", min = 0f, max = 1f, step = 0.05f)
    public float transitionSeconds = 0.15f;

    @ConfigSlider(name = "Réduction de la sensibilité en zoom (%)", description = "Ralentit la rotation de la caméra proportionnellement au niveau de zoom courant, pour un ressenti cohérent (100% = compensation complète façon longue-vue, 0% = sensibilité inchangée).",
        category = "Réglages", min = 0f, max = 100f, step = 5f)
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
    }

    @Override
    public void onTick() {
        try {
            boolean down = isZoomKeyDown();
            Field fovField = fovField();
            Object options = optionsInstance();
            if (fovField == null || options == null) return;

            if (down && !zooming) {
                zooming = true;
                scrollOffsetFov = 0;
                if (!active) {
                    active = true;
                    savedFov = readOptionValue(fovField, options);
                    effectiveFov = savedFov;
                    transitionRange = Math.max(1.0, Math.abs(savedFov - zoomFov));
                    saveSensitivity(options);
                }
            } else if (!down && zooming) {
                zooming = false;
                scrollOffsetFov = 0;
            }

            if (!active) return;

            int scroll = readScrollDelta();
            if (zooming && scroll != 0) {
                // Molette vers le haut (delta > 0) = zoome PLUS = FOV plus
                // petit — scrollOffsetFov reste >= 0, jamais négatif (la
                // base zoomFov est le plancher "le moins zoomé" tant que la
                // touche est maintenue, voir javadoc de classe).
                scrollOffsetFov = Math.max(0, scrollOffsetFov + scroll * zoomScrollStep);
            }

            double maxOffset = Math.max(0, zoomFov - zoomFovMin);
            double targetFov = zooming ? (zoomFov - Math.min(scrollOffsetFov, maxOffset)) : savedFov;

            effectiveFov = stepToward(effectiveFov, targetFov);
            writeOptionValue(fovField, options, effectiveFov);
            applySensitivityScale(options);

            // Transition de sortie terminée (touche relâchée ET la valeur
            // lissée a rejoint la valeur d'origine) : restauration EXACTE
            // (élimine toute erreur d'arrondi accumulée par le lissage) et
            // libération de l'état, pour qu'un futur appui reparte propre.
            if (!zooming && Math.abs(effectiveFov - savedFov) < 0.05) {
                writeOptionValue(fovField, options, savedFov);
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
     * l'écart restant par tick", qui donnerait une vitesse variable) — un pas
     * fixe par tick calculé pour que {@link #transitionSeconds} corresponde
     * au temps RÉEL pour parcourir {@link #transitionRange} (l'écart
     * base↔cible mesuré à l'entrée en zoom), à 20 ticks/seconde.
     * {@code transitionSeconds == 0} conserve le comportement d'origine
     * (saut instantané).
     */
    private double stepToward(double current, double target) {
        if (transitionSeconds <= 0f) return target;
        double maxStep = transitionRange / (transitionSeconds * 20.0);
        double diff = target - current;
        if (Math.abs(diff) <= maxStep) return target;
        return current + Math.signum(diff) * maxStep;
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        if (active && savedFov >= 0) {
            try {
                Field fovField = fovField();
                Object options = optionsInstance();
                if (fovField != null && options != null) {
                    writeOptionValue(fovField, options, savedFov);
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
     */
    private double readOptionValue(Field field, Object options) throws Exception {
        if (field.getType() == double.class) return field.getDouble(options);
        if (field.getType() == float.class) return field.getFloat(options);
        return McReflect.simpleOptionGetValue(field.get(options));
    }

    private void writeOptionValue(Field field, Object options, double value) throws Exception {
        if (field.getType() == double.class) { field.setDouble(options, value); return; }
        if (field.getType() == float.class) { field.setFloat(options, (float) value); return; }
        McReflect.simpleOptionSetValue(field.get(options), value);
    }

    /**
     * Réduit la sensibilité de rotation caméra proportionnellement au niveau
     * de zoom COURANT (suit {@link #effectiveFov}, donc la réduction
     * s'installe/se retire EN DOUCEUR en même temps que le FOV, pas d'un
     * coup) — voir javadoc de classe § 3 pour le pourquoi (vanilla ne
     * compense jamais ça tout seul). {@code ratio} vaut 1.0 hors zoom (aucune
     * réduction) et diminue vers {@code zoomFov(Min)/savedFov} en zoom
     * maximal ; {@link #sensitivityCompensation} permet de doser l'intensité
     * de l'effet (0% = désactivé, 100% = réduction dans les mêmes
     * proportions que le zoom lui-même).
     */
    private void applySensitivityScale(Object options) {
        if (savedSensitivity < 0 || sensitivityCompensation <= 0f || savedFov <= 0) return;
        try {
            Field field = sensitivityField(options);
            if (field == null) return;
            double ratio = Math.min(1.0, effectiveFov / savedFov);
            double scale = 1.0 - (1.0 - ratio) * (sensitivityCompensation / 100.0);
            writeOptionValue(field, options, savedSensitivity * scale);
        } catch (Throwable t) {
            LauncherLog.err("[ZoomModule] applySensitivityScale: " + t);
        }
    }

    private void saveSensitivity(Object options) {
        try {
            Field field = sensitivityField(options);
            savedSensitivity = field != null ? readOptionValue(field, options) : -1;
        } catch (Throwable t) {
            savedSensitivity = -1;
        }
    }

    private void restoreSensitivity(Object options) {
        if (savedSensitivity < 0) return;
        try {
            Field field = sensitivityField(options);
            if (field != null) writeOptionValue(field, options, savedSensitivity);
        } catch (Throwable ignored) {
        }
        savedSensitivity = -1;
    }

    /**
     * Yarn "mouseSensitivity" (nom historique 1.8.9) → réel "sensitivity"
     * (vérifié par javap sur le jar client 26.1.2 : {@code OptionInstance<
     * Double> sensitivity}) — si la résolution échoue sur un bracket non
     * testé, {@link #applySensitivityScale} se dégrade silencieusement (le
     * zoom lui-même continue de fonctionner, seule la compensation de
     * sensibilité ne s'applique pas), même philosophie que le reste de ce
     * module face à un champ introuvable.
     */
    private Field sensitivityField(Object options) {
        return McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "mouseSensitivity", "sensitivity");
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

    private Object optionsInstance() throws Exception {
        Object mc = McReflect.minecraftClient();
        if (mc == null) return null;
        return McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
    }

    private Field fovField() throws Exception {
        Object options = optionsInstance();
        if (options == null) return null;
        return McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", "fov");
    }
}
