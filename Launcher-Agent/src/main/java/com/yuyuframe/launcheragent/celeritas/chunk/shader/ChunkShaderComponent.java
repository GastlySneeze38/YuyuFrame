package com.yuyuframe.launcheragent.celeritas.chunk.shader;

import com.yuyuframe.launcheragent.celeritas.gl.shader.ShaderBindingContext;

import java.util.Collection;
import java.util.List;

public interface ChunkShaderComponent {
    void setup();

    interface Factory<T extends ChunkShaderComponent> {
        T create(ShaderBindingContext context, ChunkShaderEnvironment environment);

        default Collection<String> getDefines() {
            return List.of();
        }
    }
}
