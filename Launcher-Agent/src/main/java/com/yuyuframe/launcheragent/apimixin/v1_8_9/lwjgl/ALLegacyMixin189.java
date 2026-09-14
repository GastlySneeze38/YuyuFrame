package com.yuyuframe.launcheragent.apimixin.v1_8_9.lwjgl;

import com.yuyuframe.launcheragent.lwjgl2compat.LegacyPublic;
import com.yuyuframe.launcheragent.lwjgl2compat.OpenAlContext;
import org.lwjgl.LWJGLException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Recrée sur {@code org.lwjgl.openal.AL} (LWJGL 3) {@code create()} et
 * {@code isCreated()} de LWJGL 2, appelés par paulscode. En LWJGL 3,
 * {@code AL} ne crée plus le contexte : tout est délégué à
 * {@link OpenAlContext}, qui reprend la logique de {@code ALMixin} de
 * legacy-lwjgl3 (moehreag, LGPL-2.1).
 *
 * <p>{@code AL.destroy()} n'est PAS recréé : il existe en LWJGL 3 mais est
 * package-private — paulscode y est redirigé par
 * {@link LibraryLWJGLOpenALMixin189}.
 */
@Mixin(targets = "org.lwjgl.openal.AL", remap = false)
public abstract class ALLegacyMixin189 {

	@LegacyPublic
	@Unique
	private static void create() throws LWJGLException {
		OpenAlContext.create();
	}

	@LegacyPublic
	@Unique
	private static boolean isCreated() {
		return OpenAlContext.isCreated();
	}
}
