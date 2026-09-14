package com.yuyuframe.launcheragent.apimixin.v1_8_9.lwjgl;

import com.yuyuframe.launcheragent.lwjgl2compat.LegacyAlias;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Recrée sur {@code org.lwjgl.opengl.GL11} (LWJGL 3) les 8 méthodes LWJGL 2
 * que Minecraft 1.8.9 appelle et que LWJGL 3 a renommées (suffixe {@code v} /
 * {@code fv}). Liste issue de l'inventaire bytecode du jar 1.8.9 ; logique
 * reprise de {@code GL11Mixin} de legacy-lwjgl3 (moehreag, LGPL-2.1).
 *
 * <p>Pas une classe de Minecraft : aucun nom à traduire, {@code remap = false}.
 * Les alias sont créés par {@code LauncherMixinConfigPlugin.postApply}, voir
 * {@link LegacyAlias}.
 */
@Mixin(targets = "org.lwjgl.opengl.GL11", remap = false)
public abstract class GL11LegacyMixin189 {

	@LegacyAlias("glGetFloat")
	@Shadow
	public static void glGetFloatv(int pname, FloatBuffer params) {
	}

	@LegacyAlias("glGetInteger")
	@Shadow
	public static void glGetIntegerv(int pname, IntBuffer params) {
	}

	@LegacyAlias("glFog")
	@Shadow
	public static void glFogfv(int pname, FloatBuffer params) {
	}

	@LegacyAlias("glLight")
	@Shadow
	public static void glLightfv(int light, int pname, FloatBuffer params) {
	}

	@LegacyAlias("glLightModel")
	@Shadow
	public static void glLightModelfv(int pname, FloatBuffer params) {
	}

	@LegacyAlias("glMultMatrix")
	@Shadow
	public static void glMultMatrixf(FloatBuffer m) {
	}

	@LegacyAlias("glTexEnv")
	@Shadow
	public static void glTexEnvfv(int target, int pname, FloatBuffer params) {
	}

	@LegacyAlias("glTexGen")
	@Shadow
	public static void glTexGenfv(int coord, int pname, FloatBuffer params) {
	}
}
