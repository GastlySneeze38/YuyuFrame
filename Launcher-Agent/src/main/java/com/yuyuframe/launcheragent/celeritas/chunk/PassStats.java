package com.yuyuframe.launcheragent.celeritas.chunk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * YuyuFrame : ce que coûte le dessin de chaque passe de terrain (opaque,
 * découpée, translucide), pour la ligne de métriques du renderer. Notre code,
 * pas celui de Celeritas.
 *
 * <p>Le temps est celui passé côté processeur dans le dessin de la passe :
 * préparation des lots et appels au pilote. Un « lot » regroupe les sections
 * d'une région ; il est gardé d'une image à l'autre et refait quand ce qui est
 * visible dans la région change.
 *
 * <p>Fil du jeu seulement.
 */
public final class PassStats {

    private static final class Pass {
        long nanos;
        long regions;
        long rebuiltBatches;
        long draws;
    }

    private static final Map<String, Pass> PASSES = new LinkedHashMap<>();

    private PassStats() {
    }

    /**
     * @param regions        régions dessinées
     * @param rebuiltBatches lots refaits pendant ce dessin (les autres étaient en cache)
     */
    public static void record(String pass, long nanos, int regions, int rebuiltBatches) {
        Pass stats = PASSES.computeIfAbsent(pass, name -> new Pass());
        stats.nanos += nanos;
        stats.regions += regions;
        stats.rebuiltBatches += rebuiltBatches;
        stats.draws++;
    }

    /** Les moyennes par dessin depuis l'appel précédent, ou {@code null} s'il n'y a rien eu. */
    public static String drain() {
        if (PASSES.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder("Dessin par passe:");
        for (Map.Entry<String, Pass> entry : PASSES.entrySet()) {
            Pass stats = entry.getValue();
            if (stats.draws == 0) {
                continue;
            }
            out.append(String.format(Locale.ROOT, " %s %.0f µs (%.1f régions, %.2f lots refaits) |",
                entry.getKey(), stats.nanos / 1000.0 / stats.draws, (double) stats.regions / stats.draws,
                (double) stats.rebuiltBatches / stats.draws));
        }
        PASSES.clear();
        return out.toString();
    }
}
