package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#HUD_EXTRACT_SPECTATOR_ACTION} sur 1.21.11 — pendant de
 * {@code HudExtractSpectatorActionMixin261}.
 *
 * <pre>
 *   26.1.2 : SpectatorGui.extractAction(GuiGraphicsExtractor)
 *   1.21.11 : SpectatorHud.render(DrawContext)   (Mojang renderAction, gmp.b(gir))
 * </pre>
 *
 * Receveur en {@code Object} : voir {@link HudExtractContextualBarBackgroundMixin1211}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractSpectatorActionMixin1211 {

    @WrapOperation(method = "renderMainHud(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/SpectatorHud;render(Lnet/minecraft/client/gui/DrawContext;)V"),
        require = 0)
    private void la$dispatchSpectatorAction(Object spectatorHud, Object context, Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SPECTATOR_ACTION, context)) {
            renderVanilla.call(spectatorHud, context);
        }
    }
}
