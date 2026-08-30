package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

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
 * 1.21.4 — voir leur javadoc pour l'architecture complète et le garde
 * {@code ClientWorld} obligatoire.
 *
 * {@code getTimeOfDay()J} (nom YARN, method_8532 — réel Mojang {@code
 * getDayTime}, vérifié via les mappings officiels Mojang de CETTE version
 * exacte).
 */
@Mixin(targets = "net.minecraft.world.World")
public abstract class WorldTimeMixin1214 {

    private static volatile boolean diagLogged;

    @Inject(method = "getTimeOfDay()J", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$fakeDayTime(CallbackInfoReturnable<Long> cir) {
        try {
            LauncherModule moduleBase = ModuleRegistry.get("world-time");
            boolean enabled = moduleBase instanceof WorldTimeModule && moduleBase.isEnabled();

            Class<?> clientWorldClass = McReflect.yarnClass("net/minecraft/client/world/ClientWorld", "net.minecraft.client.multiplayer.ClientLevel");
            boolean isClientWorld = clientWorldClass != null && clientWorldClass.isInstance(this);

            if (!diagLogged) {
                diagLogged = true;
                LauncherLog.info("[WorldTimeMixin1214] diag: moduleBase=" + moduleBase + " enabled=" + enabled
                    + " clientWorldClass=" + clientWorldClass + " isClientWorld=" + isClientWorld
                    + " thisClass=" + this.getClass());
            }

            if (!enabled || !isClientWorld) return;
            WorldTimeModule module = (WorldTimeModule) moduleBase;
            cir.setReturnValue((long) module.time);
        } catch (Throwable t) {
            LauncherLog.err("[WorldTimeMixin1214] la$fakeDayTime: " + t);
        }
    }
}
