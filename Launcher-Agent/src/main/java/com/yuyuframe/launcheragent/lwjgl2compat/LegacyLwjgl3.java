package com.yuyuframe.launcheragent.lwjgl2compat;

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

	/**
	 * Journal minimal : la 1.8.9 vanilla n'embarque pas slf4j, et ce jar ne
	 * dépend pas de l'agent. La sortie standard du jeu est déjà recueillie par
	 * le launcher.
	 */
	public static void warn(String message, Throwable error) {
		System.err.println("[LegacyLWJGL3] " + message + (error == null ? "" : " : " + error));
	}

	public static void warn(String message) {
		warn(message, null);
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
