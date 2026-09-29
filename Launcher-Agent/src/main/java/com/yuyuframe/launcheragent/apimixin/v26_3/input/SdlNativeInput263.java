package com.yuyuframe.launcheragent.apimixin.v26_3.input;

import com.mojang.blaze3d.platform.TextInputManager;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.NativeInput;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.MinecraftAccessor263;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Couche native SDL3 du poller d'entrée — 26.3, où Minecraft a remplacé GLFW
 * par SDL3 ({@code lwjgl-sdl} 3.4.3 ; {@code lwjgl-glfw} n'est plus livré).
 *
 * <p>Lectures instantanées : les fonctions SDL qui correspondent une à une à
 * celles que le poller demandait à GLFW (curseur, taille fenêtre, taille en
 * pixels, état du clavier, nom d'une touche) — le jeu emploie les mêmes
 * ({@code MouseHandler}, {@code Window}, {@code InputConstants}). Les codes
 * entrent et sortent en GLFW, via {@link SdlKeys263}.
 *
 * <p>Les ÉVÉNEMENTS n'arrivent pas ici : voir {@link KeyboardInputMixin263} et
 * {@link MouseInputMixin263}.
 *
 * <p>Fil de rendu uniquement, comme le poller. Tampons natifs alloués une
 * fois : LWJGL exige des tampons directs.
 */
public final class SdlNativeInput263 implements NativeInput {

    private final long window;
    private final Object minecraft;

    private final FloatBuffer cursorX = floatBuffer(), cursorY = floatBuffer();
    private final IntBuffer sizeW = intBuffer(), sizeH = intBuffer();

    /** Saisie de texte SDL démarrée par NOUS (propriétaire = cet objet) — voir {@link #frame()}. */
    private boolean textInputStarted;

    public SdlNativeInput263(long window, Object minecraft) {
        this.window = window;
        this.minecraft = minecraft;
    }

    @Override
    public void cursorPos(double[] xOut, double[] yOut) {
        SDLMouse.SDL_GetMouseState(cursorX, cursorY);
        xOut[0] = cursorX.get(0);
        yOut[0] = cursorY.get(0);
    }

    @Override
    public void windowSize(int[] wOut, int[] hOut) {
        SDLVideo.SDL_GetWindowSize(window, sizeW, sizeH);
        wOut[0] = sizeW.get(0);
        hOut[0] = sizeH.get(0);
    }

    @Override
    public void framebufferSize(int[] wOut, int[] hOut) {
        SDLVideo.SDL_GetWindowSizeInPixels(window, sizeW, sizeH);
        wOut[0] = sizeW.get(0);
        hOut[0] = sizeH.get(0);
    }

    @Override
    public boolean isKeyDown(int glfwKey) {
        int scancode = SdlKeys263.toSdl(glfwKey);
        if (scancode < 0) return false;
        ByteBuffer state = SDLKeyboard.SDL_GetKeyboardState();
        return state != null && scancode < state.capacity() && state.get(scancode) != 0;
    }

    /**
     * Libellé IMPRIMABLE seulement, comme {@code glfwGetKeyName} : SDL nomme
     * aussi les touches de fonction (« Return », « Left Shift »…), que le
     * poller nomme lui-même par sa table — on ne garde donc qu'un caractère
     * unique, le test que fait {@code InputConstants$Type} (javap). Même
     * appel que le jeu : touche produite par ce scancode, sans modificateur.
     */
    @Override
    public String keyName(int glfwKey) {
        int scancode = SdlKeys263.toSdl(glfwKey);
        if (scancode < 0) return null;
        try {
            String name = SDLKeyboard.SDL_GetKeyName(SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) 0, false));
            if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) != 1) return null;
            return name;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int toGlfwKey(int sdlScancode) {
        return SdlKeys263.toGlfw(sdlScancode);
    }

    @Override
    public int toGlfwButton(int sdlButton) {
        return SdlKeys263.buttonToGlfw(sdlButton);
    }

    /**
     * Saisie de texte : démarrée dès qu'un champ de l'agent a le focus
     * ({@link UiInputPoller#textInputActive}), arrêtée quand il le perd.
     * Propriétaire = cet objet : {@code stopTextInput(owner)} n'arrête que si
     * nous sommes toujours propriétaires — un champ vanilla qui aurait pris la
     * main entre-temps n'est pas coupé.
     */
    @Override
    public void frame() {
        boolean wanted = UiInputPoller.textInputActive;
        if (wanted == textInputStarted) return;
        try {
            TextInputManager manager = minecraft instanceof MinecraftAccessor263
                ? ((MinecraftAccessor263) minecraft).la$textInputManager() : null;
            if (!(manager instanceof TextInputManagerInvoker263)) return;
            if (wanted) ((TextInputManagerInvoker263) manager).la$startTextInput(this);
            else ((TextInputManagerInvoker263) manager).la$stopTextInput(this);
            textInputStarted = wanted;
        } catch (Throwable t) {
            LauncherLog.err("[SdlNativeInput263] saisie de texte (" + (wanted ? "start" : "stop") + "): " + t);
            textInputStarted = wanted; // pas de nouvel essai à chaque image
        }
    }

    private static FloatBuffer floatBuffer() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static IntBuffer intBuffer() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }
}
