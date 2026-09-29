package com.yuyuframe.launcheragent.apimixin.v26_3.input;

/**
 * Scancodes SDL3 ↔ codes de touche GLFW — 26.3.
 *
 * <p>En 26.3, Minecraft est passé de GLFW à SDL3 et ses codes de touche sont
 * des SCANCODES SDL ({@code InputConstants.KEY_A} = 4, {@code KEY_LSHIFT} =
 * 225, {@code KeyEvent.key()}, {@code InputConstants.Key.getValue()}). Le
 * reste de l'agent parle GLFW (écrans, modules, réglages enregistrés par
 * nom). Les deux désignent la MÊME chose — une position physique, nommée
 * d'après le clavier US —, la correspondance est donc exacte touche pour
 * touche.
 *
 * <p>Valeurs SDL relues dans {@code lwjgl-sdl-3.4.3.jar}
 * ({@code SDLScancode}, javap -constants) ; valeurs GLFW : celles de
 * {@code UiInputPollerModern.CAPTURABLE_KEYS}, relues dans lwjgl-glfw.
 * Seules les touches qu'un clavier courant peut produire figurent ici ; les
 * autres n'ont pas de code GLFW et sont suivies par scancode
 * ({@code SCAN<n>}), comme sous GLFW.
 *
 * <p>PUBLIQUE, méthodes comprises — obligatoire : le corps des mixins
 * {@code input/} est recopié DANS {@code MouseHandler}/{@code KeyboardHandler}
 * (paquet {@code net.minecraft.client}), d'où il appelle cette classe. Une
 * classe package-private y lève {@code IllegalAccessError} au premier clic
 * (crash du 2026-09-29, v1202).
 */
public final class SdlKeys263 {

    private SdlKeys263() {
    }

    /** Index = scancode SDL, valeur = code GLFW (−1 : pas d'équivalent). */
    private static final int[] SDL_TO_GLFW = new int[512];
    /** Index = code GLFW, valeur = scancode SDL (−1 : pas d'équivalent). */
    private static final int[] GLFW_TO_SDL = new int[350];

    static {
        java.util.Arrays.fill(SDL_TO_GLFW, -1);
        java.util.Arrays.fill(GLFW_TO_SDL, -1);
        // Lettres : SDL A..Z = 4..29, GLFW A..Z = 65..90.
        for (int i = 0; i < 26; i++) put(4 + i, 65 + i);
        // Rangée de chiffres : SDL 1..9 = 30..38 et 0 = 39 ; GLFW 0..9 = 48..57.
        for (int i = 1; i <= 9; i++) put(29 + i, 48 + i);
        put(39, 48);
        put(40, 257);  // RETURN → ENTER
        put(41, 256);  // ESCAPE
        put(42, 259);  // BACKSPACE
        put(43, 258);  // TAB
        put(44, 32);   // SPACE
        put(45, 45);   // MINUS
        put(46, 61);   // EQUALS → EQUAL
        put(47, 91);   // LEFTBRACKET
        put(48, 93);   // RIGHTBRACKET
        put(49, 92);   // BACKSLASH
        put(50, 92);   // NONUSHASH — même touche que BACKSLASH sous GLFW (sens SDL → GLFW seulement)
        put(51, 59);   // SEMICOLON
        put(52, 39);   // APOSTROPHE
        put(53, 96);   // GRAVE
        put(54, 44);   // COMMA
        put(55, 46);   // PERIOD
        put(56, 47);   // SLASH
        put(57, 280);  // CAPSLOCK
        for (int i = 0; i < 12; i++) put(58 + i, 290 + i);  // F1..F12
        put(70, 283);  // PRINTSCREEN
        put(71, 281);  // SCROLLLOCK
        put(72, 284);  // PAUSE
        put(73, 260);  // INSERT
        put(74, 268);  // HOME
        put(75, 266);  // PAGEUP
        put(76, 261);  // DELETE
        put(77, 269);  // END
        put(78, 267);  // PAGEDOWN
        put(79, 262);  // RIGHT
        put(80, 263);  // LEFT
        put(81, 264);  // DOWN
        put(82, 265);  // UP
        put(83, 282);  // NUMLOCKCLEAR → NUM_LOCK
        put(84, 331);  // KP_DIVIDE
        put(85, 332);  // KP_MULTIPLY
        put(86, 333);  // KP_MINUS → KP_SUBTRACT
        put(87, 334);  // KP_PLUS → KP_ADD
        put(88, 335);  // KP_ENTER
        // Pavé : SDL KP_1..KP_9 = 89..97, KP_0 = 98 ; GLFW KP_0..KP_9 = 320..329.
        for (int i = 1; i <= 9; i++) put(88 + i, 320 + i);
        put(98, 320);
        put(99, 330);  // KP_PERIOD → KP_DECIMAL
        put(100, 161); // NONUSBACKSLASH → WORLD_1 (la touche < > des claviers ISO)
        put(101, 348); // APPLICATION → MENU
        put(103, 336); // KP_EQUALS → KP_EQUAL
        // F13..F24 : SDL 104..115, GLFW 302..313 (GLFW va jusqu'à F25, SDL F24).
        for (int i = 0; i < 12; i++) put(104 + i, 302 + i);
        put(224, 341); // LCTRL → LEFT_CONTROL
        put(225, 340); // LSHIFT → LEFT_SHIFT
        put(226, 342); // LALT → LEFT_ALT
        put(227, 343); // LGUI → LEFT_SUPER
        put(228, 345); // RCTRL → RIGHT_CONTROL
        put(229, 344); // RSHIFT → RIGHT_SHIFT
        put(230, 346); // RALT → RIGHT_ALT
        put(231, 347); // RGUI → RIGHT_SUPER
    }

    /** Inscrit un couple ; le premier scancode inscrit pour un code GLFW reste celui du sens inverse. */
    private static void put(int sdlScancode, int glfwKey) {
        SDL_TO_GLFW[sdlScancode] = glfwKey;
        if (GLFW_TO_SDL[glfwKey] < 0) GLFW_TO_SDL[glfwKey] = sdlScancode;
    }

    public static int toGlfw(int sdlScancode) {
        return sdlScancode >= 0 && sdlScancode < SDL_TO_GLFW.length ? SDL_TO_GLFW[sdlScancode] : -1;
    }

    public static int toSdl(int glfwKey) {
        return glfwKey >= 0 && glfwKey < GLFW_TO_SDL.length ? GLFW_TO_SDL[glfwKey] : -1;
    }

    /**
     * Bouton SDL → index GLFW. SDL : 1 gauche, 2 milieu, 3 droit, 4/5
     * latéraux ({@code SDL_BUTTON_*}) ; GLFW : 0 gauche, 1 droit, 2 milieu,
     * 3/4 latéraux. Milieu et droit sont INVERSÉS d'une numérotation à l'autre.
     */
    public static int buttonToGlfw(int sdlButton) {
        switch (sdlButton) {
            case 1: return 0;
            case 2: return 2;
            case 3: return 1;
            default: return sdlButton >= 4 && sdlButton <= 8 ? sdlButton - 1 : -1;
        }
    }
}
