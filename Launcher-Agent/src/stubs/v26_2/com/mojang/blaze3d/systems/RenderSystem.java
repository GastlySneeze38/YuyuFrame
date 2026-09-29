package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.renderer.DynamicUniforms;

/**
 * Stub compile-only — API Blaze3D de la <b>26.2</b>.
 *
 * <h2>Règles pour tous les stubs Blaze3D de cette unité</h2>
 * <ul>
 *   <li>Signatures relevées par javap sur le vrai jar client 26.2 — jamais
 *       devinées, jamais recopiées de {@code src/stubs/v26_1} : la 26.2 a
 *       refondu Blaze3D sous les MÊMES noms de classes (GpuSurface,
 *       RenderPassDescriptor, BindGroupLayout, GpuFormat, PrimitiveTopology ;
 *       TextureFormat, VertexFormat$Mode/$IndexType et les constantes de
 *       VertexFormatElement supprimés).</li>
 *   <li>Le jeu n'est PAS obfusqué en 26.2 : les noms écrits ici sont les
 *       noms réels, aucun remappage au chargement.</li>
 *   <li>Aucune valeur sur les {@code static final} primitifs : javac
 *       INLINERAIT la valeur du stub au lieu de lire celle du jeu.</li>
 * </ul>
 *
 * <p>Seul client de ces stubs : {@code Blaze3DGpu262} et les pipelines de
 * {@code apigraphic/era/blaze3d/v26_2}.
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

    public static DynamicUniforms getDynamicUniforms() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * Tampon d'indices séquentiel partagé du jeu (quads → triangles). 26.2 :
     * prend un {@code PrimitiveTopology} (ex-{@code VertexFormat.Mode}).
     */
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
