package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

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
    private float savedFov = -1f;

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
                savedFov = fovField.getFloat(options);
            } else if (!down && zooming) {
                zooming = false;
                fovField.setFloat(options, savedFov);
                savedFov = -1f;
                return;
            }

            if (zooming) fovField.setFloat(options, zoomFov);
        } catch (Throwable t) {
            LauncherLog.err("[ZoomModule] onTick: " + t);
        }
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        if (zooming && savedFov >= 0f) {
            try {
                Field fovField = fovField();
                if (fovField != null) fovField.setFloat(optionsInstance(), savedFov);
            } catch (Throwable ignored) {
            }
        }
        zooming = false;
        savedFov = -1f;
    }

    private boolean isZoomKeyDown() throws Exception {
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
