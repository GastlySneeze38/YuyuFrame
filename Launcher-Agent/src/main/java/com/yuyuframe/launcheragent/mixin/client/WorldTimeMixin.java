package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.WorldTimeModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Port de {@code MixinWorldTime189} (1.8.9) pour le bracket 1.21.11 — voir
 * aussi {@code ClientClockManagerWorldTimeMixin261} (26.1.2, architecture
 * "ClockManager"/Timeline totalement différente, absente ici).
 *
 * SCOPE : audit demandé par l'utilisateur ("fait en sorte que le temps du
 * monde fonctionne sur toutes les versions") — jusqu'ici {@code
 * WorldTimeModule} n'avait de logique QUE pour 1.8.9 et 26.1.2, carte
 * cliquable sans effet sur 1.16.5/1.20.4/1.21.4/1.21.11 (voir audit de
 * session). Complété ici pour 1.21.11 — voir {@code WorldTimeMixin116}/
 * {@code WorldTimeMixin1204}/{@code WorldTimeMixin1214} (même technique,
 * un fichier par bracket) pour 1.16.5/1.20.4/1.21.4.
 *
 * Sur ce bracket (déjà passé sur la nouvelle architecture SkyRenderer, voir
 * historique de session pour 26.1.2 — MAIS SANS le système Timeline/
 * EnvironmentAttribute/ClockManager, absent ici), TOUT le rendu du ciel/
 * soleil/lune/couleur découle in fine d'{@code EnvironmentAttributeProbe}
 * interrogé via un {@code LongSupplier} — confirmé par désassemblage
 * bytecode du vrai jar 1.21.11 (parser maison, class-file version non lisible
 * par javap) que {@code Timeline.createTrackSampler} reçoit ce supplier de
 * l'extérieur ; plutôt que de retracer CE fil (fragile, dépend d'un lambda
 * non nommé), on remonte à la source COMMUNE dont tout dérive forcément :
 * {@code World.getTimeOfDay()J} (nom YARN — réel Mojang {@code getDayTime},
 * vérifié via les mappings officiels Mojang téléchargés pour ce build exact)
 * — la méthode FONDAMENTALE qui stocke/renvoie le compteur de jour brut,
 * lue tôt ou tard par n'importe quel mécanisme de rendu du temps, ancien ou
 * nouveau. Renvoyer NOTRE heure ICI fait cascader le changement partout,
 * sans avoir à comprendre le détail du nouveau système d'attributs.
 *
 * Garde {@code ClientWorld} (Yarn) OBLIGATOIRE : {@code World}/{@code
 * getTimeOfDay} sont déclarés sur la classe PARTAGÉE client/serveur (à la
 * différence de {@code ClientClockManager}, client-only par construction
 * sur 26.1.2) — sans ce garde, activer ce module en solo (serveur intégré,
 * MÊME JVM) fausserait AUSSI le temps réellement simulé côté serveur (spawn
 * de mobs, etc.), exactement ce que l'utilisateur a explicitement exclu
 * ("pas server side, que client side").
 */
@Mixin(targets = "net.minecraft.world.World")
public abstract class WorldTimeMixin {

    @Inject(method = "getTimeOfDay()J", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$fakeDayTime(CallbackInfoReturnable<Long> cir) {
        try {
            LauncherModule moduleBase = ModuleRegistry.get("world-time");
            if (!(moduleBase instanceof WorldTimeModule) || !moduleBase.isEnabled()) return;
            WorldTimeModule module = (WorldTimeModule) moduleBase;

            Class<?> clientWorldClass = McReflect.yarnClass("net/minecraft/client/world/ClientWorld", "net.minecraft.client.multiplayer.ClientLevel");
            if (clientWorldClass == null || !clientWorldClass.isInstance(this)) return;

            cir.setReturnValue((long) module.time);
        } catch (Throwable t) {
            LauncherLog.err("[WorldTimeMixin] la$fakeDayTime: " + t);
        }
    }
}
