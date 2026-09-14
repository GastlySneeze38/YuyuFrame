package com.yuyuframe.launcheragent.apimixin.v1_8_9.lwjgl;

import com.yuyuframe.launcheragent.lwjgl2compat.LegacyAlias;
import com.yuyuframe.launcheragent.lwjgl2compat.LegacyPublic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Pendant ARB de {@link GL20LegacyMixin189} sur
 * {@code org.lwjgl.opengl.ARBShaderObjects} : les 12 méthodes que la 1.8.9
 * appelle quand le pilote n'offre que l'extension ARB. En LWJGL 3 le type
 * se place AVANT le suffixe ({@code glUniform1ivARB}), en LWJGL 2 il n'y en
 * avait pas ({@code glUniform1ARB}).
 *
 * <p>Signatures vérifiées au javap sur lwjgl-opengl 3.4.1. Logique reprise de
 * {@code ARBShaderObjectsMixin} de legacy-lwjgl3 (moehreag, LGPL-2.1).
 */
@Mixin(targets = "org.lwjgl.opengl.ARBShaderObjects", remap = false)
public abstract class ARBShaderObjectsLegacyMixin189 {

	@LegacyAlias("glUniform1ARB")
	@Shadow
	public static void glUniform1ivARB(int location, IntBuffer value) {
	}

	@LegacyAlias("glUniform1ARB")
	@Shadow
	public static void glUniform1fvARB(int location, FloatBuffer value) {
	}

	@LegacyAlias("glUniform2ARB")
	@Shadow
	public static void glUniform2ivARB(int location, IntBuffer value) {
	}

	@LegacyAlias("glUniform2ARB")
	@Shadow
	public static void glUniform2fvARB(int location, FloatBuffer value) {
	}

	@LegacyAlias("glUniform3ARB")
	@Shadow
	public static void glUniform3ivARB(int location, IntBuffer value) {
	}

	@LegacyAlias("glUniform3ARB")
	@Shadow
	public static void glUniform3fvARB(int location, FloatBuffer value) {
	}

	@LegacyAlias("glUniform4ARB")
	@Shadow
	public static void glUniform4ivARB(int location, IntBuffer value) {
	}

	@LegacyAlias("glUniform4ARB")
	@Shadow
	public static void glUniform4fvARB(int location, FloatBuffer value) {
	}

	@LegacyAlias("glUniformMatrix2ARB")
	@Shadow
	public static void glUniformMatrix2fvARB(int location, boolean transpose, FloatBuffer value) {
	}

	@LegacyAlias("glUniformMatrix3ARB")
	@Shadow
	public static void glUniformMatrix3fvARB(int location, boolean transpose, FloatBuffer value) {
	}

	@LegacyAlias("glUniformMatrix4ARB")
	@Shadow
	public static void glUniformMatrix4fvARB(int location, boolean transpose, FloatBuffer value) {
	}

	@Shadow
	public static void glShaderSourceARB(int shader, CharSequence string) {
	}

	/** LWJGL 2 passait la source en octets ; LWJGL 3 attend une chaîne. */
	@LegacyPublic
	@Unique
	private static void glShaderSourceARB(int shader, ByteBuffer string) {
		byte[] data = new byte[string.limit()];
		string.position(0);
		string.get(data);
		string.position(0);
		glShaderSourceARB(shader, new String(data));
	}
}
