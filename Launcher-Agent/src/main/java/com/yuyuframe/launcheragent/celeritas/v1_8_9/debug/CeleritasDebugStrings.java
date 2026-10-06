package com.yuyuframe.launcheragent.celeritas.v1_8_9.debug;

import it.unimi.dsi.fastutil.Pair;
import com.yuyuframe.launcheragent.celeritas.util.MathUtil;
import com.yuyuframe.launcheragent.celeritas.util.NativeBuffer;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.Celeritas;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.CeleritasWorldRenderer;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

public class CeleritasDebugStrings {
    public static List<Pair<String, Integer>> getStringsToRender() {
        List<Pair<String, Integer>> stringsToRender = new ArrayList<>();

        stringsToRender.add(Pair.of(getNativeMemoryString(), -1));
        stringsToRender.add(Pair.of("", -1));

        stringsToRender.add(Pair.of("%s Renderer (%s)".formatted("Celeritas", Celeritas.VERSION), 0xFF55FF55));
        stringsToRender.add(Pair.of("", -1));

        var renderer = CeleritasWorldRenderer.instanceNullable();

        if (renderer != null) {
            renderer.getDebugStrings().forEach(str -> stringsToRender.add(Pair.of(str, -1)));
        }

        return stringsToRender;
    }

    private static String getNativeMemoryString() {
        return "Off-Heap: +" + MathUtil.toMib(getNativeMemoryUsage()) + "MB";
    }

    private static long getNativeMemoryUsage() {
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() + NativeBuffer.getTotalAllocated();
    }
}
