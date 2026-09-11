package com.yuyuframe.launcheragent.apimixin.v1_21_11.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#SCREEN_INIT} sur 1.21.11 — pendant EXACT de
 * {@code ScreenInitMixin261} : {@code Screen.init(II)V}, TAIL.
 *
 * <p>⚠️ {@code Screen} a deux {@code init} ({@code init()V} et
 * {@code init(II)V}, officiels {@code bg_} et {@code b}) — le descripteur
 * complet est obligatoire, c'est le cas qui a motivé la recherche par
 * descripteur du refmap (voir {@code resolveOfficialMethodName}).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public abstract class ScreenInitMixin1211 {

    @Inject(method = "init(II)V", at = @At("TAIL"), require = 0)
    private void la$dispatchScreenInit(int width, int height, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SCREEN_INIT, this);
    }
}
