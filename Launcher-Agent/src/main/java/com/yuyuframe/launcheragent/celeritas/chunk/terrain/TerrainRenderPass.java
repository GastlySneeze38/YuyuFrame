package com.yuyuframe.launcheragent.celeritas.chunk.terrain;

import com.yuyuframe.launcheragent.celeritas.gl.attribute.GlVertexFormat;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.sorting.ChunkPrimitiveType;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.Material;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkVertexType;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A terrain render pass corresponds to a draw call to render some subset of terrain geometry. Passes are generally
 * used for fixed configuration that will not change from quad to quad and allow for optimizations to be made
 * within the terrain shader code at compile time (e.g. omitting the fragment discard conditional entirely on the solid pass).
 * <p></p>
 * Geometry that shares the same terrain render pass may still be able to specify some more dynamic properties. See {@link Material}
 * for more information.
 */
public class TerrainRenderPass {
    /**
     * The friendly name of this render pass. Not part of {@link #equals}.
     */
    private final String name;

    /**
     * A callback used to set up/clear GPU pipeline state.
     */
    private final PipelineState pipelineState;

    /**
     * Whether sections on this render pass should be rendered farthest-to-nearest, rather than nearest-to-farthest.
     */
    private final boolean useReverseOrder;
    /**
     * Whether fragment alpha testing should be enabled for this render pass.
     */
    private final boolean fragmentDiscard;
    /**
     * Whether this render pass wants to opt in to translucency sorting if enabled.
     */
    private final boolean useTranslucencySorting;
    /**
     * Whether this render pass has no lightmap texture.
     */
    private final boolean hasNoLightmap;

    private final @NotNull ChunkPrimitiveType primitiveType;
    private final @NotNull ChunkVertexType vertexType;

    public record TessellationKey(GlVertexFormat vertexFormat, ChunkPrimitiveType primitiveType, boolean sorted) {}

    /** Derived from the vertex type and primitive type. Not part of {@link #equals}. */
    private final TessellationKey tessellationKey;

    private final Map<String, String> extraDefines;

    public TerrainRenderPass(String name,
                             PipelineState pipelineState,
                             boolean useReverseOrder,
                             boolean fragmentDiscard,
                             boolean useTranslucencySorting,
                             boolean hasNoLightmap,
                             @NotNull ChunkVertexType vertexType,
                             @NotNull ChunkPrimitiveType primitiveType,
                             Map<String, String> extraDefines) {
        if(name == null || name.length() == 0) {
            throw new IllegalArgumentException("Name not specified for terrain pass");
        }
        Objects.requireNonNull(vertexType);
        Objects.requireNonNull(primitiveType);

        this.name = name;
        this.pipelineState = pipelineState;
        this.useReverseOrder = useReverseOrder;
        this.fragmentDiscard = fragmentDiscard;
        this.useTranslucencySorting = useTranslucencySorting;
        this.hasNoLightmap = hasNoLightmap;
        this.primitiveType = primitiveType;
        this.vertexType = vertexType;
        this.tessellationKey = new TessellationKey(vertexType.getVertexFormat(), primitiveType, useTranslucencySorting);
        this.extraDefines = Map.copyOf(extraDefines);
    }

    public String name() {
        return this.name;
    }

    public TessellationKey tessellationKey() {
        return this.tessellationKey;
    }

    public static TerrainRenderPassBuilder builder() {
        return new TerrainRenderPassBuilder();
    }

    /** Step-by-step construction of a {@link TerrainRenderPass}; written out by hand in place of Lombok's {@code @Builder}. */
    public static class TerrainRenderPassBuilder {
        private String name;
        private PipelineState pipelineState;
        private boolean useReverseOrder;
        private boolean fragmentDiscard;
        private boolean useTranslucencySorting;
        private boolean hasNoLightmap;
        private ChunkVertexType vertexType;
        private ChunkPrimitiveType primitiveType;
        private final Map<String, String> extraDefines = new LinkedHashMap<>();

        TerrainRenderPassBuilder() {
        }

