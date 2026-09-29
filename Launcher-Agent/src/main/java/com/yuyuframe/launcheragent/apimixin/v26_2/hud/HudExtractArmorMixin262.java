package com.yuyuframe.launcheragent.apimixin.v26_2.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapArmorBar} vers {@link HookPoint#HUD_EXTRACT_ARMOR}
 * — voir {@link HudExtractCameraOverlayMixin262} pour l'explication du
 * pattern. {@code Gui.extractArmor} est STATIQUE (le SEUL {@code extract*}
 * de toute la classe à l'être — vérifié javap sur les ~30 méthodes de
 * {@code Gui}, contrairement à la plupart des autres extractions) — pas de
 * paramètre {@code instance} en tête ici, même chose côté Fabric d'origine
 * (vérifié dans le source).
 *
 * ⚠️ Le handler {@code @Inject} DOIT être {@code static} lui aussi
 * (2026-08-25, §21) — Mixin exige que le handler et sa cible concordent en
 * staticité. Bug PRÉ-EXISTANT (présent avant même le début de la session qui
 * a introduit ce fichier, pas une régression du portage {@code @WrapOperation
 * → @Inject}), resté dormant tant qu'aucun module ne réclamait {@code
 * HUD_EXTRACT_ARMOR} (donc jamais chargé par le filtre HookPoint) — révélé
 * dès qu'{@code ArmorDurabilityModule} a commencé à le réclamer :
 * {@code InvalidInjectionException: non-static callback method ... targets a
 * static method}. Fatal pour TOUTE la classe {@code Gui} (pas seulement ce
 * mixin) — a aussi cassé le crosshair en collatéral, alors que
 * {@code HudExtractCrosshairMixin262} lui-même n'avait rien.
 */
@Mixin(targets = "net.minecraft.client.gui.Hud")
abstract class HudExtractArmorMixin262 {

    @Inject(method = "extractArmor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIII)V", at = @At("HEAD"), cancellable = true)
    private static void la$dispatchArmor(GuiGraphicsExtractor graphics, Player player, int i, int j, int k, int x, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_ARMOR, graphics)) {
            ci.cancel();
        }
    }
}
