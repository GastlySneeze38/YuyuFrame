package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPollerModern;

import java.lang.reflect.Field;

/**
 * Zoom façon OptiFine — touche maintenue réduit temporairement le FOV, relâchée
 * restaure la valeur d'avant. Vanilla 1.8.9 n'a pas de KeyBinding "zoom" dédié
 * (contrairement à sneak/sprint, voir MixinToggleSneak189/MixinToggleSprint189)
 * donc pas de champ GameOptions à rediriger — la touche est pollée directement
 * via org.lwjgl.input.Keyboard (API publique LWJGL2, pas obfusquée), même
 * mécanisme que UiInputPollerLegacy.readMenuKeyDown pour une touche configurable.
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

    @ConfigSlider(name = "FOV en zoom", category = "Réglages", min = 5f, max = 60f, step = 1f)
    public float zoomFov = 20f;

    private String cachedKeyName;
    private int cachedKeyCode = -1;
    private boolean zooming;
    private double savedFov = -1;

    public ZoomModule() {
        super("zoom", "Zoom", "Maintenir une touche réduit temporairement le FOV (façon OptiFine)", false);
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
            } else if (!down && zooming) {
                zooming = false;
                writeFov(fovField, options, savedFov);
                savedFov = -1;
                return;
            }

            if (zooming) writeFov(fovField, options, zoomFov);
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
    }

    /**
     * BUG TROUVÉ (audit modules, voir historique de session) : {@code
     * GameOptions.fov} est un {@code float} en 1.8.9 mais un {@code double}
     * en 1.13-1.16.5 (vérifié dans les mappings 1.16.5 : {@code f D aO
     * field_1826 fov}) — {@code getFloat}/{@code setFloat} lève {@code
     * IllegalArgumentException} sur ce dernier, avalée silencieusement,
     * zoom totalement inopérant. Lit le VRAI type du champ au lieu de
     * supposer, fonctionne sur les deux.
     */
    private double readFov(Field fovField, Object options) throws Exception {
        return fovField.getType() == double.class ? fovField.getDouble(options) : fovField.getFloat(options);
    }

    private void writeFov(Field fovField, Object options, double value) throws Exception {
        if (fovField.getType() == double.class) fovField.setDouble(options, value);
        else fovField.setFloat(options, (float) value);
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
