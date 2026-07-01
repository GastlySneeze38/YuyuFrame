package com.yuyuframe.launcheragent.runtime.ui;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Implémentation LWJGL3/GLFW (1.13+, dont 1.21) de UiInputPoller.
 *
 * glfwGetCursorPos() renvoie des coordonnées "fenêtre" (origine HAUT-gauche),
 * alors que gl_FragCoord (utilisé par UiRenderer) est en pixels FRAMEBUFFER
 * (origine BAS-gauche) — deux conversions nécessaires, pas juste un flip :
 *   1. Passage fenêtre → framebuffer : les deux peuvent différer sur un écran
 *      HiDPI/Retina (framebuffer = fenêtre × content-scale), d'où le ratio
 *      framebufferSize/windowSize appliqué aux deux axes.
 *   2. Flip Y : uniquement après la mise à l'échelle, sur la hauteur
 *      framebuffer (pas la hauteur fenêtre).
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
        double[] cx = new double[1];
        double[] cy = new double[1];
        glfwGetCursorPos(windowHandle, cx, cy);

        int[] winW = new int[1], winH = new int[1];
        glfwGetWindowSize(windowHandle, winW, winH);
        int[] fbW = new int[1], fbH = new int[1];
        glfwGetFramebufferSize(windowHandle, fbW, fbH);

        // winW/winH peuvent valoir 0 juste après création de fenêtre — repli
        // sans mise à l'échelle (ratio 1) plutôt qu'une division par zéro.
        double scaleX = winW[0] > 0 ? (double) fbW[0] / winW[0] : 1.0;
        double scaleY = winH[0] > 0 ? (double) fbH[0] / winH[0] : 1.0;

        mouseX = cx[0] * scaleX;
        mouseY = fbH[0] - (cy[0] * scaleY); // flip après mise à l'échelle, sur la hauteur framebuffer

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

    private void glfwGetWindowSize(long handle, int[] wOut, int[] hOut) throws Exception {
        glfw("glfwGetWindowSize", long.class, int[].class, int[].class).invoke(null, handle, wOut, hOut);
    }

    private void glfwGetFramebufferSize(long handle, int[] wOut, int[] hOut) throws Exception {
        glfw("glfwGetFramebufferSize", long.class, int[].class, int[].class).invoke(null, handle, wOut, hOut);
    }

    private int glfwGetMouseButton(long handle, int button) throws Exception {
        return (int) glfw("glfwGetMouseButton", long.class, int.class).invoke(null, handle, button);
    }
}
