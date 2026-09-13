package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * {@link HookPoint#HUD_EXTRACT_TITLE} sur 1.8.9 — titre et sous-titre, dessinés
 * en ligne dans {@code InGameHud.render(float)} par deux
 * {@code TextRenderer.draw(String, float, float, int, boolean)} dans la section
 * {@code "titleAndSubtitle"}, avant la section {@code "chat"} (javap {@code avo.a(F)V}).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractTitleMixin189 {

    @WrapOperation(method = "render(F)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFIZ)I"),
        slice = @Slice(
            from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V", args = "ldc=titleAndSubtitle"),
            to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V", args = "ldc=chat")),
        require = 0)
    private int la$dispatchTitle(Object textRenderer, String text, float x, float y, int color, boolean shadow,
                                 Operation<Integer> original) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_TITLE, null)) return 0;
        return original.call(textRenderer, text, x, y, color, shadow);
    }
}
