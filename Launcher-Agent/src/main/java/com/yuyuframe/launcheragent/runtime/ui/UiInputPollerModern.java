package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    // GLFW n'a pas d'état de molette pollable (contrairement à LWJGL2
    // Mouse.getDWheel()) — uniquement un callback. Accumulé ici entre deux
    // poll(), consommé/remis à zéro par readScrollDelta().
    private volatile double pendingScroll;
    private final Object[] previousScrollCb = new Object[1];

    // Touches "capturables" pour UiKeybindButton — codes GLFW standards (API
    // publique stable, pas obfusqués, littéraux sûrs comme les constantes GL
    // ailleurs dans ce package). Pas de callback clavier ici (contrairement à
    // la molette) : un simple scan isKeyDown/frame suffit et reste dans le
    // même style "poll" que le reste de cette classe, uniquement appelé par
    // le widget en mode écoute (jamais chaque frame inconditionnellement).
    private static final Object[][] CAPTURABLE_KEYS = buildCapturableKeys();
    private final Map<Integer, Boolean> prevKeyDown = new HashMap<>();

    public UiInputPollerModern(long windowHandle, ClassLoader gameClassLoader) {
        this.windowHandle = windowHandle;
        this.gameClassLoader = gameClassLoader;
        registerScrollCallback();
    }

    private static Object[][] buildCapturableKeys() {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < 26; i++) m.put(65 + i, String.valueOf((char) ('A' + i)));
        for (int i = 0; i <= 9; i++) m.put(48 + i, String.valueOf(i));
        for (int i = 0; i < 12; i++) m.put(290 + i, "F" + (i + 1));
        m.put(32, "SPACE"); m.put(257, "ENTER"); m.put(258, "TAB"); m.put(256, "ESCAPE");
        m.put(340, "LSHIFT"); m.put(344, "RSHIFT"); m.put(341, "LCTRL"); m.put(345, "RCTRL");
        m.put(342, "LALT"); m.put(346, "RALT");
        m.put(263, "LEFT"); m.put(262, "RIGHT"); m.put(265, "UP"); m.put(264, "DOWN");
        m.put(259, "BACKSPACE"); m.put(261, "DELETE"); m.put(280, "CAPSLOCK"); m.put(96, "GRAVE");
        Object[][] out = new Object[m.size()][2];
        int idx = 0;
        for (Map.Entry<Integer, String> e : m.entrySet()) out[idx++] = new Object[]{ e.getKey(), e.getValue() };
        return out;
    }

    /**
     * S'abonne au callback de molette GLFW en CHAÎNANT vers celui déjà en
     * place (retourné par glfwSetScrollCallback, ce qui remplace TOUJOURS le
     * précédent) — sans ça, on casserait silencieusement le scroll vanilla
     * (sélection hotbar, zoom longue-vue) partout dans le jeu, pas seulement
     * quand un de nos écrans custom est ouvert.
     */
    private void registerScrollCallback() {
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW", true, gameClassLoader);
            Class<?> cbIface = Class.forName("org.lwjgl.glfw.GLFWScrollCallbackI", true, gameClassLoader);
            Object proxy = Proxy.newProxyInstance(gameClassLoader, new Class[]{ cbIface }, (p, method, args) -> {
                if (args != null && args.length == 3 && "invoke".equals(method.getName())) {
                    pendingScroll += (Double) args[2];
                    Object prev = previousScrollCb[0];
                    if (prev != null) {
                        try { method.invoke(prev, args); } catch (Throwable ignored) {}
                    }
                }
                return null;
            });
            Method setCb = glfwClass.getMethod("glfwSetScrollCallback", long.class, cbIface);
            previousScrollCb[0] = setCb.invoke(null, windowHandle, proxy);
        } catch (Throwable t) {
            LauncherLog.err("[UiInputPollerModern] registerScrollCallback: " + t);
        }
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
        fbWidth = fbW[0];
        fbHeight = fbH[0];

        leftDown = glfwGetMouseButton(windowHandle, 0) == 1;  // GLFW_MOUSE_BUTTON_LEFT
        rightDown = glfwGetMouseButton(windowHandle, 1) == 1; // GLFW_MOUSE_BUTTON_RIGHT
    }

    @Override
    protected boolean readMenuKeyDown() throws Exception {
        return glfwGetKey(windowHandle, 344) == 1; // GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS
    }

    @Override
    protected synchronized int readScrollDelta() throws Exception {
        int delta = (int) Math.round(pendingScroll);
        pendingScroll = 0;
        return delta;
    }

    @Override
    public String pollAnyKeyJustPressed() {
        try {
            for (Object[] entry : CAPTURABLE_KEYS) {
                int code = (Integer) entry[0];
                boolean down = glfwGetKey(windowHandle, code) == 1;
                boolean was = Boolean.TRUE.equals(prevKeyDown.get(code));
                prevKeyDown.put(code, down);
                if (down && !was) return (String) entry[1];
            }
        } catch (Exception e) {
            LauncherLog.err("[UiInputPollerModern] pollAnyKeyJustPressed: " + e);
        }
        return null;
    }

    private int glfwGetKey(long handle, int key) throws Exception {
        return (int) glfw("glfwGetKey", long.class, int.class).invoke(null, handle, key);
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
