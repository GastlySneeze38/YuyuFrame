package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
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

/**
 * "Clear Lava" — même correctif/mêmes explications que
 * {@link WaterFogEnvironmentMixin261} (voir sa javadoc, notamment le crash
 * évité ET le crash "non-private static method" — copie PRIVÉE du contrôle
 * clear-vision/no-fog ici plutôt qu'un appel cross-Mixin, qui ne fonctionne
 * pas). NON RETESTÉ EN JEU au moment de l'écriture.
 */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.LavaFogEnvironment")
public abstract class LavaFogEnvironmentMixin261 {

    private static final float FAR = 1_000_000f;

    @Inject(method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"), require = 0)
    private void la$clearFog(FogData fogData, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            if (!clearVisionOrNoFogEnabled() || fogData == null) return;
            fogData.environmentalStart = FAR;
            fogData.environmentalEnd = FAR * 2f;
            fogData.renderDistanceStart = FAR;
            fogData.renderDistanceEnd = FAR * 2f;
        } catch (Throwable t) {
            LauncherLog.err("[LavaFogEnvironmentMixin261] la$clearFog: " + t);
        }
    }

    private static boolean clearVisionOrNoFogEnabled() {
        LauncherModule clearVision = ModuleRegistry.get("clear-vision");
        if (clearVision != null && clearVision.isEnabled()) return true;
        LauncherModule noFog = ModuleRegistry.get("no-fog");
        return noFog != null && noFog.isEnabled();
    }
}
