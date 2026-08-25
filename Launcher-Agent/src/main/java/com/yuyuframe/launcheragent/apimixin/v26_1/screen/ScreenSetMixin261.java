package com.yuyuframe.launcheragent.apimixin.v26_1.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte (simplifié) {@code MinecraftMixin#checkThreadOnDev} (fabric-screen-api-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#SCREEN_SET} — transition
 * d'écran, HEAD de {@code setScreen} (avant que le nouvel écran ne soit
 * effectivement posé).
 *
 * ⚠️ RETIRÉ de {@code mixins.launcheragent-apimixin-26.1.json} (bissection
 * en jeu, 2026-08-24, voir [[project_mc_261_port]] §10) : testé ISOLÉMENT
 * (aucun autre mixin apimixin sur {@code Minecraft} présent), provoque quand
 * même un {@code VerifyError} "Bad type on operand stack" sur {@code
 * Minecraft.setScreen} lui-même. Bissection complète sur les 4 mixins
 * {@code apimixin} qui ciblaient {@code Minecraft} (celui-ci, {@code
 * MinecraftAccessor261}, {@code ClientTickMixin261}, {@code
 * ClientLevelLoadMixin261}) : 0 présent = sain, N'IMPORTE LEQUEL des 4 seul
 * = crash — pas un défaut de CE mixin précis, mais une incompatibilité entre
 * "au moins un mixin apimixin sur Minecraft" et un ou plusieurs autres mods
 * du modpack qui modifient déjà cette classe (au moins {@code
 * fabric-screen-api-v1}, qui a lui-même un mixin sur {@code setScreen} — et
 * {@code fabric-data-generation-api-v1}, dont le mixin sur {@code Minecraft}
 * échoue DÉJÀ avant toute modification de notre part, voir log de session).
 * NE PAS remettre sans revalider en jeu — et NE PAS ajouter le moindre
 * nouveau mixin {@code apimixin} sur {@code Minecraft} sans le même risque.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class ScreenSetMixin261 {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void la$dispatchScreenSet(Screen screen, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SCREEN_SET, screen);
    }
}
