package com.yuyuframe.launcheragent.runtime.ui;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Implémentation LWJGL2 (1.8.9) de UiInputPoller — org.lwjgl.input.Mouse,
 * API publique globale (pas de handle de fenêtre, contrairement à GLFW).
 *
 * Mouse.getX()/getY() renvoient déjà des pixels physiques origine BAS-gauche
 * — mêmes unités que gl_FragCoord en GLSL (voir UiRenderer), donc AUCUN flip
 * Y nécessaire ici, contrairement à UiInputPollerModern (GLFW, haut-gauche).
 *
 * Boutons : isButtonDown(0)=gauche, isButtonDown(1)=droit — même convention
 * que GLFW_MOUSE_BUTTON_LEFT/RIGHT, coïncidence utile mais pas garantie par
 * la lib, vérifiée dans la doc LWJGL2 org.lwjgl.input.Mouse.
 */
public final class UiInputPollerLegacy extends UiInputPoller {

    private final ClassLoader gameClassLoader;
    private final Map<String, Method> mouseMethods = new HashMap<>();
    private Class<?> mouseClass;

    public UiInputPollerLegacy(ClassLoader gameClassLoader) {
        this.gameClassLoader = gameClassLoader;
    }

    @Override
    protected void readState() throws Exception {
        mouseX = getX();
        mouseY = getY();
        leftDown = isButtonDown(0);
        rightDown = isButtonDown(1);
    }

    // ── org.lwjgl.input.Mouse via réflexion (API publique, pas obfusquée) ──

    private Class<?> mouseClass() throws Exception {
        if (mouseClass == null) {
            mouseClass = Class.forName("org.lwjgl.input.Mouse", true, gameClassLoader);
        }
        return mouseClass;
    }

    private Method mouse(String name, Class<?>... params) throws Exception {
        String key = name + java.util.Arrays.toString(params);
        Method m = mouseMethods.get(key);
        if (m != null) return m;
        m = mouseClass().getMethod(name, params);
        mouseMethods.put(key, m);
        return m;
    }

    private int getX() throws Exception {
        return (int) mouse("getX").invoke(null);
    }

    private int getY() throws Exception {
        return (int) mouse("getY").invoke(null);
    }

    private boolean isButtonDown(int button) throws Exception {
        return (boolean) mouse("isButtonDown", int.class).invoke(null, button);
    }
}
