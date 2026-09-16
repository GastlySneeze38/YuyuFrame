package com.yuyuframe.launcheragent.apimixin.v1_21_11.clock;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
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
 * — voir {@code mixin.client.WorldTimeMixin} (supprimé le 2026-09-16,
 * historique dans git) pour le raisonnement
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
 * <h2>Garde « monde client » obligatoire</h2>
 *
 * {@code World} est partagé client/serveur : sans ce garde, activer le module
 * en solo (serveur intégré, même JVM) fausserait AUSSI le temps réellement
 * simulé côté serveur. Le module est explicitement « client seulement ».
 *
 * <p>Le garde passait par {@code McReflect.yarnClass("ClientWorld")} puis
 * {@code isInstance}. Il passe désormais par le point d'accès
 * {@link AccessPoint#LEVEL_IS_CLIENT} (2026-09-13) : le corps d'un Mixin n'est
 * pas traduit au chargement et ne peut donc pas nommer {@code ClientWorld}
 * lui-même ; la liaison typée, elle, l'est.
 */
@Mixin(targets = "net.minecraft.world.World")
public abstract class ClockTotalTicksMixin1211 {

    @Inject(method = "getTimeOfDay()J", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchTimeOfDay(CallbackInfoReturnable<Long> cir) {
        try {
            // Faux aussi si la liaison manque — déjà journalisé une fois par
            // AccessorRegistry : dans le doute, on ne touche pas au temps simulé.
            if (!AccessorRegistry.getBoolean(AccessPoint.LEVEL_IS_CLIENT, this, false)) return;

            Object replacement = VanillaHookRegistry.dispatchValue(HookPoint.CLOCK_TOTAL_TICKS, this);
            if (replacement instanceof Long) {
                cir.setReturnValue((Long) replacement);
            }
        } catch (Throwable t) {
            LauncherLog.err("[ClockTotalTicksMixin1211] " + t);
        }
    }
}
