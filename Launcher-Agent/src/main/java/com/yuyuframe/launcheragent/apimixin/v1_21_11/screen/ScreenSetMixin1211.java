package com.yuyuframe.launcheragent.apimixin.v1_21_11.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#SCREEN_SET} sur 1.21.11 — pendant de {@code ScreenSetMixin261} :
 * {@code MinecraftClient.setScreen(Screen)}, HEAD. Écran en {@link Coerce}
 * {@code Object} (classe obfusquée).
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ScreenSetMixin1211 {

    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"), require = 0)
    private void la$dispatchScreenSet(@Coerce Object screen, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SCREEN_SET, screen);
    }
}
