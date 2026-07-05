package com.yuyuframe.launcheragent.mixin.client.v1_20_4;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Équivalent 1.17-1.20.4 de {@code MixinCrosshair116} — DIFFÉRENCE RÉELLE
 * (pas juste un renommage de package) : depuis la refonte du rendu HUD
 * ({@code MatrixStack}/{@code VertexConsumerProvider} remplacés par un objet
 * {@code DrawContext} unique, introduite progressivement 1.17→1.20), {@code
 * InGameHud.renderCrosshair} prend désormais un {@code
 * net.minecraft.client.gui.DrawContext} et non plus un {@code
 * net.minecraft.client.util.math.MatrixStack} — vérifié directement dans
 * mappings/yarn-1.20.4-mergedv2.jar : {@code (Lewu;)V d method_1736
 * renderCrosshair}, avec {@code ewu} = {@code net/minecraft/client/gui/DrawContext}
 * (PAS {@code MatrixStack}, confirmé en résolvant la classe du paramètre).
 * Aucun stub de compilation nécessaire pour ce type : comme pour
 * {@code GlobalUiRenderMixin} (1.21.11, RenderTickCounter), la signature
 * cible n'est qu'une chaîne dans l'annotation Mixin, jamais un vrai paramètre
 * Java capturé par le handler.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class MixinCrosshair1204 {

    @Inject(method = "renderCrosshair(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$hideVanillaCrosshair(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("custom-crosshair");
            if (module != null && module.isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinCrosshair1204] la$hideVanillaCrosshair: " + t);
        }
    }
}
