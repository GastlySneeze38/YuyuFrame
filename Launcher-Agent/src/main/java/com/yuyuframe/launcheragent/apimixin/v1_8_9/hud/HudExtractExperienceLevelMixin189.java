package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#HUD_EXTRACT_EXPERIENCE_LEVEL} sur 1.8.9 — le chiffre du
 * niveau, dessiné dans {@code InGameHud.renderExperienceBar} par cinq
 * {@code TextRenderer.draw(String, int, int, int)} (quatre contours noirs puis
 * le texte vert, javap {@code avo.b(avr,I)V}).
 *
 * <p>{@code @WrapOperation} et non {@code @WrapWithCondition} : l'appel renvoie
 * un {@code int} (largeur dessinée), que la méthode ignore — on renvoie 0
 * quand le dessin est sauté. Voir {@code HudExtractContextualBarBackgroundMixin189}
 * pour la barre elle-même.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractExperienceLevelMixin189 {

    @WrapOperation(method = "renderExperienceBar(Lnet/minecraft/client/util/Window;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;III)I"),
        require = 0)
    private int la$dispatchExperienceLevel(Object textRenderer, String text, int x, int y, int color,
                                           Operation<Integer> original) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_EXPERIENCE_LEVEL, null)) return 0;
        return original.call(textRenderer, text, x, y, color);
    }
}
