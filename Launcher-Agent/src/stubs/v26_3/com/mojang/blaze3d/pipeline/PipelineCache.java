package com.mojang.blaze3d.pipeline;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>Nouveau en 26.3 : table {@code RenderPipeline → CompiledRenderPipeline}
 * liée à UNE source de shaders (celle du jeu). {@code get} compile à la
 * demande avec cette source — qui ne connaît pas nos shaders —, d'où
 * {@code insert} : on y range nous-mêmes le pipeline compilé avec notre
 * source, avant que le {@code GuiRenderer} vanilla ne le demande.
 * Relevé par javap.
 */
public class PipelineCache {

    private PipelineCache() {
    }

    public void insert(RenderPipeline pipeline, CompiledRenderPipeline compiled) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
