package com.yuyuframe.launcheragent.apimixin.v1_21_11.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CLIENT_TICK} sur 1.21.11 — pendant de {@code ClientTickMixin261}
 * ({@code Minecraft.tick} → {@code MinecraftClient.tick}, même nom en Yarn).
 *
 * <h2>⚠️ Classe chargée tôt — à surveiller au premier test</h2>
 *
 * {@code MinecraftClient} se charge très tôt. L'ancien hub
 * {@code mixin.client.GlobalUiRenderMixin} avait renoncé à la cibler : dans un
 * modpack de ~100 mods, elle était déjà chargée avant notre config Mixin, et
 * le retransform à chaud qui devait y ajouter le handler était refusé par la
 * JVM ({@code ClassFormatError}). Ce mixin est pourtant du même type que
 * {@code GlobalTickMixin1214}, qui tissait {@code MinecraftClient.tick} sans
 * problème en 1.21.4 — d'où le portage, mais sous surveillance : si le log
 * montre ce refus, le repli est de dispatcher {@code CLIENT_TICK} depuis le
 * hub logique, qui tourne à chaque frame.
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ClientTickMixin1211 {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void la$dispatchClientTick(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CLIENT_TICK, null);
    }
}
