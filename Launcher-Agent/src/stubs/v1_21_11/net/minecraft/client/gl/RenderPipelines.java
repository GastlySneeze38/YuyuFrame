package net.minecraft.client.gl;

import com.mojang.blaze3d.pipeline.RenderPipeline;

/** Stub compile-only 1.21.11, nom Yarn ({@code hpa}) — voir {@code com.mojang.blaze3d.systems.RenderSystem}. */
public class RenderPipelines {

    public static final RenderPipeline GUI_TEXT;
    public static final RenderPipeline GUI;
    public static final RenderPipeline GUI_TEXTURED;

    static {
        GUI_TEXT = stub();
        GUI = stub();
        GUI_TEXTURED = stub();
    }

    private RenderPipelines() {
    }

    private static RenderPipeline stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
