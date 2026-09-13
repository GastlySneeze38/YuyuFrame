package com.yuyuframe.launcheragent.apimixin.v1_8_9.clock;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#CLOCK_TOTAL_TICKS} sur 1.8.9 — l'heure dont dérive l'angle du ciel.
 *
 * <h2>Pourquoi pas {@code World.getTimeOfDay()} comme en 1.21.11</h2>
 *
 * En 1.8.9, {@code World.getSkyAngle(float)} ne passe PAS par
 * {@code World.getTimeOfDay()} : elle lit directement
 * {@code LevelProperties.getTimeOfDay()} puis appelle
 * {@code Dimension.getSkyAngle(long, float)} (javap {@code adm.c(F)F}). C'est
 * de cet angle que dérivent soleil, lune, couleurs du ciel et lumière ambiante.
 * Surcharger {@code World.getTimeOfDay()} n'aurait rien changé à l'écran.
 *
 * <h2>Mieux que l'ancien point</h2>
 *
 * L'ancien {@code MixinWorldTime189} remplaçait l'angle entier et appelait
 * lui-même {@code Dimension.getSkyAngle} par réflexion. Ici
 * {@code @ModifyExpressionValue} ne remplace que la valeur de temps lue : la
 * formule vanilla reste celle du jeu, sans réflexion, et le HookPoint garde son
 * contrat (une valeur en ticks, {@code Long}).
 *
 * <h2>Garde « monde client »</h2>
 *
 * {@code getSkyAngle} sert aussi au serveur intégré (lumière du ciel,
 * apparition des monstres). Même garde qu'en 1.21.11 : le point d'accès
 * {@link AccessPoint#LEVEL_IS_CLIENT}. Tant que sa liaison 1.8.9 n'existe pas,
 * la garde répond {@code false} et l'heure vanilla est conservée.
 */
@Mixin(targets = "net.minecraft.world.World")
public abstract class ClockTotalTicksMixin189 {

    @ModifyExpressionValue(method = "getSkyAngle(F)F",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelProperties;getTimeOfDay()J"),
        require = 0)
    private long la$dispatchTimeOfDay(long original) {
        try {
            if (!AccessorRegistry.getBoolean(AccessPoint.LEVEL_IS_CLIENT, this, false)) return original;
            Object replacement = VanillaHookRegistry.dispatchValue(HookPoint.CLOCK_TOTAL_TICKS, this);
            return replacement instanceof Long ? (Long) replacement : original;
        } catch (Throwable t) {
            LauncherLog.err("[ClockTotalTicksMixin189] " + t);
            return original;
        }
    }
}
