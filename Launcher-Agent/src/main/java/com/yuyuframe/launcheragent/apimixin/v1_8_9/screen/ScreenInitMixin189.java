package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#SCREEN_INIT} sur 1.8.9 — {@code Screen.init(MinecraftClient, int, int)},
 * TAIL. Pendant de {@code init(II)V} en 1.21.11 : c'est elle qui vide les
 * boutons puis appelle {@code init()} (javap {@code axu.a(ave,II)V}).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public abstract class ScreenInitMixin189 {

    @Inject(method = "init(Lnet/minecraft/client/MinecraftClient;II)V", at = @At("TAIL"), require = 0)
    private void la$dispatchScreenInit(@Coerce Object client, int width, int height, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SCREEN_INIT, this);
    }
}
