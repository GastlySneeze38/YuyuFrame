package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vide la file d'icônes d'item vanilla en attente dans l'état de GUI VIVANT,
 * en tête de {@code GuiRenderer.render(GpuBufferSlice)} — portage apimixin de
 * {@code mixin.client.GuiFlushMixin} (voir ce fichier pour le pourquoi de ce
 * point d'accroche plutôt que {@code GuiRenderState.clear()}).
 *
 * <p>Filet de sécurité, comme {@code GuiFlushMixin261} l'est devenu : le vrai
 * flush de z-order correct viendra de {@code HUD_EXTRACT_CHAT}, une fois ce
 * hook branché sur 1.21.11. Une file déjà vidée rend ce second passage
 * inoffensif.
 *
 * <p>Le paramètre {@code GpuBufferSlice} n'est pas capturé : seul le MOMENT de
 * l'appel compte — voir la leçon {@code @Coerce} dans module-bracket-audit.md.
 */
@Mixin(targets = "net.minecraft.client.gui.render.GuiRenderer")
public abstract class GuiFlushMixin1211 {

    @Inject(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"))
    private void la$flushPendingItemIcons(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.flushPendingModernItemIconsFromGuiRenderer(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GuiFlushMixin1211: " + t);
        }
    }
}
