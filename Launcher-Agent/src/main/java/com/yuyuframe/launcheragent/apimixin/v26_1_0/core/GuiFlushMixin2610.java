package com.yuyuframe.launcheragent.apimixin.v26_1_0.core;

import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.GuiFlushMixin2610} (supprimé
 * le 2026-09-16 ; voir l'historique git pour l'historique complet — piège classloader Knot, timing HEAD
 * vs après {@code Lighting.setupFor}) — logique IDENTIQUE, package apimixin
 * uniquement. Reste "hub" (comme {@link GlobalUiRenderMixin2610}), pas de
 * dispatch {@code VanillaHookRegistry}.
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class GuiFlushMixin2610 {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/platform/Lighting;setupFor(Lcom/mojang/blaze3d/platform/Lighting$Entry;)V",
                 shift = At.Shift.AFTER))
    private void la$flushPendingItemIcons(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.flushPendingModernItemIcons(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GuiFlushMixin2610 (apimixin): " + t);
        }
    }
}
