package com.yuyuframe.launcheragent.celeritas.gl.tessellation;

import com.yuyuframe.launcheragent.celeritas.gl.device.CommandList;

public interface GlTessellation {
    void delete(CommandList commandList);

    void bind(CommandList commandList);

    void unbind(CommandList commandList);
}
