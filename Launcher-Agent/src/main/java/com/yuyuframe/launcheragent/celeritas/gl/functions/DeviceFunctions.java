package com.yuyuframe.launcheragent.celeritas.gl.functions;

import com.yuyuframe.launcheragent.celeritas.gl.device.RenderDevice;

public record DeviceFunctions(BufferCopyFunctions bufferCopyFunctions,
                              BufferMapRangeFunctions bufferMapRangeFunctions) {
    public DeviceFunctions(RenderDevice device) {
        this(
                BufferCopyFunctions.pickBest(device),
                BufferMapRangeFunctions.pickBest(device)
        );
    }
}
