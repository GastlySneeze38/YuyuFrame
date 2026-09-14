package com.yuyuframe.launcheragent.lwjgl2compat;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Repris de legacy-lwjgl3 (moehreag, LGPL-2.1 — voir LICENSE-legacy-lwjgl3.txt).
 * Seule adaptation : journalisation via {@link LegacyLwjgl3#warn} (pas de slf4j
 * en 1.8.9 vanilla).
 */
public enum OS {
	LINUX(),
	OSX() {
		@Override
		protected String[] getProcessArguments(String uri) {
			return new String[]{"open", uri};
		}
	},
	SOLARIS(),
	UNKNOWN(),
	WINDOWS() {
		@Override
		protected String[] getProcessArguments(String uri) {
			return new String[]{"rundll32", "url.dll,FileProtocolHandler", uri};
		}
	};

	public void open(String uri) {
		try {
			Process process = Runtime.getRuntime().exec(this.getProcessArguments(uri));
			process.getInputStream().close();
			process.getErrorStream().close();
			process.getOutputStream().close();
		} catch (IOException e) {
			LegacyLwjgl3.warn("Couldn't open uri '" + uri + "'", e);
		}
	}

	protected String[] getProcessArguments(String uri) {
		String string = uri;
		try {
			var parsed = new URI(uri);
			if ("file".equals(parsed.getScheme())) {
				string = string.replace("file:", "file://");
			}
		} catch (Exception ignored) {
		}

		return new String[]{"xdg-open", string};
	}

	private static final OS CURRENT = getPlatform();

	public static OS current() {
		return CURRENT;
	}

	private static OS getPlatform() {
		String string = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		if (string.contains("win")) {
			return OS.WINDOWS;
		} else if (string.contains("mac")) {
			return OS.OSX;
		} else if (string.contains("solaris") || string.contains("sunos")) {
			return OS.SOLARIS;
		} else if (string.contains("linux") || string.contains("unix")) {
			return OS.LINUX;
		}
		return OS.UNKNOWN;
	}
}
