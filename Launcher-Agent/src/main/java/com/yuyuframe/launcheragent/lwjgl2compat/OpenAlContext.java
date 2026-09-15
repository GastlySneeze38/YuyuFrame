package com.yuyuframe.launcheragent.lwjgl2compat;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.LWJGLException;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.system.MemoryStack;

/**
 * Contexte OpenAL « à la LWJGL 2 » : {@code AL.create()} ouvrait le
 * périphérique ET créait le contexte ; LWJGL 3 laisse les deux à l'appelant.
 *
 * <p>Logique reprise de {@code ALMixin} de legacy-lwjgl3 (moehreag, LGPL-2.1),
 * sortie du mixin : les méthodes ajoutées à {@code org.lwjgl.openal.AL} par
 * {@code ALLegacyMixin189} ne font que déléguer ici, et
 * {@code LibraryLWJGLOpenALMixin189} appelle {@link #exit()} directement — ce
 * qui supprime le {@code MethodHandle} de l'original (D4 : zéro réflexion).
 *
 * <p>Attributs du contexte (2026-09-15) : ceux de LWJGL 2 (44,1 kHz forcés,
 * rafraîchissement 60 Hz) visaient OpenAL Soft 1.15. Sous l'OpenAL Soft de
 * LWJGL 3, forcer 44,1 kHz sur un périphérique à 48 kHz ajoute un
 * rééchantillonnage (latence), et rien ne coupait l'HRTF, qu'OpenAL Soft
 * active seul sur un casque (son « saturé »). Le contexte est maintenant
 * ouvert comme en 26.1.2 : fréquence et rafraîchissement du périphérique,
 * HRTF explicitement désactivé.
 */
public final class OpenAlContext {

	private OpenAlContext() {
	}

	private static long contextPtr = -1L;
	private static long devicePtr = -1L;
	private static boolean created;

	public static boolean isCreated() {
		return created;
	}

	/** {@code AL.create()} de LWJGL 2 : périphérique par défaut, réglages du périphérique, sans HRTF. */
	public static void create() throws LWJGLException {
		if (created) {
			throw new IllegalStateException("Only one OpenAL context may be instantiated at any one time.");
		}
		try {
			devicePtr = ALC10.alcOpenDevice((ByteBuffer) null);
			if (devicePtr == 0L || devicePtr == -1L) {
				throw new LWJGLException("Could not open ALC device");
			}
			ALCCapabilities deviceCaps = ALC.createCapabilities(devicePtr);
			if (deviceCaps.ALC_SOFT_HRTF) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					IntBuffer attributes = stack.callocInt(3);
					attributes.put(0, SOFTHRTF.ALC_HRTF_SOFT);
					attributes.put(1, ALC10.ALC_FALSE);
					attributes.put(2, 0);
					contextPtr = ALC10.alcCreateContext(devicePtr, attributes);
				}
			} else {
				contextPtr = ALC10.alcCreateContext(devicePtr, (IntBuffer) null);
			}
			if (contextPtr == 0L) {
				throw new LWJGLException("Could not create ALC context");
			}
			ALC10.alcMakeContextCurrent(contextPtr);
			AL.createCapabilities(deviceCaps);
			created = true;
		} catch (LWJGLException e) {
			exit();
			throw e;
		}
	}

	/** Ferme contexte et périphérique — l'équivalent de {@code AL.destroy()} en LWJGL 2. */
	public static void exit() {
		if (contextPtr != -1L && contextPtr != 0L) {
			ALC10.alcMakeContextCurrent(0L);
			ALC10.alcDestroyContext(contextPtr);
		}
		contextPtr = -1L;
		if (devicePtr != -1L && devicePtr != 0L) {
			ALC10.alcCloseDevice(devicePtr);
		}
		devicePtr = -1L;
		created = false;
	}
}
