package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@link HookPoint#HUD_EXTRACT_EXPERIENCE_LEVEL} sur 1.8.9 — le chiffre du
 * niveau, dessiné dans {@code InGameHud.renderExperienceBar} par cinq
 * {@code TextRenderer.draw(String, int, int, int)} (quatre contours noirs puis
 * le texte vert, javap {@code avo.b(avr,I)V}). Voir
 * {@code HudExtractContextualBarBackgroundMixin189} pour la barre elle-même.
 *
 * <h2>Texte vide plutôt qu'appel sauté (2026-09-15)</h2>
 *
 * La première version faisait un {@code @WrapOperation} avec un receveur typé
 * {@code Object} : MixinExtras exige le vrai type obfusqué du
 * {@code TextRenderer} et refuse. {@code @ModifyArg} remplace la CHAÎNE par
 * {@code ""} : rien n'est dessiné, et la largeur rendue est ignorée par la
 * méthode. Aucun type du jeu dans la signature.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractExperienceLevelMixin189 {

    @ModifyArg(method = "renderExperienceBar(Lnet/minecraft/client/util/Window;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;III)I"),
        index = 0, require = 0)
    private String la$dispatchExperienceLevel(String text) {
        return VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_EXPERIENCE_LEVEL, null) ? "" : text;
    }
}
