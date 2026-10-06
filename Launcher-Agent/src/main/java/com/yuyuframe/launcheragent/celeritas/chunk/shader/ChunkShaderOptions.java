package com.yuyuframe.launcheragent.celeritas.chunk.shader;

import com.yuyuframe.launcheragent.celeritas.gl.shader.ShaderConstants;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.TerrainRenderPass;

import java.util.List;

public record ChunkShaderOptions(List<ChunkShaderComponent.Factory<?>> components, TerrainRenderPass pass) {

    public ShaderConstants constants() {
        ShaderConstants.Builder constants = ShaderConstants.builder();
        for (var component : components) {
            constants.addAll(component.getDefines());
        }

        if (this.pass.supportsFragmentDiscard()) {
            constants.add("USE_FRAGMENT_DISCARD");
        }

        if (this.pass.hasNoLightmap()) {
            constants.add("CELERITAS_NO_LIGHTMAP");
        }

        constants.addAll(pass.extraDefines());

        var vertexType = pass.vertexType();
        var primitiveType = pass.primitiveType();

        vertexType.getDefines().forEach(constants::add);
        constants.addAll(primitiveType.getDefines());

        return constants.build();
    }
}
