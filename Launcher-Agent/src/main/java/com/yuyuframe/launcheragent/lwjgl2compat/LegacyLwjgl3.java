package com.yuyuframe.launcheragent.lwjgl2compat;

import com.yuyuframe.launcheragent.lwjgl2compat.glfw.GLFWMouseImplementation;
import org.lwjgl.Sys;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.Display;

/**
 * Point d'entrée de la couche de compatibilité LWJGL 2 → LWJGL 3 de la 1.8.9.
 *
 * <p>Remplace à la fois {@code LegacyLWJGL3} (options, journal) et
 * {@code LegacyLWJGL3Internal} (presse-papier, liens) de legacy-lwjgl3
 * (moehreag, LGPL-2.1). Là-bas, l'implémentation était greffée par Mixin sur
 * une classe vide ; ici elle est écrite directement : cette classe vit dans
 * {@code lwjgl2-compat.jar}, sur le classpath du jeu, et n'a besoin d'aucun
 * loader.
 *
 * <p>Backend GLFW uniquement (décision D9) — pas de SDL3, pas d'IME.
 */
public final class LegacyLwjgl3 {

	private LegacyLwjgl3() {
	}

	/**
	 * Mise à l'échelle du framebuffer sur écran HiDPI. Même clé et même
	 * variable d'environnement que legacy-lwjgl3, pour que sa documentation
	 * reste valable.
	 */
	public static final boolean SCALE_FRAMEBUFFER =
		readBooleanOption("legacy_lwjgl3.scale_framebuffer", "LEGACY_LWJGL3_SCALE_FRAMEBUFFER", true);

	private static boolean readBooleanOption(String propertyKey, String envVarName, boolean defaultValue) {
		String property = System.getProperty(propertyKey);
		if (property != null) {
			return Boolean.parseBoolean(property);
		}
		String envVar = System.getenv(envVarName);
		if (envVar != null) {
			return Boolean.parseBoolean(envVar) || "1".equals(envVar);
		}
		return defaultValue;
	}

	// Vrai par défaut : la souris est créée avant que le client intégré ait
	// relu ses réglages, qui la coupent ensuite s'il le faut.
	private static volatile boolean nativeMouseWanted = true;

	public static boolean nativeMouseWanted() {
		return nativeMouseWanted;
	}

	/**
	 * Choisit la souris brute native (content_core.dll) ou celle de GLFW,
	 * tout de suite si la souris existe déjà, sinon à sa création. Appelé par
	 * le module « Optimisation » du client intégré, depuis le thread du jeu.
	 */
	public static void setNativeMouse(boolean wanted) {
		nativeMouseWanted = wanted;
		if (LWJGLImplementationUtils._mouseImplementation instanceof GLFWMouseImplementation mouse) {
			mouse.setNativeRawInput(wanted);
		}
	}

	// Faux par défaut : le plein écran exclusif est celui que le jeu a
	// toujours eu, et celui que préfèrent les joueurs qui cherchent la latence
	// la plus basse.
	private static volatile boolean borderlessFullscreen = false;

	public static boolean borderlessFullscreen() {
		return borderlessFullscreen;
	}

	/**
	 * Choisit le plein écran sans bordure (vrai) ou exclusif (faux). Si le jeu
	 * est déjà en plein écran, il change de mode tout de suite. Appelé par le
	 * module « Optimisation » du client intégré, depuis le thread du jeu.
	 */
	public static void setBorderlessFullscreen(boolean wanted) {
		if (borderlessFullscreen == wanted) return;
		borderlessFullscreen = wanted;
		Display.reapplyFullscreen();
	}

	/**
	 * Journal minimal : la 1.8.9 vanilla n'embarque pas slf4j, et ce jar ne
	 * dépend pas de l'agent. La sortie standard du jeu est déjà recueillie par
	 * le launcher.
	 */
	public static void warn(String message, Throwable error) {
		ERR.println("[WARN] [LegacyLWJGL3] " + message + (error == null ? "" : " : " + error));
	}

	public static void warn(String message) {
		warn(message, null);
	}

	/**
	 * Ligne d'information, à la forme d'un journal de niveau 3 de l'agent
	 * ({@code LauncherLog.agent(3, …)} : « [!] [AGENT] … »). Écrite à la main :
	 * ce jar ne dépend pas de l'agent, donc pas de {@code LauncherLog} ici.
	 */
	public static void info(String message) {
		OUT.println("[!] [AGENT] [LegacyLWJGL3] " + message);
	}

	// Le launcher lit la sortie du jeu en UTF-8, alors que System.out écrit
	// dans l'encodage de Windows : les accents y arrivaient abîmés.
	private static final java.io.PrintStream OUT = utf8(java.io.FileDescriptor.out);
	private static final java.io.PrintStream ERR = utf8(java.io.FileDescriptor.err);

	private static java.io.PrintStream utf8(java.io.FileDescriptor target) {
		return new java.io.PrintStream(new java.io.FileOutputStream(target), true, java.nio.charset.StandardCharsets.UTF_8);
	}

	/** Presse-papier via GLFW — remplace l'AWT de {@code Screen.getClipboard}. */
	public static String getClipboard() {
		String text = GLFW.glfwGetClipboardString(Display.getHandle());
		return text == null ? "" : text;
	}

	/** Presse-papier via GLFW — remplace l'AWT de {@code Screen.setClipboard}. */
	public static void setClipboard(String text) {
		GLFW.glfwSetClipboardString(Display.getHandle(), text);
	}

	/** Ouverture d'un lien sans AWT — remplace {@code Screen.openLink}. */
	public static void openLink(String uri) {
		Sys.openURL(uri);
	}
}
