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

/**
 * NoFogModule ne faisait qu'écrire {@code FogRenderer.fogEnabled} — champ
 * hérité de l'ANCIENNE architecture (1.16.5-1.21.11), qui ne gouverne plus
 * rien sur 26.1+ : le brouillard "normal" (distance de rendu/atmosphérique,
 * le plus visible/le plus reproché) est en réalité géré par {@code
 * AtmosphericFogEnvironment}, une des implémentations du NOUVEAU système
 * {@code FogEnvironment} (voir {@code WaterFogEnvironmentMixin261} pour
 * l'architecture complète et le crash évité en ne touchant JAMAIS {@code
 * isApplicable}) — confirmé par désassemblage bytecode du vrai jar 26.1.2 :
 * c'EST cette classe (pas FogRenderer directement) qui remplit
 * environmentalStart/End + renderDistanceStart/End pour le brouillard de
 * distance standard. Même technique : repousse ces 4 distances très loin à
 * la TAIL de setupFog, jamais de toucher isApplicable.
 */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment")
public abstract class AtmosphericFogEnvironmentMixin261 {

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
            LauncherLog.err("[AtmosphericFogEnvironmentMixin261] la$clearFog: " + t);
        }
    }
}
