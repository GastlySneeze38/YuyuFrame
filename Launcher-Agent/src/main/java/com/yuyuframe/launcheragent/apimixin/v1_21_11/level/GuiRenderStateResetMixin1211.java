package com.yuyuframe.launcheragent.apimixin.v1_21_11.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#GUI_RENDER_STATE_RESET} sur 1.21.11 — pendant de
 * {@code GuiRenderStateResetMixin261} : {@code GuiRenderState.reset} (26.1.2)
 * devient {@code GuiRenderState.clear()V} dans Yarn 1.21.11 — même classe
 * Mojang, paquet {@code gui.render.state} au lieu de {@code renderer.state.gui}.
 */
@Mixin(targets = "net.minecraft.client.gui.render.state.GuiRenderState")
public abstract class GuiRenderStateResetMixin1211 {

    @Inject(method = "clear()V", at = @At("TAIL"), require = 0)
    private void la$dispatchGuiRenderStateReset(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.GUI_RENDER_STATE_RESET, null);
    }
}
