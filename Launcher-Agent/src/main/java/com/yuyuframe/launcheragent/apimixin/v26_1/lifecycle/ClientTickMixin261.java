package com.yuyuframe.launcheragent.apimixin.v26_1.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code MinecraftMixin#onStartTick} (fabric-lifecycle-events-v1, voir
 * mixinapi/26.1.2) vers {@link HookPoint#CLIENT_TICK} — HEAD de
 * {@code Minecraft.tick()}, base de quasi tous les modules à logique
 * périodique. Original Fabric a aussi un END_CLIENT_TICK (RETURN) — non
 * porté ici, à ajouter si un besoin réel de "fin de tick" se présente.
 *
 * ⚠️ RETIRÉ de {@code mixins.launcheragent-apimixin-26.1.json} (bissection
 * en jeu, 2026-08-24, voir [[project_mc_261_port]] §10) : la simple présence
 * de ce mixin dans la config — même NON tissé, aucun module ne
 * s'enregistrant sur {@code CLIENT_TICK} — corrompait le bytecode généré
 * pour {@code Minecraft.setScreen} (VerifyError "Bad type on operand
 * stack"), confirmé par bissection isolée (retiré seul = crash disparaît,
 * remis seul = crash revient). Cause probable : bug Sponge Mixin 0.8.7 /
 * ASM COMPUTE_FRAMES sur class file version 69 (Java 25) lors de la
 * préparation d'un {@code @Inject} sur {@code Minecraft.tick()}
 * spécifiquement (méthode voisine de plusieurs lambdas synthétiques). NE
 * PAS remettre dans le JSON sans revalider en jeu, isolément d'abord — le
 * ticking global passe de toute façon déjà par
 * {@code GlobalUiRenderMixin261} → {@code ModuleRegistry.tickAll()} (hub,
 * voir ce fichier), donc {@code CLIENT_TICK} reste un doublon tant qu'aucun
 * module n'en a explicitement besoin.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class ClientTickMixin261 {

    @Inject(at = @At("HEAD"), method = "tick")
    private void la$dispatchClientTick(CallbackInfo info) {
        VanillaHookRegistry.dispatch(HookPoint.CLIENT_TICK, null);
    }
}
