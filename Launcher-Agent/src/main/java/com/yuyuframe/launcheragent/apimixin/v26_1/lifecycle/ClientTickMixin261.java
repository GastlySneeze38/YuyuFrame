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
 * ⚠️ HISTORIQUE (2026-08-24, [[project_mc_261_port]] §10) — retiré du JSON
 * après bissection : la simple présence de ce mixin dans la config, même NON
 * tissé, corrompait le bytecode généré pour {@code Minecraft.setScreen}
 * ({@code VerifyError} "Bad type on operand stack"). L'hypothèse retenue
 * était un bug Sponge Mixin 0.8.7 / ASM COMPUTE_FRAMES sur class file
 * version 69 (Java 25).
 *
 * ✅ RÉTABLI le 2026-08-25 (§12). L'hypothèse « bug Mixin/ASM » était à côté :
 * le vrai défaut était chez nous — {@code
 * LauncherMixinService.getClassNode()} n'interrogeait que {@code isolatedCl},
 * qui ne voit pas le jar du jeu, donc TOUTE classe du jeu échouait à se
 * résoudre pendant COMPUTE_FRAMES, faisant retomber le supertype commun sur
 * {@code Object} et produisant des frames invalides sur une méthode
 * arbitraire de la classe tissée (la réécriture Mixin est class-wide, d'où
 * {@code setScreen} alors qu'on hooke {@code tick()}). Voir la javadoc de
 * {@code LauncherMixinService.gameClassLoader}. {@code ScreenSetMixin261},
 * même cible et même symptôme, a été revalidé en jeu sur 5 lancements sans
 * {@code VerifyError}.
 *
 * Reste gaté sur {@link HookPoint#CLIENT_TICK}, non consommé aujourd'hui :
 * le ticking global passe par {@code GlobalUiRenderMixin261} →
 * {@code ModuleRegistry.tickAll()}, donc ce hook reste un doublon tant
 * qu'aucun module ne le demande explicitement.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class ClientTickMixin261 {

    @Inject(at = @At("HEAD"), method = "tick")
    private void la$dispatchClientTick(CallbackInfo info) {
        VanillaHookRegistry.dispatch(HookPoint.CLIENT_TICK, null);
    }
}
