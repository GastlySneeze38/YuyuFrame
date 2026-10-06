package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.celeritas.v1_8_9.debug.CeleritasDebugStrings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Ajoute les lignes du renderer à droite de l'écran F3 — portage de
 * {@code DebugOverlayMixin} de l'adaptateur {@code ornithe} de Celeritas.
 *
 * <p>Rien n'est ajouté tant que le renderer ne dessine pas : la liste rendue
 * est alors vide. {@code getRightText()} ({@code avv.c}), vérifiée sur le jar.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.DebugHud")
public abstract class DebugHudRendererMixin189 {

    @Inject(method = "getRightText()Ljava/util/List;", at = @At("RETURN"), require = 0)
    private void la$appendRendererLines(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();
        if (lines != null) {
            lines.addAll(CeleritasDebugStrings.getStringsToRender());
        }
    }
}
