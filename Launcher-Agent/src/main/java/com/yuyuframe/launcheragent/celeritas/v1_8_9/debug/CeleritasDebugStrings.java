package com.yuyuframe.launcheragent.celeritas.v1_8_9.debug;

import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.celeritas.util.MathUtil;
import com.yuyuframe.launcheragent.celeritas.util.NativeBuffer;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.CeleritasWorldRenderer;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Lignes ajoutées à droite de l'écran F3 quand notre renderer dessine.
 *
 * <p>Repris de l'adaptateur {@code ornithe} de Celeritas, en simples chaînes
 * (l'amont rendait des paires texte/couleur, par une classe de fastutil 8
 * absente de la version que nous livrons).
 */
public class CeleritasDebugStrings {

    /** Préfixe de couleur verte du jeu (§a) — évite de nommer Formatting ici. */
    private static final String GREEN = "§a";

    public static List<String> getStringsToRender() {
        List<String> lines = new ArrayList<>();

        if (!RendererSwitch.active()) {
            return lines;
        }

        lines.add("");
        lines.add(getNativeMemoryString());
        lines.add(GREEN + "YuyuFrame Renderer (Celeritas)");

        if (CeleritasWorldRenderer.INSTANCE.isAttached()) {
            lines.addAll(CeleritasWorldRenderer.INSTANCE.getDebugStrings());
        }

        return lines;
    }

    private static String getNativeMemoryString() {
        return "Off-Heap: +" + MathUtil.toMib(getNativeMemoryUsage()) + "MB";
    }

    private static long getNativeMemoryUsage() {
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() + NativeBuffer.getTotalAllocated();
    }
}
