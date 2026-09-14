package com.yuyuframe.launcheragent.lwjgl2compat.input;

// Repris tel quel de legacy-lwjgl3 (moehreag, LGPL-2.1) ; seul le paquet change.

import java.nio.ByteBuffer;

/**
 * @author Zarzelcow
 *
 * <p>28/09/2022 - 3:24 PM</p>
 */
public interface KeyboardImplementation {
    void createKeyboard();

    void destroyKeyboard();

    void pollKeyboard(ByteBuffer keyDownBuffer);

    void readKeyboard(ByteBuffer readBuffer);
}
