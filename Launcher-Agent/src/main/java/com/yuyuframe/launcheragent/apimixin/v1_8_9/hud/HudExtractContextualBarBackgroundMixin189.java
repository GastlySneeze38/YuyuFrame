package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@link HookPoint#HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND} sur 1.8.9 — la barre
 * contextuelle sans son chiffre : barre d'expérience et barre de saut à cheval.
 *
 * <p>Chaque barre a sa méthode, mais la barre d'expérience y dessine AUSSI le
 * niveau (autre HookPoint, voir {@code HudExtractExperienceLevelMixin189}) :
 * annuler la méthode masquerait les deux. On ne retient donc que ses
 * {@code drawTexture} — le niveau passe par le {@code TextRenderer}, javap
 * {@code avo.b(avr,I)V}. Dans {@code renderHorseHealth}, que Yarn legacy nomme
 * ainsi mais qui dessine la barre de saut (libellé de profiler {@code "jumpBar"},
 * javap {@code avo.a(avr,I)V}), tous les dessins sont la barre.
 *
 * <p>Largeur mise à zéro plutôt qu'appel sauté : voir
 * {@code HudExtractArmorMixin189} (receveur obfusqué refusé par MixinExtras).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractContextualBarBackgroundMixin189 {

    @ModifyArg(method = {
            "renderExperienceBar(Lnet/minecraft/client/util/Window;I)V",
            "renderHorseHealth(Lnet/minecraft/client/util/Window;I)V" },
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V"),
        index = 4, require = 0)
    private int la$dispatchContextualBar(int width) {
        return VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND, null) ? 0 : width;
    }
}
