package com.yuyuframe.launcheragent.apimixin.v26_3.render;

import com.mojang.blaze3d.pipeline.PipelineCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Caches de pipelines compilés du jeu — propre à la 26.3.
 *
 * <p>En 26.3, {@code RenderPass.setPipeline} prend un pipeline COMPILÉ, que le
 * {@code GuiRenderer} vanilla obtient par {@code RenderSystem.getCompiledPipeline}
 * : le cache courant ({@code currentPipelineCache}, posé par
 * {@code ShaderManager.apply} à chaque rechargement des ressources), sinon le
 * cache de secours ({@code fallbackPipelineCache}, posé par
 * {@code GameRenderer.preloadUiShader}). Tous deux compilent avec la source de
 * shaders du jeu, qui ne connaît pas les nôtres : {@code Blaze3DGpu263} y
 * inscrit donc lui-même nos pipelines compilés. Aucun getter public — champs
 * privés, relevés par javap.
 *
 * <p>{@code static} REQUIS (les champs le sont) ; les corps ne sont jamais exécutés.
 */
@Mixin(targets = "com.mojang.blaze3d.systems.RenderSystem")
public interface RenderSystemAccessor263 {

    @Accessor("currentPipelineCache")
    static PipelineCache la$currentPipelineCache() { throw new AssertionError("RenderSystemAccessor263 non tissé"); }

    @Accessor("fallbackPipelineCache")
    static PipelineCache la$fallbackPipelineCache() { throw new AssertionError("RenderSystemAccessor263 non tissé"); }
}