        public TerrainRenderPassBuilder name(String name) {
            this.name = name;
            return this;
        }

        public TerrainRenderPassBuilder pipelineState(PipelineState pipelineState) {
            this.pipelineState = pipelineState;
            return this;
        }

        public TerrainRenderPassBuilder useReverseOrder(boolean useReverseOrder) {
            this.useReverseOrder = useReverseOrder;
            return this;
        }

        public TerrainRenderPassBuilder fragmentDiscard(boolean fragmentDiscard) {
            this.fragmentDiscard = fragmentDiscard;
            return this;
        }

        public TerrainRenderPassBuilder useTranslucencySorting(boolean useTranslucencySorting) {
            this.useTranslucencySorting = useTranslucencySorting;
            return this;
        }

        public TerrainRenderPassBuilder hasNoLightmap(boolean hasNoLightmap) {
            this.hasNoLightmap = hasNoLightmap;
            return this;
        }

        public TerrainRenderPassBuilder vertexType(@NotNull ChunkVertexType vertexType) {
            this.vertexType = vertexType;
            return this;
        }

        public TerrainRenderPassBuilder primitiveType(@NotNull ChunkPrimitiveType primitiveType) {
            this.primitiveType = primitiveType;
            return this;
        }

        public TerrainRenderPassBuilder extraDefine(String key, String value) {
            this.extraDefines.put(key, value);
            return this;
        }

        public TerrainRenderPassBuilder extraDefines(Map<? extends String, ? extends String> extraDefines) {
            this.extraDefines.putAll(extraDefines);
            return this;
        }

        public TerrainRenderPassBuilder clearExtraDefines() {
            this.extraDefines.clear();
            return this;
        }

        public TerrainRenderPass build() {
            return new TerrainRenderPass(this.name, this.pipelineState, this.useReverseOrder, this.fragmentDiscard,
                    this.useTranslucencySorting, this.hasNoLightmap, this.vertexType, this.primitiveType, this.extraDefines);
        }
    }

    // equals/hashCode cover every field except name and tessellationKey, as the Lombok annotations did.
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof TerrainRenderPass other) || !other.canEqual(this)) {
            return false;
        }
        return this.useReverseOrder == other.useReverseOrder
                && this.fragmentDiscard == other.fragmentDiscard
                && this.useTranslucencySorting == other.useTranslucencySorting
                && this.hasNoLightmap == other.hasNoLightmap
                && Objects.equals(this.pipelineState, other.pipelineState)
                && Objects.equals(this.primitiveType, other.primitiveType)
                && Objects.equals(this.vertexType, other.vertexType)
                && Objects.equals(this.extraDefines, other.extraDefines);
    }

    protected boolean canEqual(Object other) {
        return other instanceof TerrainRenderPass;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.pipelineState, this.useReverseOrder, this.fragmentDiscard, this.useTranslucencySorting,
                this.hasNoLightmap, this.primitiveType, this.vertexType, this.extraDefines);
    }

    public boolean isReverseOrder() {
        return this.useReverseOrder;
    }

    public boolean isSorted() {
        return this.useTranslucencySorting;
    }

    public boolean hasNoLightmap() {
        return this.hasNoLightmap;
    }

    public void startDrawing() {
        this.pipelineState.setup();
    }

    public void endDrawing() {
        this.pipelineState.clear();
    }

    public boolean supportsFragmentDiscard() {
        return this.fragmentDiscard;
    }

    public ChunkPrimitiveType primitiveType() {
        return this.primitiveType;
    }

    public ChunkVertexType vertexType() {
        return this.vertexType;
    }

    public Map<String, String> extraDefines() {
        return this.extraDefines;
    }

    @Override
    public String toString() {
        return "TerrainRenderPass[name=" + this.name + "]";
    }

    public interface PipelineState {
        PipelineState DEFAULT = new PipelineState() {
            @Override
            public void setup() {

            }

            @Override
            public void clear() {

            }
        };

        void setup();
        void clear();
    }
}
