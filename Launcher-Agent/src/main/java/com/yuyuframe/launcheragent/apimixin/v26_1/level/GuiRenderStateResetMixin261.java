package com.yuyuframe.launcheragent.apimixin.v26_1.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiRenderStateMixin#clearExtraRenderData} (fabric-rendering-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#GUI_RENDER_STATE_RESET} — même
 * classe que {@code UiVanillaItemRenderer} exploite déjà par réflexion
 * manuelle ({@code flushPendingModernItemIconsFromState}). Aucun paramètre
 * utile ({@code reset()} n'en prend aucun) — ctx {@code null}, ce hook est un
 * simple signal de timing.
 */
@Mixin(targets = "net.minecraft.client.renderer.state.gui.GuiRenderState")
abstract class GuiRenderStateResetMixin261 {

    @Inject(method = "reset", at = @At("TAIL"))
    private void la$dispatchGuiRenderStateReset(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.GUI_RENDER_STATE_RESET, null);
    }
}
