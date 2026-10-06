package com.yuyuframe.launcheragent.celeritas.v1_8_9.extensions;

import com.yuyuframe.launcheragent.celeritas.chunk.sprite.SpriteTransparencyLevel;

public interface SpriteExtension {
    void celeritas$markActive();
    boolean celeritas$shouldUpdate();
    SpriteTransparencyLevel celeritas$getTransparencyLevel();
}
