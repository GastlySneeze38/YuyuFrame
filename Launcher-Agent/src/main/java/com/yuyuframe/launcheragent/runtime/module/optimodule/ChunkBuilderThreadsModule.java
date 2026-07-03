package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

/**
 * Le vanilla 1.8.9 crée son pool de threads de construction de meshes de
 * chunks ({@code ChunkBuilder}) avec un nombre de workers CODÉ EN DUR À 2 —
 * vérifié par désassemblage bytecode réel (boucle {@code for(i=0;i<2;i++)}
 * dans le constructeur, AUCUN appel à {@code Runtime.availableProcessors()}).
 * Sur un CPU moderne multi-cœurs, c'est une sous-utilisation massive : les
 * chunks à reconstruire (cassage/pose de bloc, déplacement rapide) mettent
 * plus longtemps à réapparaître, et le rendu peut stuttering en attendant.
 *
 * Nombre de threads réglable manuellement (voir MixinChunkBuilderThreads189,
 * @ModifyConstant sur la constante 2 du constructeur) — valeur PROPOSÉE par
 * défaut = {@code cores - 2} (garde 2 threads libres pour le rendu/OS), pas
 * un maximum agressif : reste réglable librement par l'utilisateur ensuite
 * (voir le slider), qui connaît sa machine mieux que ce calcul générique.
 */
public final class ChunkBuilderThreadsModule extends LauncherModule {

    @ConfigSlider(name = "Nombre de threads", category = "Réglages", min = 2f, max = 16f, step = 1f)
    public float threadCount = suggestedDefault();

    public ChunkBuilderThreadsModule() {
        super("chunk-builder-threads", "Threads de construction de chunks", "Nombre de threads pour reconstruire les chunks modifiés (vanilla est figé à 2, peu importe le CPU)", true);
    }

    private static float suggestedDefault() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, cores - 2);
    }
}
