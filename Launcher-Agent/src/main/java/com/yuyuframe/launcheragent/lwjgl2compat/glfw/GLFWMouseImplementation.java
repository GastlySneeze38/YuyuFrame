package com.yuyuframe.launcheragent.lwjgl2compat.glfw;

// Repris tel quel de legacy-lwjgl3 (moehreag, LGPL-2.1 — voir
// LICENSE-legacy-lwjgl3.txt) ; le paquet change, et la souris brute native
// (NativeMouse) y est branchée.

import com.yuyuframe.launcheragent.lwjgl2compat.input.MouseImplementation;
import com.yuyuframe.launcheragent.lwjgl2compat.rawinput.NativeMouse;
import org.lwjgl.glfw.*;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EventQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;

public class GLFWMouseImplementation implements MouseImplementation {
    protected GLFWMouseButtonCallback buttonCallback;
    private GLFWCursorPosCallback posCallback;
    private GLFWScrollCallback scrollCallback;
    private GLFWCursorEnterCallback cursorEnterCallback;
    private long windowHandle;
    private boolean grabbed;
    private boolean isInsideWindow;
    /** Souris brute lue par content_core.dll — null = souris de GLFW (YuyuFrame). */
    private NativeMouse nativeMouse;

    private final EventQueue event_queue = new EventQueue(Mouse.EVENT_SIZE);

    private final ByteBuffer tmp_event = ByteBuffer.allocate(Mouse.EVENT_SIZE);

    private double last_x;
    private double last_y;
    private double accum_dx;
    private double accum_dy;
    private double accum_dz;
    protected byte[] button_states = new byte[this.getButtonCount()];

	@Override
    public void createMouse() {
        this.windowHandle = Display.getHandle();

        // Souris brute : la nôtre (content_core.dll) si elle démarre, sinon
        // celle de GLFW. Jamais les deux : Windows n'accepte qu'une inscription
        // Raw Input par type de périphérique pour tout le processus, et GLFW
        // retire la sienne — donc la nôtre — chaque fois que le curseur est
        // libéré. Tant que son option reste éteinte, GLFW n'y touche pas.
        boolean rawDisabled = Mouse.getPrivilegedBoolean("org.lwjgl.input.Mouse.disableRawInput");
        if (!rawDisabled)
            this.nativeMouse = NativeMouse.tryStart();
        if (this.nativeMouse == null && !rawDisabled && GLFW.glfwRawMouseMotionSupported())
            GLFW.glfwSetInputMode(this.windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);

        this.buttonCallback = GLFWMouseButtonCallback.create((window, button, action, mods) -> {
            byte state = action == GLFW.GLFW_PRESS ? (byte)1 : (byte)0;
            putMouseEvent((byte) button, state, 0, System.nanoTime());
            if (button < button_states.length)
                button_states[button] = state;
        });
        this.posCallback = GLFWCursorPosCallback.create((window, xpos, ypos) -> {
            float scale = Display.getPixelScaleFactor();
            int x = (int) (xpos * scale);
            // LWJGL2: (0, 0) = the bottom-left corner
            // GLFW: (0, 0) = the top-left corner
            int y = (int) ((Display.getScreenHeight() - ypos) * scale);
            double dx = x - last_x;
            double dy = y - last_y;
            if (dx != 0 || dy != 0) {
                last_x = x;
                last_y = y;
                // Souris capturée et lue par la source native : le mouvement
                // de GLFW ne sert à rien (le jeu ne lit que getDX/getDY).
                if (grabbed && nativeMouse != null)
                    return;
                accum_dx += dx;
                accum_dy += dy;
                long nanos = System.nanoTime();
                if (grabbed) {
                    putMouseMotionEvent(dx, dy, nanos);
                } else {
                    putMouseMotionEvent(x, y, nanos);
                }
            }
        });
        this.scrollCallback = GLFWScrollCallback.create((window, xoffset, yoffset) -> {
            accum_dz += yoffset;
            putMouseEvent((byte)-1, (byte)0, (int) yoffset, System.nanoTime());
        });
        this.cursorEnterCallback = GLFWCursorEnterCallback.create((window, entered) -> this.isInsideWindow = entered);

        GLFW.glfwSetMouseButtonCallback(this.windowHandle, this.buttonCallback);
        GLFW.glfwSetCursorPosCallback(this.windowHandle, this.posCallback);
        GLFW.glfwSetScrollCallback(this.windowHandle, this.scrollCallback);
        GLFW.glfwSetCursorEnterCallback(this.windowHandle, this.cursorEnterCallback);
    }

