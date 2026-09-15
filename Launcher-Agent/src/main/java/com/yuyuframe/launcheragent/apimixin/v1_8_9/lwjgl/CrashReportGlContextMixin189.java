package com.yuyuframe.launcheragent.apimixin.v1_8_9.lwjgl;

import org.lwjgl.opengl.GL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Callable;

/**
 * Rapport de crash d'un plantage survenu AVANT la création de la fenêtre :
 * la ligne « OpenGL » du rapport interroge le contexte GL, qui n'existe pas
 * encore — en LWJGL 3 {@code GL.getCapabilities()} lève
 * {@code IllegalStateException}, et le rapport lui-même plantait, masquant la
 * vraie cause. Sans contexte courant, la ligne dit simplement qu'il n'y en a
 * pas, au lieu d'interroger OpenGL.
 *
 * <p>Cible vérifiée au javap sur 1.8.9 : dans
 * {@code MinecraftClient.addSystemDetailsToCrashReport}, les appels
 * {@code CrashReportSection.add(String, Callable)} sont dans l'ordre
 * « Launched Version », « LWJGL », « OpenGL » → ordinal 2. Idée reprise de
 * {@code MixinMinecraftFixEarlyCrashNoReports} de legacy-lwjgl3 (moehreag,
 * LGPL-2.1).
 *
 * <h2>{@code @ModifyArg} et non {@code @WrapOperation} (2026-09-15)</h2>
 *
 * La première version enveloppait l'appel avec un receveur typé
 * {@code Object}. MixinExtras VALIDE la signature : sur cette version
 * obfusquée, il attend {@code c} ({@code CrashReportSection}) et refuse
 * {@code Object} — l'échec rendait TOUTE la classe {@code MinecraftClient} non
 * transformée, tick client compris (premier lancement de la 1.8.9 dégelée).
 * Remplacer seulement le {@code Callable} ne fait apparaître aucun type du jeu.
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class CrashReportGlContextMixin189 {

	@ModifyArg(method = "addSystemDetailsToCrashReport(Lnet/minecraft/util/crash/CrashReport;)Lnet/minecraft/util/crash/CrashReport;",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/util/crash/CrashReportSection;add(Ljava/lang/String;Ljava/util/concurrent/Callable;)V",
			ordinal = 2),
		index = 1)
	private Callable<?> la$openGlOnlyWithContext(Callable<?> original) {
		return () -> {
			try {
				GL.getCapabilities();
			} catch (IllegalStateException noContext) {
				return "~~ pas de contexte OpenGL courant ~~";
			}
			return original.call();
		};
	}
}
