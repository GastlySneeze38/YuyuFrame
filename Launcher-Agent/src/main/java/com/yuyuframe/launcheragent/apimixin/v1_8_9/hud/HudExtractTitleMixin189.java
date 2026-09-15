package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * {@link HookPoint#HUD_EXTRACT_TITLE} sur 1.8.9 — titre et sous-titre, dessinés
 * en ligne dans {@code InGameHud.render(float)} par deux
 * {@code TextRenderer.draw(String, float, float, int, boolean)} dans la section
 * {@code "titleAndSubtitle"}, avant la section {@code "chat"} (javap {@code avo.a(F)V}).
 *
 * <p>Texte remplacé par {@code ""} plutôt qu'appel sauté : voir
 * {@code HudExtractExperienceLevelMixin189} (receveur obfusqué refusé par
 * MixinExtras).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractTitleMixin189 {

    @ModifyArg(method = "render(F)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFIZ)I"),
        slice = @Slice(
            from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V", args = "ldc=titleAndSubtitle"),
            to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V", args = "ldc=chat")),
        index = 0, require = 0)
    private String la$dispatchTitle(String text) {
        return VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_TITLE, null) ? "" : text;
    }
}
