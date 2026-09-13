package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.DynamicUniforms;
import net.minecraft.client.gl.SamplerCache;

/**
 * Stub compile-only — API Blaze3D de la <b>1.21.11</b>, unité de compilation
 * SÉPARÉE de {@code src/stubs/v26_1}. Voir {@code build.bat}, passe
 * « Stubs 1.21.11 ».
 *
 * <h2>Règles de cette unité (valables pour tous ses fichiers)</h2>
 * <ul>
 *   <li>Signatures recopiées de {@code javap -public} sur le vrai jar client
 *       1.21.11 — jamais devinées, jamais reprises de la 26.1.2 (les deux API
 *       diffèrent : {@code ColorTargetState}/{@code DepthStencilState}
 *       n'existent pas ici, par exemple).</li>
 *   <li>Seulement les classes {@code com.mojang.blaze3d.*} de PREMIER NIVEAU,
 *       et leurs classes imbriquées non obfusquées ({@code RenderPipeline$Builder}).
 *       Ces noms sont identiques quel que soit le loader.</li>
 *   <li>Les types OBFUSQUÉS ({@code amo} = Identifier, {@code fyz} =
 *       UniformType, {@code fzf} = GpuSampler, {@code VertexFormat$a}/{@code $b}…)
 *       changent de nom selon le loader (intermédiaire sous Fabric, officiel en
 *       vanilla) : ils sont stubés sous leur NOM YARN
 *       ({@code net/minecraft/client/gl/GpuSampler}, {@code VertexFormat$DrawMode}…),
 *       et le code qui s'en sert est traduit AU CHARGEMENT par
 *       {@code YarnNamedRemapper} vers le nom du loader actif. Conséquence : ce
 *       code doit vivre dans un paquet que ce remappeur traduit
 *       ({@code apigraphic.era.blaze3d.v1_21_11}), sinon il chercherait des
 *       classes Yarn qui n'existent pas en jeu. (Premier jet de l'étape 1 :
 *       « aucun type obfusqué » — levé à l'étape 2 avec ce remappeur.)</li>
 *   <li>Aucune valeur de constante sur les {@code static final} primitifs :
 *       javac INLINERAIT la valeur du stub dans notre code au lieu de lire
 *       celle du jeu. Ils sont assignés dans un bloc {@code static} (champ
 *       final « blanc » = pas une constante de compilation).</li>
 * </ul>
 */
public class RenderSystem {

    public static GpuDevice getDevice() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static GpuBufferSlice getProjectionMatrixBuffer() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static void bindDefaultUniforms(RenderPass pass) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static SamplerCache getSamplerCache() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static DynamicUniforms getDynamicUniforms() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code getSequentialBuffer(QUADS)} = le tampon d'indices partagé {@code sharedSequentialQuad}. */
    public static ShapeIndexBuffer getSequentialBuffer(VertexFormat.DrawMode mode) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code RenderSystem$ShapeIndexBuffer}, nom Yarn ({@code RenderSystem$a} officiel). */
    public static final class ShapeIndexBuffer {

        private ShapeIndexBuffer() {
        }

        public GpuBuffer getIndexBuffer(int indexCount) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public VertexFormat.IndexType getIndexType() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
