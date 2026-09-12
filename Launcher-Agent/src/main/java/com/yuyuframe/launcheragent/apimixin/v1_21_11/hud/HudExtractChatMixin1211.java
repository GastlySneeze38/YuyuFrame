package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_CHAT} sur 1.21.11 —
 * {@code InGameHud.renderChat(DrawContext, RenderTickCounter)}, pendant de
 * {@code Gui.extractChat} en 26.1.2.
 *
 * <p>C'est LE point d'accroche du HUD de l'agent : à cet instant, tout le HUD
 * vanilla est déjà dans l'état de GUI et le chat ne l'est pas encore — nos
 * panneaux atterrissent donc exactement entre les deux (voir
 * {@code VanillaGuiLayer.installItemIconFlush}). Le handler renvoie toujours
 * {@code false} : il s'insère dans la frame, il n'annule jamais le chat.
 *
 * <p>Paramètres en {@link Coerce} {@code Object} — même convention que les
 * autres hooks HUD de cette tranche.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractChatMixin1211 {

    @Inject(method = "renderChat(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchChat(@Coerce Object context, @Coerce Object tickCounter, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CHAT, context)) {
            ci.cancel();
        }
    }
}
