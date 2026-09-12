package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.DynamicUniforms;

/**
 * Stub compile-only — API Blaze3D de la <b>26.1.2</b>.
 *
 * <h2>Règles pour tous les stubs Blaze3D de cette unité</h2>
 * <ul>
 *   <li>Signatures relevées sur le vrai jar client 26.1.2 — jamais devinées,
 *       jamais recopiées depuis {@code src/stubs_1_21_11} : les deux API
 *       portent les MÊMES noms de classes mais diffèrent réellement. Deux
 *       exemples vécus : {@code GpuDevice}/{@code CommandEncoder}/{@code
 *       RenderPass} sont des INTERFACES en 1.21.11 et des CLASSES ici (un
 *       stub du mauvais genre lève {@code IncompatibleClassChangeError} au
 *       premier appel) ; la méthode d'écriture des transformations dynamiques
 *       s'appelle {@code writeTransform} ici et {@code write} là-bas.</li>
 *   <li>Le jeu n'est PAS obfusqué en 26.1.2 : les noms écrits ici sont les
 *       noms réels, aucun remappage au chargement (contrairement à l'unité
 *       1.21.11 et à son {@code YarnNamedRemapper}).</li>
 *   <li>Aucune valeur sur les {@code static final} primitifs : javac
 *       INLINERAIT la valeur du stub au lieu de lire celle du jeu. Elles sont
 *       assignées dans un bloc {@code static} — un final « blanc » n'est pas
 *       une constante de compilation.</li>
 * </ul>
 *
 * <p>Seul client de ces stubs : {@code Blaze3DGpu261}.
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
     * {@code getSequentialBuffer(QUADS)} rend le tampon d'indices partagé du
     * jeu — celui que le champ PRIVÉ {@code sharedSequentialQuad} contient.
     *
     * <p>C'est la raison d'être de cette méthode ici : l'adaptateur réflexif
     * lisait le champ privé par {@code setAccessible}, ce qu'un appel typé ne
     * peut pas faire. Cet accesseur public existe (vérifié sur le jar) et rend
     * exactement le même objet.
     */
    public static AutoStorageIndexBuffer getSequentialBuffer(VertexFormat.Mode mode) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code RenderSystem$AutoStorageIndexBuffer} — opaque, seulement relayée. */
    public static final class AutoStorageIndexBuffer {

        private AutoStorageIndexBuffer() {
        }

        public GpuBuffer getBuffer(int indexCount) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public VertexFormat.IndexType type() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
