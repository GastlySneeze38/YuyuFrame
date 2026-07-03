package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

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
    private Class<?> displayClass;
    private Class<?> keyboardClass;
    private String cachedMenuKeyName;
    private int cachedMenuKeyCode = -1;

    public UiInputPollerLegacy(ClassLoader gameClassLoader) {
        this.gameClassLoader = gameClassLoader;
    }

    @Override
    protected void readState() throws Exception {
        mouseX = getX();
        mouseY = getY();
        fbWidth = getDisplayWidth();
        fbHeight = getDisplayHeight();
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

    // ── org.lwjgl.opengl.Display (API publique, pas obfusquée) ──

    private Class<?> displayClass() throws Exception {
        if (displayClass == null) {
            displayClass = Class.forName("org.lwjgl.opengl.Display", true, gameClassLoader);
        }
        return displayClass;
    }

    private int getDisplayWidth() throws Exception {
        return (int) displayClass().getMethod("getWidth").invoke(null);
    }

    private int getDisplayHeight() throws Exception {
        return (int) displayClass().getMethod("getHeight").invoke(null);
    }

    @Override
    protected int readScrollDelta() throws Exception {
        // Mouse.getDWheel() : delta consommé depuis le dernier appel (pas un
        // état absolu), en multiples de 120 (convention Windows WHEEL_DELTA)
        // — divisé ici pour renvoyer un nombre de "crans", même unité que
        // UiInputPollerModern (GLFW yoffset ≈ ±1/cran), afin que
        // UiScrollContainer applique un seul facteur d'échelle pour les deux versions.
        return (int) mouse("getDWheel").invoke(null) / 120;
    }

    // ── org.lwjgl.input.Keyboard (API publique, pas obfusquée) ──

    @Override
    protected boolean readMenuKeyDown() throws Exception {
        String wanted = menuKeyName;
        if (!wanted.equals(cachedMenuKeyName)) {
            // getKeyIndex(String) est l'inverse de getKeyName(int) (déjà
            // utilisé par pollAnyKeyJustPressed) — même format de nom des deux
            // côtés, donc réutilisable tel quel pour une touche configurable
            // (voir UiInputPoller.menuKeyName, écrit par GlobalUiSettings).
            cachedMenuKeyName = wanted;
            cachedMenuKeyCode = (int) keyboardClass().getMethod("getKeyIndex", String.class).invoke(null, wanted);
        }
        if (cachedMenuKeyCode < 0) return false;
        return (boolean) keyboardClass().getMethod("isKeyDown", int.class).invoke(null, cachedMenuKeyCode);
    }

    /**
     * File d'événements Keyboard.next()/getEventKey()/getEventKeyState() —
     * déjà alimentée par le poll interne que Minecraft fait chaque frame
     * (même hypothèse que pour Mouse.getX/getY, confirmée fonctionnelle en
     * jeu) : on ne rappelle jamais Keyboard.poll() nous-mêmes. getKeyName()
     * renvoie directement un nom lisible ("A", "F1", "SPACE", "LSHIFT"...),
     * pas besoin de table de correspondance manuelle côté legacy.
     */
    @Override
    public String pollAnyKeyJustPressed() {
        try {
            Class<?> kc = keyboardClass();
            Method next = kc.getMethod("next");
            Method eventKey = kc.getMethod("getEventKey");
            Method eventKeyState = kc.getMethod("getEventKeyState");
            Method keyName = kc.getMethod("getKeyName", int.class);
            while ((boolean) next.invoke(null)) {
                if ((boolean) eventKeyState.invoke(null)) {
                    int code = (int) eventKey.invoke(null);
                    return (String) keyName.invoke(null, code);
                }
            }
        } catch (Exception e) {
            LauncherLog.err("[UiInputPollerLegacy] pollAnyKeyJustPressed: " + e);
        }
        return null;
    }

    private Class<?> keyboardClass() throws Exception {
        if (keyboardClass == null) {
            keyboardClass = Class.forName("org.lwjgl.input.Keyboard", true, gameClassLoader);
        }
        return keyboardClass;
    }

    private Integer keyBackCode;

    /**
     * Même file d'événements que pollAnyKeyJustPressed() (Keyboard.next()) —
     * drainée ICI en une seule passe (caractère + Backspace) plutôt que dans
     * deux méthodes séparées, précisément pour ne jamais entrer en conflit
     * avec elle (voir javadoc UiInputPoller.pollTextEdit). getEventCharacter()
     * tient compte du layout clavier (AZERTY/QWERTY, Shift) contrairement à
     * getKeyName() — c'est la bonne source pour du texte tapé, pas pour un
     * nom de touche de rebind.
     */
    @Override
    public void pollTextEdit(StringBuilder buffer) {
        try {
            Class<?> kc = keyboardClass();
            if (keyBackCode == null) keyBackCode = kc.getField("KEY_BACK").getInt(null);
            Method next = kc.getMethod("next");
            Method eventKey = kc.getMethod("getEventKey");
            Method eventChar = kc.getMethod("getEventCharacter");
            Method eventKeyState = kc.getMethod("getEventKeyState");
            while ((boolean) next.invoke(null)) {
                if (!(boolean) eventKeyState.invoke(null)) continue; // touche RELÂCHÉE — ignorée
                int code = (int) eventKey.invoke(null);
                if (code == keyBackCode) {
                    if (buffer.length() > 0) buffer.deleteCharAt(buffer.length() - 1);
                    continue;
                }
                char c = (char) eventChar.invoke(null);
                if (c >= 32 && c != 127) buffer.append(c);
            }
        } catch (Exception e) {
            LauncherLog.err("[UiInputPollerLegacy] pollTextEdit: " + e);
        }
    }
}
