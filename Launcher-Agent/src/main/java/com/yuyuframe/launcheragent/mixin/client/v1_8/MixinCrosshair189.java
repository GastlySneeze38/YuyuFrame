package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Masque le crosshair vanilla quand notre crosshair personnalisé
 * ({@code CrosshairModule}) est actif — inspiré du vrai PvP-Mod
 * ({@code CrosshairHandler}, qui annule {@code RenderGameOverlayEvent.Pre}
 * côté Forge). Pas d'équivalent Forge ici (bootstrap vanilla), donc on cible
 * directement {@code InGameHud.showCrosshair()} (lettre officielle "b",
 * descripteur "()Z", confirmé dans mappings-1.8.9.tiny ET vérifié par javap :
 * TOUT le dessin du crosshair vanilla est gaté par
 * {@code if (this.showCrosshair()) { ...dessin... }} à l'intérieur d'une
 * grosse méthode de rendu HUD monolithique — pas de méthode dédiée séparée à
 * annuler directement).
 *
 * Aucun paramètre à capturer ({@code ()Z}, juste {@code this} implicite) —
 * contrairement à un @Redirect sur l'appel lui-même (qui aurait exigé de
 * typer l'instance {@code InGameHud} dans la signature du handler, même
 * problème que rencontré sur sprint/sneak), cibler directement la méthode
 * CALLÉE avec @Inject cancellable évite totalement ce souci — même technique
 * que {@code MixinSwingSpeed189} sur {@code getArmSwingAnimationEnd()}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class MixinCrosshair189 {

    @Inject(method = "b()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$hideVanillaCrosshair(CallbackInfoReturnable<Boolean> cir) {
        try {
            LauncherModule module = ModuleRegistry.get("custom-crosshair");
            if (module != null && module.isEnabled()) {
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinCrosshair189] la$hideVanillaCrosshair: " + t);
        }
    }
}
