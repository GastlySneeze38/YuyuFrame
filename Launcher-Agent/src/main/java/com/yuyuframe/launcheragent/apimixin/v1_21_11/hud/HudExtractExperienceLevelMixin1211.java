package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#HUD_EXTRACT_EXPERIENCE_LEVEL} sur 1.21.11 — pendant de
 * {@code HudExtractExperienceLevelMixin261}.
 *
 * <pre>
 *   26.1.2 : ContextualBarRenderer.extractExperienceLevel (statique)
 *   1.21.11 : Bar.drawExperienceLevel (statique, invokestatic gnf.a(gir,gio,I))
 * </pre>
 *
 * Statique : pas de receveur, donc pas de pari sur son type — seuls le
 * contexte et la police sont en {@code Object}, comme les arguments de
 * {@link HudExtractContextualBarBackgroundMixin1211}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractExperienceLevelMixin1211 {

    @WrapOperation(method = "renderMainHud(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/bar/Bar;drawExperienceLevel(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;I)V"),
        require = 0)
    private void la$dispatchExperienceLevel(Object context, Object textRenderer, int level,
                                            Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_EXPERIENCE_LEVEL, context)) {
            renderVanilla.call(context, textRenderer, level);
        }
    }
}
