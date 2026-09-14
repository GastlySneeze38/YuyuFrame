package com.yuyuframe.launcheragent.apimixin.v1_8_9.core;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * État OpenGL de Minecraft 1.8.9, capturé avant que l'agent dessine et remis
 * tel quel après — UNE fois par frame, autour de tout le dessin du hub.
 *
 * <h2>Pourquoi</h2>
 *
 * Depuis le passage à l'ère gl3 (2026-09-14), l'agent dessine en shaders
 * {@code #version 150} dans le contexte 3.2 de compatibilité ouvert par la
 * couche LWJGL 3. Or la 1.8.9 rend en pipeline fixe à travers
 * {@code GlStateManager}, qui garde en CACHE l'état qu'il croit actif (blend,
 * test de profondeur, texture liée, unité active…) et saute l'appel GL quand
 * le cache dit « déjà fait ». Chaque changement fait par nos primitives sans
 * passer par lui désynchronise ce cache : texture fausse sur les items,
 * transparence cassée, etc. {@code GlBridge} route bien ses liaisons de
 * texture vers {@code GlStateManager}, mais ne trouve pas la classe en 1.8.9
 * (obfusquée {@code bfl}).
 *
 * <p>Plutôt que de corriger chaque primitive, on rend au jeu EXACTEMENT l'état
 * GL réel qu'il avait : son cache redevient vrai. L'ère gl2 le faisait
 * primitive par primitive ({@code captureLegacyGlState}, six drapeaux
 * seulement) ; ici c'est fait une fois, et couvre aussi ce que les shaders
 * touchent (programme, VAO, tampon, unité de texture, masques, scissor).
 *
 * <p>Appels LWJGL 3 typés — compilé contre les jars LWJGL 3 (unité 1.8.9 de
 * {@code build.bat}), aucune réflexion.
 */
final class GlFrameState189 {

    private static final int[] CAPS = {
        GL11.GL_TEXTURE_2D,
        GL11.GL_DEPTH_TEST,
        GL11.GL_CULL_FACE,
        GL11.GL_ALPHA_TEST,
        GL11.GL_SCISSOR_TEST,
        GL11.GL_BLEND,
        GL11.GL_STENCIL_TEST,
    };

    private final boolean[] enabled = new boolean[CAPS.length];
    private int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha;
    private boolean depthMask;
    private final boolean[] colorMask = new boolean[4];
    private final int[] scissorBox = new int[4];
    private int activeTexture, texture2dUnit0, program, vertexArray, arrayBuffer;

    void capture() {
        for (int i = 0; i < CAPS.length; i++) enabled[i] = GL11.glIsEnabled(CAPS[i]);
        blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
        }
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        // Nos primitives ne lient des textures que sur l'unité 0.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        texture2dUnit0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(activeTexture);
    }

    void restore() {
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture2dUnit0);
        GL13.glActiveTexture(activeTexture);
        for (int i = 0; i < CAPS.length; i++) {
            if (enabled[i]) GL11.glEnable(CAPS[i]);
            else GL11.glDisable(CAPS[i]);
        }
        GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        GL11.glDepthMask(depthMask);
        GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
    }
}
