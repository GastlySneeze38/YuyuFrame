package com.yuyuframe.launcheragent.celeritas.gl.buffer;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL30;
import com.yuyuframe.launcheragent.celeritas.gl.binding.GL44;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;

import com.yuyuframe.launcheragent.celeritas.gl.util.EnumBit;

public enum GlBufferStorageFlags implements EnumBit {
    PERSISTENT(GL44.GL_MAP_PERSISTENT_BIT),
    MAP_READ(GL30.GL_MAP_READ_BIT),
    MAP_WRITE(GL30.GL_MAP_WRITE_BIT),
    CLIENT_STORAGE(GL44.GL_CLIENT_STORAGE_BIT),
    COHERENT(GL44.GL_MAP_COHERENT_BIT);

    private final int bits;

    GlBufferStorageFlags(int bits) {
        this.bits = bits;
    }

    @Override
    public int getBits() {
        return this.bits;
    }
}
