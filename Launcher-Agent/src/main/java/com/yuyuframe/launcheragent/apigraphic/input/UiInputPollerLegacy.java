package com.yuyuframe.launcheragent.apigraphic.input;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

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

        // Keyboard.KEY_LSHIFT=42, KEY_RSHIFT=54 (constantes LWJGL2, stables, jamais obfusquées).
        shiftDown = (boolean) keyboardClass().getMethod("isKeyDown", int.class).invoke(null, 42)
            || (boolean) keyboardClass().getMethod("isKeyDown", int.class).invoke(null, 54);
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

    // Codes résolus paresseusement par réflexion sur les constantes PUBLIQUES
    // de org.lwjgl.input.Keyboard (jamais codés en dur — mêmes noms que la
    // doc LWJGL2 officielle, stables depuis toujours sur cette lib figée).
    private Integer keyBackCode, keyDeleteCode, keyReturnCode, keyNumpadEnterCode;
    private Integer keyLeftCode, keyRightCode, keyHomeCode, keyEndCode;
    private Integer keyLShiftCode, keyRShiftCode;

    private void resolveTextEditKeyCodes(Class<?> kc) throws Exception {
        if (keyBackCode != null) return;
        keyBackCode = kc.getField("KEY_BACK").getInt(null);
        keyDeleteCode = kc.getField("KEY_DELETE").getInt(null);
        keyReturnCode = kc.getField("KEY_RETURN").getInt(null);
        keyNumpadEnterCode = kc.getField("KEY_NUMPADENTER").getInt(null);
        keyLeftCode = kc.getField("KEY_LEFT").getInt(null);
        keyRightCode = kc.getField("KEY_RIGHT").getInt(null);
        keyHomeCode = kc.getField("KEY_HOME").getInt(null);
        keyEndCode = kc.getField("KEY_END").getInt(null);
        keyLShiftCode = kc.getField("KEY_LSHIFT").getInt(null);
        keyRShiftCode = kc.getField("KEY_RSHIFT").getInt(null);
    }

    /**
     * Même file d'événements que pollAnyKeyJustPressed() (Keyboard.next()) —
     * drainée ICI en une seule passe pour TOUT ce qui a besoin d'un événement
     * discret (caractères tapés, Entrée, Origine/Fin, Ctrl+A/C/X/V), pour ne
     * jamais entrer en conflit avec elle (voir javadoc de
     * {@link UiInputPoller#pollTextEdit()}). Backspace/Suppr/flèches, EUX,
     * sont lus en ÉTAT CONTINU (isKeyDown), PAS via cette file — nécessaire
     * pour la répétition typematic (voir {@link UiInputPoller#keyRepeatFire})
     * : la file ne donne qu'un événement par appui/relâchement, jamais "reste
     * enfoncée depuis 400ms". Donc ignorés explicitement s'ils apparaissent
     * dans la file (déjà traités via isKeyDown ci-dessous, sinon double
     * traitement). getEventCharacter() tient compte du layout clavier
     * (AZERTY/QWERTY, Shift) contrairement à getKeyName() — bonne source pour
     * du texte tapé, pas pour un nom de touche de rebind.
     */
    @Override
    public void pollTextEdit() {
        editTyped = "";
        editBackspace = editDelete = editLeft = editRight = editHome = editEnd = false;
        editEnter = editSelectAll = editCopy = editCut = editPaste = editShiftHeld = false;
        try {
            Class<?> kc = keyboardClass();
            resolveTextEditKeyCodes(kc);
            Method isKeyDown = kc.getMethod("isKeyDown", int.class);
            editShiftHeld = (boolean) isKeyDown.invoke(null, keyLShiftCode) || (boolean) isKeyDown.invoke(null, keyRShiftCode);

            Method next = kc.getMethod("next");
            Method eventKey = kc.getMethod("getEventKey");
            Method eventChar = kc.getMethod("getEventCharacter");
            Method eventKeyState = kc.getMethod("getEventKeyState");
            StringBuilder typed = new StringBuilder();
            while ((boolean) next.invoke(null)) {
                if (!(boolean) eventKeyState.invoke(null)) continue; // touche RELÂCHÉE — ignorée
                int code = (int) eventKey.invoke(null);
                if (code == keyBackCode || code == keyDeleteCode || code == keyLeftCode || code == keyRightCode) {
                    continue; // gérés en continu ci-dessous (répétition), jamais ici
                }
                if (code == keyReturnCode || code == keyNumpadEnterCode) { editEnter = true; continue; }
                if (code == keyHomeCode) { editHome = true; continue; }
                if (code == keyEndCode) { editEnd = true; continue; }
                char c = (char) eventChar.invoke(null);
                // Ctrl+A/C/X/V — détectés via les codes de contrôle ASCII
                // classiques (1/3/24/22) que Windows produit pour Ctrl+lettre,
                // INDÉPENDANTS du layout clavier — BUG TROUVÉ (clavier AZERTY
                // français) : les scancodes KEY_A/KEY_C/KEY_V/KEY_X de LWJGL2
                // sont des POSITIONS PHYSIQUES calées QWERTY (la touche "A"
                // physique sur AZERTY est en position "Q"), donc jamais
                // déclenchés par la touche réellement labellisée "A". Même
                // correctif que côté GLFW/Modern (physicalKeyForLetter), sous
                // une forme différente adaptée à LWJGL2 (pas d'équivalent
                // glfwGetKeyName ici).
                if (c == 1) { editSelectAll = true; continue; }
                if (c == 3) { editCopy = true; continue; }
                if (c == 24) { editCut = true; continue; }
                if (c == 22) { editPaste = true; continue; }
                if (c >= 32 && c != 127) typed.append(c);
            }
            editTyped = typed.toString();

            editBackspace = keyRepeatFire("legacy.backspace", (boolean) isKeyDown.invoke(null, keyBackCode));
            editDelete = keyRepeatFire("legacy.delete", (boolean) isKeyDown.invoke(null, keyDeleteCode));
            editLeft = keyRepeatFire("legacy.left", (boolean) isKeyDown.invoke(null, keyLeftCode));
            editRight = keyRepeatFire("legacy.right", (boolean) isKeyDown.invoke(null, keyRightCode));
        } catch (Exception e) {
            LauncherLog.err("[UiInputPollerLegacy] pollTextEdit: " + e);
        }
    }
}
