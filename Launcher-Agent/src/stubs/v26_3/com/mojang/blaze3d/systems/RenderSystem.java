package com.mojang.blaze3d.systems;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.DynamicGpuData;

/**
 * Stub compile-only — API de rendu de la <b>26.3</b>.
 *
 * <h2>Règles pour tous les stubs Blaze3D/renderpearl de cette unité</h2>
 * <ul>
 *   <li>Signatures relevées par javap sur le vrai jar client 26.3 — jamais
 *       devinées. La 26.3 a renommé Blaze3D en {@code com.mojang.renderpearl}
 *       ({@code api.device}, {@code api.commands}, {@code api.pipeline},
 *       {@code api.textures}, {@code api.buffers}, {@code api.vertex}) ; seules
 *       quelques classes restent dans {@code com.mojang.blaze3d}
 *       (RenderSystem, RenderTarget, Window, NativeImage, InputConstants…).
 *       {@code GpuDevice}, {@code CommandEncoder}, {@code RenderPass} et
 *       {@code GpuSurface} y sont des INTERFACES.</li>
 *   <li>Le jeu n'est PAS obfusqué : les noms écrits ici sont les noms réels.</li>
 *   <li>Aucune valeur sur les {@code static final} primitifs : javac
 *       INLINERAIT la valeur du stub au lieu de lire celle du jeu.</li>
 * </ul>
 *
 * <p>Seul client de ces stubs : {@code Blaze3DGpu263} et les pipelines de
 * {@code apigraphic/era/blaze3d/v26_3}.
 */
public class RenderSystem {

    public static GpuDevice getDevice() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static SamplerCache getSamplerCache() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static void bindDefaultUniforms(RenderPass pass) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** 26.3 : rend un {@link DynamicGpuData} (ex-{@code DynamicUniforms}), même nom de méthode. */
    public static DynamicGpuData getDynamicUniforms() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * 26.3 : pipeline compilé du cache COURANT du jeu (compilé à la demande
     * avec la source de shaders vanilla) — pour les pipelines vanilla
     * seulement ; les nôtres ont leur propre source GLSL.
     */
    public static CompiledRenderPipeline getCompiledPipeline(RenderPipeline pipeline) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Tampon d'indices séquentiel partagé du jeu (quads → triangles). */
    public static AutoStorageIndexBuffer getSequentialBuffer(PrimitiveTopology topology) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code RenderSystem$AutoStorageIndexBuffer} — opaque, seulement relayée. */
    public static final class AutoStorageIndexBuffer {

        private AutoStorageIndexBuffer() {
        }

        public GpuBuffer getBuffer(int indexCount) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public IndexType type() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
