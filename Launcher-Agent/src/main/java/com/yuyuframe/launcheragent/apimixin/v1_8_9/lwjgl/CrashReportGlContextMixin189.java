package com.yuyuframe.launcheragent.apimixin.v1_8_9.lwjgl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.opengl.GL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.Callable;

/**
 * Rapport de crash d'un plantage survenu AVANT la création de la fenêtre :
 * la ligne « OpenGL » du rapport interroge le contexte GL, qui n'existe pas
 * encore — en LWJGL 3 {@code GL.getCapabilities()} lève
 * {@code IllegalStateException}, et le rapport lui-même plantait, masquant la
 * vraie cause. On n'ajoute la ligne que si un contexte est courant.
 *
 * <p>Cible vérifiée au javap sur 1.8.9 : dans
 * {@code MinecraftClient.addSystemDetailsToCrashReport}, les appels
 * {@code CrashReportSection.add(String, Callable)} sont dans l'ordre
 * « Launched Version », « LWJGL », « OpenGL » → ordinal 2. Repris de
 * {@code MixinMinecraftFixEarlyCrashNoReports} de legacy-lwjgl3 (moehreag,
 * LGPL-2.1). Receveur typé {@code Object}, même pari que les autres
 * {@code @WrapOperation} de la tranche.
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class CrashReportGlContextMixin189 {

	@WrapOperation(method = "addSystemDetailsToCrashReport(Lnet/minecraft/util/crash/CrashReport;)Lnet/minecraft/util/crash/CrashReport;",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/util/crash/CrashReportSection;add(Ljava/lang/String;Ljava/util/concurrent/Callable;)V",
			ordinal = 2))
	private void la$addOpenGlOnlyWithContext(Object section, String key, Callable<String> value,
											 Operation<Void> original) {
		try {
			GL.getCapabilities();
		} catch (IllegalStateException noContext) {
			return;
		}
		original.call(section, key, value);
	}
}
