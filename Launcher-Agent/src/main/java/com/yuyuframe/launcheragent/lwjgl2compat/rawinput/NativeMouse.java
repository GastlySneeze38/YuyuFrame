package com.yuyuframe.launcheragent.lwjgl2compat.rawinput;

import com.yuyuframe.launcheragent.lwjgl2compat.LegacyLwjgl3;
import com.yuyuframe.launcheragent.lwjgl2compat.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Souris brute lue par {@code content_core.dll} (docs/LauncherAgent/v1.8.9/
 * README.md § 9 bis), appelée par FFM — pas par JNI : les fonctions
 * {@code yf_mouse_*} sont cherchées par leur nom dans la DLL. Leur contrat est
 * dans {@code content-core/src/mouse/bridge.rs}.
 *
 * <p>La DLL cumule les déplacements dans deux totaux qui ne reviennent jamais
 * à zéro ; {@link #poll()} en fait la différence avec la lecture précédente.
 *
 * <p>Tout échec (DLL absente, trop ancienne, source indisponible) rend
 * {@code null} à {@link #tryStart()} : l'appelant garde alors la souris de
 * GLFW. Utilisé depuis le seul thread du jeu.
 */
public final class NativeMouse {

	/** Même valeur que {@code ABI_VERSION} dans mouse/bridge.rs. */
	private static final int ABI_VERSION = 1;

	/** {@code -Dyuyuframe.mouse.nativeRawInput=false} garde la souris de GLFW. */
	private static final String PROPERTY = "yuyuframe.mouse.nativeRawInput";

	private final MethodHandle stop;
	private final MethodHandle setActive;
	private final MethodHandle read;
	/** Les deux totaux écrits par {@code yf_mouse_read} : X puis Y. */
	private final MemorySegment totals;

	private long lastX;
	private long lastY;
	private long dx;
	private long dy;
	private boolean failed;

	private NativeMouse(MethodHandle stop, MethodHandle setActive, MethodHandle read) {
		this.stop = stop;
		this.setActive = setActive;
		this.read = read;
		this.totals = Arena.global().allocate(ValueLayout.JAVA_LONG, 2);
	}

	/** Démarre la lecture native, ou rend {@code null} si elle n'est pas possible. */
	public static NativeMouse tryStart() {
		if (OS.current() != OS.WINDOWS) return null;
		if (!Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) {
			LegacyLwjgl3.info("Souris brute native désactivée (" + PROPERTY + "=false)");
			return null;
		}
		String appData = System.getenv("APPDATA");
		if (appData == null) return null;
		// Même emplacement que ContentBridge (agent), qui la charge par JNI.
		Path dll = Path.of(appData, "YuyuFrame", "agent", "content_core.dll");
		if (!Files.isRegularFile(dll)) {
			LegacyLwjgl3.warn("Souris brute native indisponible : " + dll + " absente");
			return null;
		}
		try {
			SymbolLookup lib = SymbolLookup.libraryLookup(dll, Arena.global());
			Linker linker = Linker.nativeLinker();

			MethodHandle abi = linker.downcallHandle(lib.find("yf_mouse_abi").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
			int version = (int) abi.invokeExact();
			if (version != ABI_VERSION) {
				LegacyLwjgl3.warn("Souris brute native indisponible : content_core.dll annonce la version "
					+ version + " du contrat, " + ABI_VERSION + " attendue");
				return null;
			}

			MethodHandle start = linker.downcallHandle(lib.find("yf_mouse_start").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
			MethodHandle stop = linker.downcallHandle(lib.find("yf_mouse_stop").orElseThrow(),
				FunctionDescriptor.ofVoid());
			MethodHandle setActive = linker.downcallHandle(lib.find("yf_mouse_set_active").orElseThrow(),
				FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
			// critical : la fonction ne fait que lire deux compteurs, sans verrou
			// ni rappel vers Java — l'appel se passe de la bascule d'état du thread.
			MethodHandle read = linker.downcallHandle(lib.find("yf_mouse_read").orElseThrow(),
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), Linker.Option.critical(false));

			if ((int) start.invokeExact() == 0) {
				LegacyLwjgl3.info("Souris brute native indisponible dans cette version de content_core.dll, souris de GLFW gardée");
				return null;
			}
			NativeMouse mouse = new NativeMouse(stop, setActive, read);
			mouse.resync();
			LegacyLwjgl3.info("Souris brute native démarrée");
			return mouse;
		} catch (Throwable error) {
			// Symbole absent (DLL d'avant le raw input), accès natif refusé…
			LegacyLwjgl3.warn("Souris brute native indisponible", error);
			return null;
		}
	}

	/** Dit à la DLL si le jeu a capturé la souris. Oublie ce qui a bougé avant. */
	public void setActive(boolean active) {
		try {
			setActive.invokeExact(active ? 1 : 0);
		} catch (Throwable error) {
			fail(error);
		}
		resync();
	}

	/** Relève les totaux sans rien rendre : le prochain {@link #poll()} part d'ici. */
	public void resync() {
		poll();
		dx = dy = 0;
	}

	/**
	 * Relève ce qui a bougé depuis la lecture précédente — voir {@link #dx()}
	 * et {@link #dy()}, en comptes bruts de la souris.
	 */
	public void poll() {
		try {
			read.invokeExact(totals);
		} catch (Throwable error) {
			fail(error);
			dx = dy = 0;
			return;
		}
		long x = totals.get(ValueLayout.JAVA_LONG, 0);
		long y = totals.get(ValueLayout.JAVA_LONG, 8);
		dx = x - lastX;
		dy = y - lastY;
		lastX = x;
		lastY = y;
	}

	/** Déplacement horizontal du dernier {@link #poll()}, positif vers la droite. */
	public long dx() {
		return dx;
	}

	/** Déplacement vertical du dernier {@link #poll()}, positif vers le BAS (sens de Windows). */
	public long dy() {
		return dy;
	}

	public void stop() {
		try {
			stop.invokeExact();
		} catch (Throwable error) {
			fail(error);
		}
	}

	// Un appel qui échoue échouerait à chaque image : on le dit une seule fois.
	private void fail(Throwable error) {
		if (failed) return;
		failed = true;
		LegacyLwjgl3.warn("Appel à la souris brute native échoué", error);
	}
}
