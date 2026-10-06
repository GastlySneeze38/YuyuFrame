package com.yuyuframe.launcheragent.celeritas.gl.binding;

/**
 * Version-agnostic OpenGL debug message callback handler.
 */
@FunctionalInterface
public interface DebugMessageHandler {
    void handle(int source, int type, int id, int severity, String message, DebugExtension extension);
}
