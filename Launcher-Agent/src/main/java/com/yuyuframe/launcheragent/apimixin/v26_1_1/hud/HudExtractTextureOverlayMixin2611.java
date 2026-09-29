package com.yuyuframe.launcheragent.apimixin.v26_1_1.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code ClearOverlaysMixin261} (voir historique
 * {@code mixin/client/v26_1/}) — {@code Gui.extractTextureOverlay} est le
 * point de passage COMMUN à l'overlay citrouille ET au givre de neige
 * poudreuse (voir l'ancien fichier pour l'explication complète de ce
 * partage). {@code ctx} = le CHEMIN de la texture demandée ({@code String},
 * depuis le 2026-09-11 — c'était l'{@code Identifier} lui-même, un type
 * 26.1.2 que les modules ne peuvent pas tester sur une version obfusquée, voir
 * la javadoc du HookPoint et {@code HudExtractTextureOverlayMixin1211}).
 * NoPumpkinOverlayModule/ClearVisionModule filtrent chacun par leur propre
 * segment de chemin ("pumpkin"/"powder_snow") ; ce Mixin ne fait que
 * dispatcher.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
public abstract class HudExtractTextureOverlayMixin2611 {

    @Inject(method = "extractTextureOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchTextureOverlay(GuiGraphicsExtractor extractor, Identifier textureId, float alpha, CallbackInfo ci) {
        if (textureId != null && VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY, textureId.getPath())) {
            ci.cancel();
        }
    }
}
