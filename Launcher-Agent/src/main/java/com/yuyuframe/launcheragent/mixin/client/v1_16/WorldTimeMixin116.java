package com.yuyuframe.launcheragent.mixin.client.v1_16;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.visual.WorldTimeModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Port de {@code MixinWorldTime189}/{@code WorldTimeMixin} (1.21.11) pour
 * 1.16.5 — voir leur javadoc pour l'architecture complète et le garde
 * {@code ClientWorld} obligatoire (classe {@code World} partagée client/
 * serveur, sans quoi le solo fausserait le vrai temps serveur).
 *
 * {@code getTimeOfDay()J} (nom YARN, method_8532 — réel Mojang {@code
 * getDayTime}, vérifié via les mappings officiels Mojang de CETTE version
 * exacte) : même méthode fondamentale que sur 1.21.11, confirmée présente
 * à l'identique sur ce bracket (formule d'angle du ciel classique
 * {@code World.getSkyAngle}/{@code LunarWorldView} en dérive en interne,
 * pas besoin de la retracer — un seul point d'interception suffit).
 */
@Mixin(targets = "net.minecraft.world.World")
public abstract class WorldTimeMixin116 {

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
            LauncherLog.err("[WorldTimeMixin116] la$fakeDayTime: " + t);
        }
    }
}
