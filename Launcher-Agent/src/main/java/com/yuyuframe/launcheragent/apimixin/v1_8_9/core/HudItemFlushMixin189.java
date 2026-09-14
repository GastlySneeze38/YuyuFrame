package com.yuyuframe.launcheragent.apimixin.v1_8_9.core;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vidage des icônes d'item vanilla sur 1.8.9 — pendant de
 * {@code GuiFlushMixin261}/{@code GuiFlushMixin1211}.
 *
 * <p>Au {@code profiler.push("chat")} de {@code InGameHud.render(float)}
 * (javap {@code avo.a(F)V}) : tout le HUD vanilla est dessiné, le chat pas
 * encore, et l'état GUI de vanilla est en place. Les icônes mises en file par
 * les modules à la frame précédente y sont dessinées par le récepteur 1.8.9
 * ({@code Gl3VanillaItemSink189}) — même z-order que Blaze3D : au-dessus du HUD,
 * sous le chat.
 *
 * <p>Même découpage de section que {@code HudExtractTitleMixin189}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudItemFlushMixin189 {

    @Inject(method = "render(F)V",
        at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V", args = "ldc=chat"),
        require = 0)
    private void la$flushItemIcons(CallbackInfo ci) {
        try {
            UiRenderer.flushPendingLegacyHudItems(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] HudItemFlushMixin189 : " + t);
        }
    }
}
