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
 * Recrée sur {@code org.lwjgl.opengl.GL20} (LWJGL 3) les 12 méthodes LWJGL 2
 * appelées par Minecraft 1.8.9 (shaders de post-traitement) : les
 * {@code glUniform*} à tampon (renommés {@code …iv}/{@code …fv} en LWJGL 3) et
 * {@code glShaderSource(int, ByteBuffer)} (supprimé).
 *
 * <p>Reprise corrigée de {@code GL20Mixin} de legacy-lwjgl3 (moehreag,
 * LGPL-2.1) : l'original aliasait {@code glUniform2iv} en {@code glUniform2i},
 * nom que la 1.8.9 n'appelle pas — l'inventaire bytecode fait foi ici.
 */
@Mixin(targets = "org.lwjgl.opengl.GL20", remap = false)
public abstract class GL20LegacyMixin189 {

	@LegacyAlias("glUniform1")
	@Shadow
	public static void glUniform1iv(int location, IntBuffer value) {
	}

	@LegacyAlias("glUniform1")
	@Shadow
	public static void glUniform1fv(int location, FloatBuffer value) {
	}

	@LegacyAlias("glUniform2")
	@Shadow
	public static void glUniform2iv(int location, IntBuffer value) {
	}

	@LegacyAlias("glUniform2")
	@Shadow
	public static void glUniform2fv(int location, FloatBuffer value) {
	}

	@LegacyAlias("glUniform3")
	@Shadow
	public static void glUniform3iv(int location, IntBuffer value) {
	}

	@LegacyAlias("glUniform3")
	@Shadow
	public static void glUniform3fv(int location, FloatBuffer value) {
	}

	@LegacyAlias("glUniform4")
	@Shadow
	public static void glUniform4iv(int location, IntBuffer value) {
	}

	@LegacyAlias("glUniform4")
	@Shadow
	public static void glUniform4fv(int location, FloatBuffer value) {
	}

	@LegacyAlias("glUniformMatrix2")
	@Shadow
	public static void glUniformMatrix2fv(int location, boolean transpose, FloatBuffer value) {
	}

	@LegacyAlias("glUniformMatrix3")
	@Shadow
	public static void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer value) {
	}

	@LegacyAlias("glUniformMatrix4")
	@Shadow
	public static void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
	}

	@Shadow
	public static void glShaderSource(int shader, CharSequence string) {
	}

	/** LWJGL 2 passait la source en octets ; LWJGL 3 attend une chaîne. */
	@LegacyPublic
	@Unique
	private static void glShaderSource(int shader, ByteBuffer string) {
		byte[] data = new byte[string.limit()];
		string.position(0);
		string.get(data);
		string.position(0);
		glShaderSource(shader, new String(data));
	}
}
