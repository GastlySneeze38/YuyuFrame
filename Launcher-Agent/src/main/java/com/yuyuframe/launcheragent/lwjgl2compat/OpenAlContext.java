package com.yuyuframe.launcheragent.lwjgl2compat;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.LWJGLException;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTDisconnect;
import org.lwjgl.openal.EnumerateAllExt;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTReopenDevice;
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
			startDeviceWatch(deviceCaps);
		} catch (LWJGLException e) {
			exit();
			throw e;
		}
	}

	// ── Suivi du périphérique de sortie (YuyuFrame, 2026-10-06) ──────────────
	//
	// La 1.8.9 ouvre sa sortie son une fois, au démarrage. Casque débranché,
	// ou autre sortie choisie dans Windows : le jeu restait muet (ou sur
	// l'ancienne sortie) jusqu'au redémarrage. Les versions récentes du jeu
	// surveillent la sortie une fois par seconde ; on fait de même, et on
	// ROUVRE le périphérique sur place (ALC_SOFT_reopen_device) : contexte,
	// sources et tampons survivent, paulscode ne voit rien.

	/** Entre deux contrôles. Lister les sorties interroge Windows : pas à chaque image. */
	private static final long WATCH_PERIOD_MS = 1000L;

	private static final Object WATCH_LOCK = new Object();
	/** Change à chaque ouverture/fermeture : un surveillant d'une ouverture passée s'arrête de lui-même. */
	private static int watchGeneration;

	private static void startDeviceWatch(ALCCapabilities caps) {
		if (!caps.ALC_SOFT_reopen_device) {
			LegacyLwjgl3.info("Suivi de la sortie son indisponible (OpenAL sans ALC_SOFT_reopen_device)");
			return;
		}
		final boolean canDetectLoss = caps.ALC_EXT_disconnect;
		final boolean canListOutputs = caps.ALC_ENUMERATE_ALL_EXT;
		final int generation;
		synchronized (WATCH_LOCK) {
			generation = ++watchGeneration;
		}
		Thread watch = new Thread(() -> watchDevice(generation, canDetectLoss, canListOutputs), "YuyuFrame Audio Watch");
		watch.setDaemon(true);
		watch.start();
	}

	private static String defaultOutput() {
		return ALC10.alcGetString(0L, EnumerateAllExt.ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
	}

	private static void watchDevice(int generation, boolean canDetectLoss, boolean canListOutputs) {
		String knownDefault = canListOutputs ? defaultOutput() : null;
		while (true) {
			try {
				Thread.sleep(WATCH_PERIOD_MS);
			} catch (InterruptedException e) {
				return;
			}
			// Hors verrou : lister les sorties peut prendre plusieurs millisecondes.
			String currentDefault = canListOutputs ? defaultOutput() : null;
			synchronized (WATCH_LOCK) {
				if (generation != watchGeneration || !created) {
					return;
				}
				boolean lost = canDetectLoss && ALC10.alcGetInteger(devicePtr, EXTDisconnect.ALC_CONNECTED) == 0;
				boolean moved = currentDefault != null && !currentDefault.equals(knownDefault);
				if (!lost && !moved) {
					continue;
				}
				// Nom nul = la sortie par défaut du moment ; mêmes attributs qu'à l'ouverture.
				boolean reopened = SOFTReopenDevice.alcReopenDeviceSOFT(devicePtr, (ByteBuffer) null,
					new int[] {SOFTHRTF.ALC_HRTF_SOFT, ALC10.ALC_FALSE, 0});
				if (reopened) {
					knownDefault = currentDefault;
					LegacyLwjgl3.info("Sortie son rouverte sur " + (currentDefault == null ? "la sortie par défaut" : currentDefault)
						+ (lost ? " (l'ancienne a disparu)" : " (sortie par défaut changée)"));
				} else if (lost) {
					// Aucune sortie disponible pour l'instant : on réessaiera au prochain tour.
					knownDefault = null;
				} else {
					knownDefault = currentDefault;
					LegacyLwjgl3.warn("Sortie son par défaut changée, mais la réouverture a échoué — le son reste sur l'ancienne");
				}
			}
		}
	}

	/** Ferme contexte et périphérique — l'équivalent de {@code AL.destroy()} en LWJGL 2. */
	public static void exit() {
		synchronized (WATCH_LOCK) {
			watchGeneration++;
			closeDevice();
		}
	}

	private static void closeDevice() {
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
