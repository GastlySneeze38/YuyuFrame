package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

/**
 * Stub compile-only — API Blaze3D de la <b>1.21.11</b>, unité de compilation
 * SÉPARÉE de {@code src/stubs} (26.1.2). Voir {@code build.bat}, passe
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
 *   <li>Aucun membre dont la signature contient un type obfusqué
 *       ({@code amo} = Identifier, {@code fyz} = UniformType, {@code fzf} =
 *       GpuSampler, {@code VertexFormat$a}/{@code $b}…) : leur nom change selon
 *       le loader (intermédiaire sous Fabric, officiel en vanilla), aucun stub
 *       ne peut les nommer. Ceux-là passent par des invokers Mixin
 *       ({@code apimixin/v1_21_11}), traduits par le refmap.</li>
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
}
