package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.visual.WorldTimeModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BUG TROUVÉ ET CORRIGÉ ("Temps du monde", 1.21.4, "cause encore inconnue"
 * dans module-bracket-audit.md) : {@link WorldTimeMixin1214} (cible {@code
 * World.getTimeOfDay()J}) était CONFIRMÉ tissé avec succès (log :
 * `Mixin initialisé avec succès`) et son diag confirmait le garde-fou
 * (module activé, instance bien un {@code ClientWorld}) — pourtant AUCUN
 * effet visuel. Désassemblage complet (javap sur le vrai jar client
 * `%APPDATA%\YuyuFrame\.minecraft\versions\1.21.4\1.21.4.jar`) a révélé la
 * VRAIE chaîne d'appels utilisée par le rendu du ciel/lune sur ce bracket :
 *
 * <pre>
 * LunarWorldView.getSkyAngle(F)F      (interface dgq, default method)
 *   → this.getLunarTime()J             (dgk.al(), default method)
 *     → this.getLevelProperties()      (gga.D_(), retourne gga$a)
 *       → WorldProperties.getTimeOfDay()J  (gga$a.d() — override CONCRET)
 *         → return this.timeOfDay;     (simple lecture de champ synchronisé
 *                                        par paquet réseau, PAS un appel à
 *                                        World.getTimeOfDay())
 * </pre>
 *
 * {@code World.getTimeOfDay()} (ce que {@link WorldTimeMixin1214} surcharge)
 * n'est DONC JAMAIS appelée par cette chaîne — une méthode complètement
 * différente, sur une classe complètement différente ({@code
 * ClientWorld$Properties}, obf {@code gga$a}, {@code net/minecraft/client/world/ClientWorld$Properties}),
 * fournit la valeur réellement utilisée pour le ciel. {@code LunarWorldView}/
 * {@code getLunarTime()} N'EXISTENT PAS dans les mappings 1.21.11 (grep vide
 * sur `mappings/mappings.tiny`) — cette indirection est propre aux brackets
 * pré-1.21.11 (probablement aussi 1.16.5/1.20.4, jamais vérifié individuellement),
 * ce qui explique pourquoi la même technique (surcharger juste
 * {@code World.getTimeOfDay()}) suffit sur 1.21.11/26.1.2 mais pas ici.
 *
 * "getTimeOfDay" n'a PAS d'entrée Yarn propre sur {@code ClientWorld$Properties}
 * lui-même (seul "setTimeOfDay" y est listé — l'override du getter n'a pas de
 * nom Yarn dédié) — {@code fallbackNamedOwner} pointe donc vers
 * {@code WorldProperties} (déclarant réel du nom Yarn "getTimeOfDay",
 * method_217), voir l'entrée REFMAP_ENTRIES correspondante dans
 * LauncherMixinService.
 *
 * {@link WorldTimeMixin1214} (World) est conservé tel quel — inoffensif, et
 * potentiellement lu ailleurs (F3, commandes) — ce Mixin-ci est celui qui
 * corrige RÉELLEMENT l'effet visuel.
 */
@Mixin(targets = "net.minecraft.client.world.ClientWorld$Properties")
public abstract class WorldTimePropertiesMixin1214 {

    @Inject(method = "getTimeOfDay()J", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$fakeDayTimeProperties(CallbackInfoReturnable<Long> cir) {
        try {
            LauncherModule moduleBase = ModuleRegistry.get("world-time");
            if (!(moduleBase instanceof WorldTimeModule) || !moduleBase.isEnabled()) return;
            WorldTimeModule module = (WorldTimeModule) moduleBase;
            cir.setReturnValue((long) module.time);
        } catch (Throwable t) {
            LauncherLog.err("[WorldTimePropertiesMixin1214] la$fakeDayTimeProperties: " + t);
        }
    }
}
