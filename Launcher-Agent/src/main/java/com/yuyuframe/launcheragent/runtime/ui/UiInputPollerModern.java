package com.yuyuframe.launcheragent.runtime.ui;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Implémentation LWJGL3/GLFW (1.13+, dont 1.21) de UiInputPoller — voir
 * UiInputPoller pour la note sur le flip Y requis (pas encore fait).
 */
public final class UiInputPollerModern extends UiInputPoller {

    private final long windowHandle;
    private final ClassLoader gameClassLoader;
    private final Map<String, Method> glfwMethods = new HashMap<>();

    public UiInputPollerModern(long windowHandle, ClassLoader gameClassLoader) {
        this.windowHandle = windowHandle;
        this.gameClassLoader = gameClassLoader;
    }

    @Override
    protected void readState() throws Exception {
        double[] x = new double[1];
        double[] y = new double[1];
        glfwGetCursorPos(windowHandle, x, y);
        mouseX = x[0];
        mouseY = y[0]; // TODO flip (viewportHeight - y) avant usage dans UiRenderer — voir UiInputPoller

        leftDown = glfwGetMouseButton(windowHandle, 0) == 1;  // GLFW_MOUSE_BUTTON_LEFT
        rightDown = glfwGetMouseButton(windowHandle, 1) == 1; // GLFW_MOUSE_BUTTON_RIGHT
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

    private int glfwGetMouseButton(long handle, int button) throws Exception {
        return (int) glfw("glfwGetMouseButton", long.class, int.class).invoke(null, handle, button);
    }
}
