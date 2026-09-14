package com.yuyuframe.launcheragent.lwjgl2compat;

import java.nio.IntBuffer;

import org.lwjgl.LWJGLException;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
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

	/** {@code AL.create()} de LWJGL 2 : périphérique par défaut, 44,1 kHz, 60 Hz. */
	public static void create() throws LWJGLException {
		create(null, 44100, 60, false);
	}

	public static void create(String deviceArguments, int contextFrequency, int contextRefresh,
							  boolean contextSynchronized) throws LWJGLException {
		if (created) {
			throw new IllegalStateException("Only one OpenAL context may be instantiated at any one time.");
		}
		try {
			devicePtr = ALC10.alcOpenDevice(deviceArguments);
			if (devicePtr == 0L || devicePtr == -1L) {
				throw new LWJGLException("Could not open ALC device");
			}
			ALCCapabilities deviceCaps = ALC.createCapabilities(devicePtr);
			if (contextFrequency == -1) {
				contextPtr = ALC10.alcCreateContext(devicePtr, (IntBuffer) null);
			} else {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					contextPtr = ALC10.alcCreateContext(devicePtr,
						attributes(contextFrequency, contextRefresh, contextSynchronized ? 1 : 0, stack));
				}
			}
			ALC10.alcMakeContextCurrent(contextPtr);
			AL.createCapabilities(deviceCaps);
			created = true;
		} catch (LWJGLException e) {
			exit();
			throw e;
		}
	}

	private static IntBuffer attributes(int frequency, int refresh, int sync, MemoryStack stack) {
		IntBuffer buffer = stack.callocInt(7);
		buffer.put(0, 0x1007); // ALC_FREQUENCY
		buffer.put(1, frequency);
		buffer.put(2, 0x1008); // ALC_REFRESH
		buffer.put(3, refresh);
		buffer.put(4, 0x1009); // ALC_SYNC
		buffer.put(5, sync);
		buffer.put(6, 0);
		return buffer;
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
