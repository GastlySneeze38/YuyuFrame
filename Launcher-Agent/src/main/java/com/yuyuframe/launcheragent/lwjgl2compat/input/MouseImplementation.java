package com.yuyuframe.launcheragent.lwjgl2compat.input;

// Repris tel quel de legacy-lwjgl3 (moehreag, LGPL-2.1) ; seul le paquet change.

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;

/**
 * @author Zarzelcow
 * 
 * <p>28/09/2022 - 8:58 PM</p>
 */
public interface MouseImplementation {
	void createMouse();

	void destroyMouse();

	void pollMouse(DoubleBuffer coord_buffer, ByteBuffer buttons_buffer);

	void readMouse(ByteBuffer readBuffer);

	void setCursorPosition(double x, double y);

	void grabMouse(boolean grab);

	boolean hasWheel();

	int getButtonCount();

	boolean isInsideWindow();
}
