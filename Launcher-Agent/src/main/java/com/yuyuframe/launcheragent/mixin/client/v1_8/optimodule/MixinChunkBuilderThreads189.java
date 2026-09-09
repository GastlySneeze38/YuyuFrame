package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.optimodule.ChunkBuilderThreadsModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Cible : net.minecraft.client.render.chunk.ChunkBuilder (official bho),
 * constructeur — vérifié par désassemblage bytecode réel : la boucle qui
 * crée les threads workers est {@code for (int i = 0; i < 2; i++) { ... }},
 * la constante 2 codée en dur (AUCUN appel à
 * Runtime.getRuntime().availableProcessors() nulle part dans ce
 * constructeur). Un seul {@code iconst_2} présent dans toute la méthode
 * (l'autre boucle du constructeur utilise {@code iconst_5}, valeur
 * différente) — @ModifyConstant(intValue=2) cible donc sans ambiguïté.
 *
 * Remplace cette constante par la valeur du slider utilisateur (voir
 * ChunkBuilderThreadsModule) — pas de calcul automatique depuis le CPU, le
 * nombre optimal dépend trop de la machine réelle pour choisir à sa place.
 * Défaut du slider = 2 = comportement vanilla inchangé tant que l'utilisateur
 * ne monte pas la valeur lui-même.
 */
@Mixin(targets = "net.minecraft.client.render.chunk.ChunkBuilder")
public abstract class MixinChunkBuilderThreads189 {

    @ModifyConstant(method = "<init>()V", constant = @Constant(intValue = 2))
    private int la$threadCount(int original) {
        try {
            LauncherModule module = ModuleRegistry.get("chunk-builder-threads");
            if (module == null || !module.isEnabled() || !(module instanceof ChunkBuilderThreadsModule)) return original;
            int threads = Math.round(((ChunkBuilderThreadsModule) module).threadCount);
            return Math.max(1, threads);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinChunkBuilderThreads189: " + t);
            return original;
        }
    }
}
