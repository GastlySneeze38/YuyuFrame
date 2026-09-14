package com.yuyuframe.launcheragent.lwjgl2compat;

import com.yuyuframe.launcheragent.lwjgl2compat.glfw.GLFWKeyboardImplementation;
import com.yuyuframe.launcheragent.lwjgl2compat.glfw.GLFWMouseImplementation;
import com.yuyuframe.launcheragent.lwjgl2compat.input.CombinedInputImplementation;
import com.yuyuframe.launcheragent.lwjgl2compat.input.InputImplementation;
import com.yuyuframe.launcheragent.lwjgl2compat.input.KeyboardImplementation;
import com.yuyuframe.launcheragent.lwjgl2compat.input.MouseImplementation;

/**
 * Repris de legacy-lwjgl3 (moehreag, LGPL-2.1 — voir LICENSE-legacy-lwjgl3.txt),
 * réduit au backend GLFW : la branche SDL3 est retirée (décision D9).
 */
public class LWJGLImplementationUtils {
	private static InputImplementation _inputImplementation;
	public static MouseImplementation _mouseImplementation;
	public static KeyboardImplementation _keyboardImplementation;

	public static InputImplementation getOrCreateInputImplementation() {
		if (_inputImplementation == null) {
			_inputImplementation = createImplementation();
		}
		return _inputImplementation;
	}

	private static InputImplementation createImplementation() {
		_mouseImplementation = new GLFWMouseImplementation();
		_keyboardImplementation = new GLFWKeyboardImplementation();
		return new CombinedInputImplementation(_keyboardImplementation, _mouseImplementation);
	}

}
