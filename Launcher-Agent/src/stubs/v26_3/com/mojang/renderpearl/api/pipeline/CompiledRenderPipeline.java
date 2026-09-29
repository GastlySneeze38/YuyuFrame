package com.mojang.renderpearl.api.pipeline;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : c'est désormais CE type que {@code RenderPass.setPipeline} attend.
 * On l'obtient par {@code GpuDevice.compilePipeline(...)}, un futur de
 * {@link Pending} à terminer par {@link Pending#finishCompile()} — exactement
 * ce que fait {@code PipelineCache.get} (relevé par javap). {@code isValid()}
 * a disparu au profit de {@code isClosed()}.
 */
public interface CompiledRenderPipeline extends AutoCloseable {

    boolean isClosed();

    @Override
    void close();

    /** {@code CompiledRenderPipeline$Pending} — compilation lancée, pas encore liée. */
    interface Pending {
        CompiledRenderPipeline finishCompile();
    }
}
