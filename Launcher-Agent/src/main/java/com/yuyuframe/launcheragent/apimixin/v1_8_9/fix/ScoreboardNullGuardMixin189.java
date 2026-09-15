package com.yuyuframe.launcheragent.apimixin.v1_8_9.fix;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bug vanilla 1.8.9 : quand un serveur (Hypixel…) supprime une équipe ou un
 * objectif que le client ne connaît pas, {@code ClientPlayNetworkHandler}
 * passe {@code null} à {@code Scoreboard.removeTeam}/{@code removeObjective},
 * qui lèvent un {@code NullPointerException}. Constaté le 2026-09-15 : ~900
 * {@code removeTeam} et ~110 {@code removeObjective} en une session, chacun
 * journalisé en « Error executing task » FATAL avec sa pile complète — un flot
 * de sortie qui encombrait la console et pouvait faire geler le jeu.
 *
 * <p>Supprimer « rien » ne fait rien : l'appel est simplement ignoré.
 * Paramètres en {@code @Coerce Object} : un type obfusqué du jeu ne peut pas
 * apparaître dans la signature (voir HeldItemTransformMixin189).
 */
@Mixin(targets = "net.minecraft.scoreboard.Scoreboard")
public abstract class ScoreboardNullGuardMixin189 {

	@Inject(method = "removeTeam(Lnet/minecraft/scoreboard/Team;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void la$ignoreUnknownTeam(@Coerce Object team, CallbackInfo ci) {
		if (team == null) {
			ci.cancel();
		}
	}

	@Inject(method = "removeObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void la$ignoreUnknownObjective(@Coerce Object objective, CallbackInfo ci) {
		if (objective == null) {
			ci.cancel();
		}
	}
}
