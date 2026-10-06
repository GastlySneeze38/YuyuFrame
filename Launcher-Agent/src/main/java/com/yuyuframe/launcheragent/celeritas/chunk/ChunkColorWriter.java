package com.yuyuframe.launcheragent.celeritas.chunk;

import com.yuyuframe.launcheragent.celeritas.util.color.ColorABGR;
import com.yuyuframe.launcheragent.celeritas.util.color.ColorMixer;

public enum ChunkColorWriter {
    SEPARATE_AO {
        @Override
        public int writeColor(int colorWithAlpha, float aoValue) {
            return ColorABGR.withAlpha(colorWithAlpha, aoValue);
        }
    },
    EMBEDDIUM {
        @Override
        public int writeColor(int colorWithAlpha, float aoValue) {
            return ColorMixer.mulSingleWithoutAlpha(colorWithAlpha, (int)(aoValue * 255));
        }
    };

    public abstract int writeColor(int colorWithAlpha, float aoValue);
}
