package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPollerModern;

import java.lang.reflect.Field;

/**
 * Zoom façon Essential Mod — touche maintenue réduit immédiatement le FOV à un
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
 */
public final class ZoomModule extends LauncherModule {

    @ConfigKeybind(name = "Touche de zoom", category = "Réglages")
    public String zoomKey = "C";

    @ConfigSlider(name = "FOV en zoom (base)", description = "Niveau de zoom appliqué immédiatement à l'appui sur la touche — voir \"FOV en zoom max\" pour la limite atteignable en scrollant.",
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

    private String cachedKeyName;
    private int cachedKeyCode = -1;
    private boolean zooming;
    private double savedFov = -1;
    /** >= 0 — combien on a scrollé AU-DELÀ de la base cette session de zoom (voir javadoc de classe). Remis à 0 à chaque nouvel appui. */
    private double scrollOffsetFov = 0;

    public ZoomModule() {
        super("zoom", "Zoom", "Maintenir une touche réduit temporairement le FOV, scroller pendant le zoom pour aller plus loin (façon Essential)", false);
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
                savedFov = readFov(fovField, options);
                scrollOffsetFov = 0;
            } else if (!down && zooming) {
                zooming = false;
                writeFov(fovField, options, savedFov);
                savedFov = -1;
                scrollOffsetFov = 0;
                return;
            }

            if (zooming) {
                // Molette vers le haut (delta > 0, convention déjà établie
                // par UiInputPoller.scrollDelta) = zoome PLUS = FOV plus
                // petit — scrollOffsetFov reste >= 0, jamais négatif (la
                // base zoomFov est le plancher "le moins zoomé" tant que la
                // touche est maintenue, voir javadoc de classe).
                int scroll = readScrollDelta();
                if (scroll != 0) {
                    scrollOffsetFov = Math.max(0, scrollOffsetFov + scroll * zoomScrollStep);
                }
                double maxOffset = Math.max(0, zoomFov - zoomFovMin);
                double effectiveFov = zoomFov - Math.min(scrollOffsetFov, maxOffset);
                writeFov(fovField, options, effectiveFov);
            }
        } catch (Throwable t) {
            LauncherLog.err("[ZoomModule] onTick: " + t);
        }
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        if (zooming && savedFov >= 0) {
            try {
                Field fovField = fovField();
                if (fovField != null) writeFov(fovField, optionsInstance(), savedFov);
            } catch (Throwable ignored) {
            }
        }
        zooming = false;
        savedFov = -1;
        scrollOffsetFov = 0;
    }

    /**
     * Modern : UiInputPollerModern.ACTIVE.scrollDelta (déjà câblé sur le
     * callback GLFW réel, voir sa javadoc — consommé une fois par frame par
     * poll(), donc lu ICI directement en champ, pas re-consommé). Legacy
     * (1.8.9, LWJGL2, pas de UiInputPoller pour ce bracket dans ce module —
     * voir isZoomKeyDown) : org.lwjgl.input.Mouse.getDWheel(), l'équivalent
     * LWJGL2 — retourne un delta déjà cru (typiquement multiples de 120 par
     * cran), divisé pour retomber sur "un cran = un pas" comme le modern.
     */
    private int readScrollDelta() {
        try {
            UiInputPollerModern modern = UiInputPollerModern.ACTIVE;
            if (modern != null) return modern.scrollDelta;

            Class<?> mouse = McReflect.rawClass("org.lwjgl.input.Mouse");
            int raw = (int) McReflect.rawMethod(mouse, "getDWheel").invoke(null);
            return raw / 120;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * BUG TROUVÉ (audit modules, voir historique de session) : {@code
     * GameOptions.fov} est un {@code float} en 1.8.9 mais un {@code double}
     * en 1.13-1.16.5 (vérifié dans les mappings 1.16.5 : {@code f D aO
     * field_1826 fov}) — {@code getFloat}/{@code setFloat} lève {@code
     * IllegalArgumentException} sur ce dernier, avalée silencieusement,
     * zoom totalement inopérant. Lit le VRAI type du champ au lieu de
     * supposer, fonctionne sur les deux.
     *
     * BUG TROUVÉ #2 (1.20.4, test utilisateur) : depuis la refonte
     * "SimpleOption" (~1.19-1.20), {@code fov} n'est même plus un float/double
     * DU TOUT — c'est un objet {@code SimpleOption} FINAL (vérifié : {@code f
     * Levl; bM field_1826 fov}) — voir {@link McReflect#simpleOptionGetValue}/
     * {@link McReflect#simpleOptionSetValue} pour le repli.
     */
    private double readFov(Field fovField, Object options) throws Exception {
        if (fovField.getType() == double.class) return fovField.getDouble(options);
        if (fovField.getType() == float.class) return fovField.getFloat(options);
        return McReflect.simpleOptionGetValue(fovField.get(options));
    }

    private void writeFov(Field fovField, Object options, double value) throws Exception {
        if (fovField.getType() == double.class) { fovField.setDouble(options, value); return; }
        if (fovField.getType() == float.class) { fovField.setFloat(options, (float) value); return; }
        McReflect.simpleOptionSetValue(fovField.get(options), value);
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
