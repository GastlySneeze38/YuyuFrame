package com.yuyuframe.launcheragent.apimixin.v1_8_9.lwjgl;

import com.yuyuframe.launcheragent.lwjgl2compat.OpenAlContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * paulscode appelle {@code AL.destroy()}, public en LWJGL 2 mais
 * package-private en LWJGL 3 ({@code IllegalAccessError}) — et qui, là-bas,
 * ne fermerait de toute façon ni le contexte ni le périphérique ouverts par
 * {@code AL.create()}. Les deux appels sont redirigés vers
 * {@link OpenAlContext#exit()}.
 *
 * <p>Repris de {@code LibraryLWJGLOpenALMixin} de legacy-lwjgl3 (moehreag,
 * LGPL-2.1), SANS son {@code MethodHandle} (D4) : l'appel est direct. Le
 * branchement de paulscode sur le classloader de Knot, indispensable sous
 * Fabric, n'a pas lieu d'être en vanilla (tout est sur le classloader système).
 */
@Mixin(targets = "paulscode.sound.libraries.LibraryLWJGLOpenAL", remap = false)
public abstract class LibraryLWJGLOpenALMixin189 {

	@Redirect(method = "libraryCompatible", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL;destroy()V"))
	private static void la$exitOnCompatibilityCheck() {
		OpenAlContext.exit();
	}

	@Redirect(method = "cleanup", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL;destroy()V"))
	private void la$exitOnCleanup() {
		OpenAlContext.exit();
	}
}
