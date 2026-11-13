package com.winlator.xconnector;

import android.util.Log;

import androidx.annotation.Keep;

import java.nio.ByteBuffer;

/**
 * Non-blocking send, from the small libxnonblock.so that the CI workflow builds. When that library
 * is not in the APK, {@link #isAvailable()} is false and callers use the blocking write instead.
 */
@Keep
public final class NonBlockingSocket {
    private static volatile boolean available;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("xnonblock");
            loaded = true;
        }
        catch (Throwable e) {
            Log.w("NonBlockingSocket", "libxnonblock.so not available, using blocking writes: " + e);
        }
        available = loaded;
    }

    private NonBlockingSocket() {}

    public static boolean isAvailable() {
        return available;
    }

    /**
     * Sends up to {@code length} bytes from the start of the direct buffer without waiting.
     * Returns the bytes sent (0 when the socket is full), -1 when the connection is broken, or
     * -2 when the native code turned out to be unusable (the caller must then write blocking).
     */
    public static int trySend(int fd, ByteBuffer data, int length) {
        if (!available) return -2;
        try {
            return send(fd, data, length);
        }
        catch (LinkageError e) {
            available = false;
            Log.w("NonBlockingSocket", "Non-blocking send unusable, using blocking writes: " + e);
            return -2;
        }
    }

    private static native int send(int fd, ByteBuffer data, int length);
}
