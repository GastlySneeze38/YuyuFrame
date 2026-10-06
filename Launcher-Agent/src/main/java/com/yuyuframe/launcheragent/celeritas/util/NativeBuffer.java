package com.yuyuframe.launcheragent.celeritas.util;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Collectors;

import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;

public class NativeBuffer {
    private static final ReferenceQueue<NativeBuffer> RECLAIM_QUEUE = new ReferenceQueue<>();
    private static final Reference2ReferenceMap<Reference<NativeBuffer>, BufferReference> ACTIVE_BUFFERS =
            Reference2ReferenceMaps.synchronize(new Reference2ReferenceOpenHashMap<>());

    private static long ALLOCATED = 0L;

    private final BufferReference ref;

    public static boolean ENABLE_MEMORY_TRACING = false;

    public NativeBuffer(int capacity) {
        this.ref = allocate(capacity);

        ACTIVE_BUFFERS.put(new PhantomReference<>(this, RECLAIM_QUEUE), this.ref);
    }

    public static NativeBuffer copy(ByteBuffer src) {
        NativeBuffer dst = new NativeBuffer(src.remaining());
        LWJGL.memCopy(src, dst.getDirectBuffer());
        return dst;
    }

    public ByteBuffer getDirectBuffer() {
        this.ref.checkFreed();

        return LWJGL.memByteBuffer(this.ref.address, this.ref.length);
    }

    public void free() {
        deallocate(this.ref);
    }

    public int getLength() {
        return this.ref.length;
    }

    public static void reclaim(boolean forceGc) {
        if (forceGc) {
            System.gc();
        }

        Reference<? extends NativeBuffer> ref;

        while ((ref = RECLAIM_QUEUE.poll()) != null) {
            BufferReference buf = ACTIVE_BUFFERS.remove(ref);

            if (buf.freed) {
                continue;
            }

            deallocate(buf);

            if (buf.allocationSite != null) {
                LauncherLog.warn("[RENDERER] Reclaimed " + buf.length + " bytes at address " + buf.address
                        + " that were leaked from allocation site:\n"
                        + Arrays.stream(buf.allocationSite)
                                .map(StackTraceElement::toString)
                                .collect(Collectors.joining("\n")));
            } else {
                LauncherLog.warn("[RENDERER] Reclaimed " + buf.length + " bytes at address " + buf.address
                        + " that were leaked from an unknown location (logging is disabled)");
            }
        }
    }

    public static long getTotalAllocated() {
        return ALLOCATED;
    }

    private static StackTraceElement[] getStackTrace() {
        return ENABLE_MEMORY_TRACING ? Thread.currentThread().getStackTrace() : null;
    }

    private static final int MAX_ALLOCATION_ATTEMPTS = 3;

    private static BufferReference allocate(int bytes) {
        long address = 0;
        int attempts = 0;

        while (++attempts <= MAX_ALLOCATION_ATTEMPTS) {
            address = LWJGL.nmemAlloc(bytes);

            if (address != LWJGLServiceProvider.NULL) {
                break;
            }

            LauncherLog.err("[RENDERER] EMERGENCY: Tried to allocate " + bytes + " bytes but the allocator reports failure");
            LauncherLog.err("[RENDERER] EMERGENCY: ... Attempting to force a garbage collection cycle (attempt " + attempts + "/" + MAX_ALLOCATION_ATTEMPTS + ")");

            // If memory allocation fails, force a garbage collection
            reclaim(true);
        }

        if (address == LWJGLServiceProvider.NULL) {
            throw new OutOfMemoryError("Couldn't allocate %s bytes after %s attempts".formatted(bytes, attempts));
        }

        StackTraceElement[] stackTrace = getStackTrace();

        BufferReference ref = new BufferReference(address, bytes, stackTrace);
        ALLOCATED += ref.length;

        return ref;
    }

    private static void deallocate(BufferReference ref) {
        ref.checkFreed();
        ref.freed = true;

        LWJGL.nmemFree(ref.address);

        ALLOCATED -= ref.length;
    }

    private static class BufferReference {
        public final long address;
        public final int length;

        public final StackTraceElement[] allocationSite;

        public boolean freed;

        private BufferReference(long address, int length, StackTraceElement[] allocationSite) {
            this.address = address;
            this.length = length;
            this.allocationSite = allocationSite;
        }

        private void checkFreed() {
            if (this.freed) {
                throw new IllegalStateException("Buffer has been deleted");
            }
        }
    }
}
