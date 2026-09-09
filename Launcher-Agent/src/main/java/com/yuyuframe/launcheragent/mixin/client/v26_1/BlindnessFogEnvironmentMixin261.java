package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Sans cécité" (brouillard de l'effet Cécité) — même technique que {@link AtmosphericFogEnvironmentMixin261}/{@link WaterFogEnvironmentMixin261} (voir leur javadoc). */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment")
public abstract class BlindnessFogEnvironmentMixin261 {

    private static final float FAR = 1_000_000f;

    @Inject(method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"), require = 0)
    private void la$clearFog(FogData fogData, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("no-fog");
            if (module == null || !module.isEnabled() || fogData == null) return;
            fogData.environmentalStart = FAR;
            fogData.environmentalEnd = FAR * 2f;
            fogData.renderDistanceStart = FAR;
            fogData.renderDistanceEnd = FAR * 2f;
        } catch (Throwable t) {
            LauncherLog.err("[BlindnessFogEnvironmentMixin261] la$clearFog: " + t);
        }
    }
}
