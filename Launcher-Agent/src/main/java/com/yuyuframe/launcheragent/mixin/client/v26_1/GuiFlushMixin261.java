package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage du bracket 1.21.11 ({@link com.yuyuframe.launcheragent.mixin.client.GuiFlushMixin},
 * voir sa javadoc pour le pourquoi complet — y compris le piège de
 * classloader Knot/'app' et son correctif, ensureExposed() en tout premier)
 * pour MC 26.1+ — même point d'accroche conceptuel (HEAD de GameRenderer.render,
 * avant l'appel vanilla à guiRenderer.render(GpuBufferSlice) plus loin dans la
 * même méthode), seuls les noms de classe/méthode changent : {@code
 * net.minecraft.client.render.GameRenderer} → {@code
 * net.minecraft.client.renderer.GameRenderer}, {@code RenderTickCounter} →
 * {@code DeltaTracker} — vérifié par désassemblage (classdump maison, javap
 * ne lit pas le class file version 69 de ce build) sur le jar client 26.1.2
 * réel : {@code public void render(net.minecraft.client.DeltaTracker, boolean)},
 * aucun reset()/clear() de GuiRenderState dans cette méthode elle-même (trace
 * bytecode complète, méthode "extract" séparée en amont).
 *
 * BUG TROUVÉ (test utilisateur, 26.1.2) : {@code LinkageError: loader
 * constraint violation ... UiColor ... previously loaded by 'knot'} — voir
 * GuiFlushMixin (bracket 1.21.11) pour le détail complet du mécanisme.
 *
 * NON VÉRIFIÉ EN JEU (pas d'accès à un client Minecraft depuis cet
 * environnement).
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class GuiFlushMixin261 {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void la$flushPendingItemIcons(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.flushPendingModernItemIcons(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GuiFlushMixin261: " + t);
        }
    }
}