    protected void putMouseEvent(byte button, byte state, int dz, long nanos) {
        if (grabbed)
            putMouseEventWithCoords(button, state, 0, 0, dz, nanos);
        else
            putMouseEventWithCoords(button, state, last_x, last_y, dz, nanos);
    }

    protected void putMouseEventWithCoords(byte button, byte state, double coord1, double coord2, int dz, long nanos) {
        tmp_event.clear();
        tmp_event.put(button).put(state).putDouble(coord1).putDouble(coord2).putDouble(dz).putLong(nanos);
        tmp_event.flip();
        event_queue.putEvent(tmp_event);
	}

    private void putMouseMotionEvent(double coord1, double coord2, long nanos) {
        synchronized (event_queue) {
            // Fusion dans le dernier mouvement en accès absolus sur la file —
            // sans la vue allouée par getLastEvent() à chaque événement
            // (YuyuFrame, 2026-09-16).
            int last = event_queue.lastEventOffset();
            if (last >= 0) {
                ByteBuffer queue = event_queue.rawBuffer();
                if (queue.get(last) == -1 && queue.getDouble(last + 18) == 0) {
                    if (grabbed) {
                        queue.putDouble(last + 2, queue.getDouble(last + 2) + coord1);
                        queue.putDouble(last + 10, queue.getDouble(last + 10) + coord2);
                    } else {
                        queue.putDouble(last + 2, coord1);
                        queue.putDouble(last + 10, coord2);
                    }
                    queue.putLong(last + 26, nanos);
                    return;
                }
            }

            putMouseEventWithCoords((byte) -1, (byte) 0, coord1, coord2, 0, nanos);
        }
    }

    @Override
    public void destroyMouse() {
        this.buttonCallback.free();
        this.posCallback.free();
        this.scrollCallback.free();
        this.cursorEnterCallback.free();
        if (this.nativeMouse != null) {
            this.nativeMouse.stop();
            this.nativeMouse = null;
        }
    }

    /**
     * Déplacement de la source native depuis la relève précédente, au format
     * de LWJGL 2 (Y vers le haut). Jeté quand la fenêtre n'a pas le focus :
     * la caméra ne doit pas tourner en arrière-plan.
     */
    private void putNativeMotion(DoubleBuffer coord_buffer) {
        nativeMouse.poll();
        boolean active = Display.isActive();
        coord_buffer.put(0, active ? nativeMouse.dx() : 0);
        coord_buffer.put(1, active ? -nativeMouse.dy() : 0);
    }

    @Override
    public boolean pollLateMotion(DoubleBuffer coord_buffer) {
        if (!grabbed || nativeMouse == null)
            return false;
        putNativeMotion(coord_buffer);
        return true;
    }

    private void reset() {
        this.event_queue.clearEvents();
        accum_dx = accum_dy = 0;
    }
    @Override
    public void pollMouse(DoubleBuffer coord_buffer, ByteBuffer buttons_buffer) {
        if (grabbed && nativeMouse != null) {
            putNativeMotion(coord_buffer);
        } else if (grabbed) {
            coord_buffer.put(0, accum_dx);
            coord_buffer.put(1, accum_dy);
        } else {
            coord_buffer.put(0, last_x);
            coord_buffer.put(1, last_y);
        }
        coord_buffer.put(2, accum_dz);
        accum_dx = accum_dy = accum_dz = 0;
        for (int i = 0; i < button_states.length; i++)
            buttons_buffer.put(i, button_states[i]);
    }

    @Override
    public void readMouse(ByteBuffer readBuffer) {
        event_queue.copyEvents(readBuffer);
    }

    @Override
    public void setCursorPosition(double x, double y) {
        float scale = Display.getPixelScaleFactor();
        this.last_x = x;
        this.last_y = y;
        GLFW.glfwSetCursorPos(this.windowHandle, x / scale, y / scale);
    }

    @Override
    public void grabMouse(boolean grab) {
        GLFW.glfwSetInputMode(this.windowHandle, GLFW.GLFW_CURSOR, grab ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
        if (!grab) {
            setCursorPosition(last_x, last_y);
        }
        this.grabbed = grab;
        this.reset();
        if (this.nativeMouse != null)
            this.nativeMouse.setActive(grab);
    }

    @Override
    public boolean hasWheel() {
        return true;
    }

    @Override
    public int getButtonCount() {
        return GLFW.GLFW_MOUSE_BUTTON_LAST + 1;
    }

    @Override
    public boolean isInsideWindow() {
        return grabbed || isInsideWindow;
    }
}