package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_ARMOR} sur 1.21.11 — {@code InGameHud.renderArmor}.
 *
 * <p>⚠️ STATIQUE, comme en 26.1.2 (vérifié javap : {@code private static void
 * a(gir, ddm, int, int, int, int)}) — le handler doit l'être aussi, sinon
 * {@code InvalidInjectionException} fatale pour TOUTE la classe cible (voir
 * {@code HudExtractArmorMixin261}, qui a cassé le crosshair en collatéral).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractArmorMixin1211 {

    @Inject(method = "renderArmor(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;IIII)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void la$dispatchArmor(@Coerce Object context, @Coerce Object player,
                                         int i, int j, int k, int x, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_ARMOR, context)) {
            ci.cancel();
        }
    }
}
