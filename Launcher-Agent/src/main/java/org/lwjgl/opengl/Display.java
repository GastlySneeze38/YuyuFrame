package org.lwjgl.opengl;

import java.nio.ByteBuffer;

import org.lwjgl.LWJGLException;

/**
 * {@code Display} de LWJGL 2 au-dessus d'une fenêtre GLFW.
 *
 * <p>Repris de legacy-lwjgl3 (moehreag, LGPL-2.1). Adaptations YuyuFrame :
 * backend GLFW seul (décision D9, la branche SDL3 et l'interface scellée qui
 * la permettait sont retirées), annotations JetBrains retirées. La logique de
 * délégation est inchangée.
 *
 * <p>{@link #getHandle()} est le handle GLFW de la fenêtre : c'est lui que
 * l'agent passe à {@code UiInputPollerModern}, comme sur les versions GLFW.
 */
@SuppressWarnings("unused")
public class Display {
	static final Display.Impl impl = new GLFWDisplay();

	public static long getHandle() {
		return impl.getHandle();
	}

	public static String getTitle() {
		return impl.getTitle();
	}

	public static void setTitle(String title) {
		if (title.startsWith("Minecraft Minecraft")) {
			title = title.replace("Minecraft Minecraft", "Minecraft");
		}
		impl.setTitle(title);
	}

	public static void setHandle(long handle) {
		impl.setHandle(handle);
	}

	public static DisplayMode getDisplayMode() {
		return impl.getDisplayMode();
	}

	public static void setDisplayMode(DisplayMode mode) {
		impl.setDisplayMode(mode);
	}

	public static int getWidth() {
		return impl.getWidth();
	}

	public static void setWidth(int width) {
		impl.setWidth(width);
	}

	public static int getHeight() {
		return impl.getHeight();
	}

	public static void setHeight(int height) {
		impl.setHeight(height);
	}

	public static void setScreenWidth(int width) {
		impl.setScreenWidth(width);
	}

	public static int getScreenWidth() {
		return impl.getScreenWidth();
	}

	public static void setScreenHeight(int height) {
		impl.setScreenHeight(height);
	}

	public static int getScreenHeight() {
		return impl.getScreenHeight();
	}

	public static DisplayMode getDesktopDisplayMode() {
		return impl.getDesktopDisplayMode();
	}

	public static int setIcon(ByteBuffer[] icons) {
		return impl.setIcon(icons);
	}

	public static void update() {
		impl.update();
	}

	public static void create() throws LWJGLException {
		create(new PixelFormat());
	}

	public static void create(PixelFormat pixelFormat) throws LWJGLException {
		impl.create(pixelFormat);
	}

	public static void setFullscreen(boolean fullscreen) {
		impl.setFullscreen(fullscreen);
	}

	public static DisplayMode[] getAvailableDisplayModes() {
		return impl.getAvailableDisplayModes();
	}

	public static void destroy() {
		impl.destroy();
	}

	public static boolean isCreated() {
		return getHandle() != -1L;
	}

	public static boolean isActive() {
		return impl.isActive();
	}

	public static void setResizable(boolean isResizable) {
		impl.setResizable(isResizable);
	}

	public static void sync(int fps) {
		Sync.sync(fps);
	}

	public static void setVSyncEnabled(boolean enabled) {
		impl.setVSyncEnabled(enabled);
	}

	public static boolean wasResized() {
		return impl.wasResized();
	}

	public static boolean isVisible() {
		return impl.isVisible();
	}

	public static void makeCurrent() {
		impl.makeCurrent();
	}

	public static Drawable getDrawable() {
		return impl.getDrawable();
	}

	public static boolean isCloseRequested() {
		return impl.isCloseRequested();
	}

	public static void swapBuffers() {
		impl.swapBuffers();
	}

	public static float getPixelScaleFactor() {
		return impl.getPixelScaleFactor();
	}

	interface Impl {
		long getHandle();

		String getTitle();

		void setTitle(String title);

		void setHandle(long handle);

		DisplayMode getDisplayMode();

		void setDisplayMode(DisplayMode mode);

		int getWidth();

		void setWidth(int width);

		int getHeight();

		void setHeight(int height);

		void setScreenWidth(int width);

		int getScreenWidth();

		void setScreenHeight(int height);

		int getScreenHeight();

		DisplayMode getDesktopDisplayMode();

		int setIcon(ByteBuffer[] icons);

		void update();

		void create(PixelFormat pixelFormat) throws LWJGLException;

		void setFullscreen(boolean fullscreen);

		DisplayMode[] getAvailableDisplayModes();

		void destroy();

		boolean isActive();

		void setResizable(boolean isResizable);

		void setVSyncEnabled(boolean enabled);

		boolean wasResized();

		boolean isVisible();

		void makeCurrent();

		Drawable getDrawable();

		void swapBuffers();

		boolean isCloseRequested();

		float getPixelScaleFactor();
	}
}
