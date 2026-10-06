package com.yuyuframe.launcheragent.celeritas.gl.device;

import com.yuyuframe.launcheragent.celeritas.gl.functions.DeviceFunctions;

public interface RenderDevice {
    RenderDevice INSTANCE = new GLRenderDevice();

    CommandList createCommandList();

    static void enterManagedCode() {
        RenderDevice.INSTANCE.makeActive();
    }

    static void exitManagedCode() {
        RenderDevice.INSTANCE.makeInactive();
    }

    void makeActive();
    void makeInactive();

    DeviceFunctions getDeviceFunctions();
}
