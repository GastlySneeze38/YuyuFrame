package com.yuyuframe.launcheragent.apimixin.v26_1.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte (simplifié) {@code MinecraftMixin#checkThreadOnDev} (fabric-screen-api-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#SCREEN_SET} — transition
 * d'écran, HEAD de {@code setScreen} (avant que le nouvel écran ne soit
 * effectivement posé).
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class ScreenSetMixin261 {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void la$dispatchScreenSet(Screen screen, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SCREEN_SET, screen);
    }
}
