package com.yuyuframe.launcheragent.apimixin.v1_21_11.clock;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link HookPoint#CLOCK_TOTAL_TICKS} sur 1.21.11 — {@code World.getTimeOfDay()}.
 *
 * <h2>Même HookPoint, point d'accroche différent — et c'est voulu</h2>
 *
 * Le HookPoint est une notion LOGIQUE : « la valeur de temps dont dérive tout
 * le rendu du ciel ». En 26.1.2 elle vient de
 * {@code ClientClockManager.getTotalTicks(Holder)} ; cette version n'a pas ce
 * système d'horloge, et la source commune y est {@code World.getTimeOfDay()}
 * — voir {@code mixin.client.WorldTimeMixin}, conservé, pour le raisonnement
 * complet (désassemblage du vrai jar, et confirmation VISUELLE en jeu sur
 * cette version, pas seulement « mixin appliqué »).
 *
 * <p>{@code WorldTimeModule} s'enregistre sur ce HookPoint et renvoie son
 * heure sans lire {@code ctx} : le même handler sert les deux versions, sans
 * savoir laquelle tourne.
 *
 * <p>Remplace {@code mixin.client.WorldTimeMixin}, qui lisait le module en dur
 * ({@code ModuleRegistry.get("world-time")}) — interdit à {@code apimixin/}.
 *
 * <h2>Garde {@code ClientWorld} obligatoire</h2>
 *
 * {@code World} est partagé client/serveur : sans ce garde, activer le module
 * en solo (serveur intégré, même JVM) fausserait AUSSI le temps réellement
 * simulé côté serveur. Le module est explicitement « client seulement ».
 * Classe résolue une fois puis mise en cache.
 */
@Mixin(targets = "net.minecraft.world.World")
public abstract class ClockTotalTicksMixin1211 {

    private static volatile Class<?> clientWorldClass;
    private static volatile boolean resolutionFailed;

    @Inject(method = "getTimeOfDay()J", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchTimeOfDay(CallbackInfoReturnable<Long> cir) {
        try {
            if (resolutionFailed) return;
            if (clientWorldClass == null) {
                clientWorldClass = McReflect.yarnClass("net/minecraft/client/world/ClientWorld",
                    "net.minecraft.client.multiplayer.ClientLevel");
                if (clientWorldClass == null) {
                    resolutionFailed = true;
                    LauncherLog.err("[ClockTotalTicksMixin1211] ClientWorld introuvable — temps du monde désactivé");
                    return;
                }
            }
            if (!clientWorldClass.isInstance(this)) return;

            Object replacement = VanillaHookRegistry.dispatchValue(HookPoint.CLOCK_TOTAL_TICKS, this);
            if (replacement instanceof Long) {
                cir.setReturnValue((Long) replacement);
            }
        } catch (Throwable t) {
            LauncherLog.err("[ClockTotalTicksMixin1211] " + t);
        }
    }
}
