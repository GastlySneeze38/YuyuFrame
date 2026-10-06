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

	/**
	 * Relève de dernière minute (YuyuFrame) : appelée par {@code Mouse.getDX()}
	 * et {@code getDY()} juste avant le calcul de la caméra, pour ne pas
	 * s'arrêter au mouvement relevé en début d'image. Écrit dx et dy dans
	 * {@code coord_buffer} et rend vrai si la source sait le faire.
	 */
	default boolean pollLateMotion(DoubleBuffer coord_buffer) {
		return false;
	}

	void readMouse(ByteBuffer readBuffer);

	void setCursorPosition(double x, double y);

	void grabMouse(boolean grab);

	boolean hasWheel();

	int getButtonCount();

	boolean isInsideWindow();
}
