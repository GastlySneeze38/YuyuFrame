package com.yuyuframe.launcheragent.apimixin.v1_8_9.lwjgl;

import com.yuyuframe.launcheragent.lwjgl2compat.LegacyAlias;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Recrée sur {@code org.lwjgl.openal.AL10} (LWJGL 3) les 3 méthodes LWJGL 2
 * appelées par paulscode ({@code librarylwjglopenal}), la bibliothèque de son
 * de la 1.8.9. Logique reprise de {@code AL10Mixin} de legacy-lwjgl3
 * (moehreag, LGPL-2.1).
 */
@Mixin(targets = "org.lwjgl.openal.AL10", remap = false)
public abstract class AL10LegacyMixin189 {

	@LegacyAlias("alListener")
	@Shadow
	public static void alListenerfv(int paramName, FloatBuffer values) {
	}

	@LegacyAlias("alSource")
	@Shadow
	public static void alSourcefv(int source, int param, FloatBuffer values) {
	}

	@LegacyAlias("alSourceStop")
	@Shadow
	public static void alSourceStopv(IntBuffer sources) {
	}
}
