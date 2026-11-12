package com.winlator.xconnector;

import com.winlator.xserver.XServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.ReentrantLock;

public class XOutputStream {
    private static final byte[] ZERO = new byte[64];
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock = new ReentrantLock();
    private int ancillaryFd = -1;
    private static final double FP3232_SCALE = 4294967296.0;

    public XOutputStream(int initialCapacity) {
        this(null, initialCapacity);
    }

    public XOutputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public void setByteOrder(ByteOrder byteOrder) {
        buffer.order(byteOrder);
    }

    public void setAncillaryFd(int ancillaryFd) {
        this.ancillaryFd = ancillaryFd;
    }

    public void writeByte(byte value) {
        ensureSpaceIsAvailable(1);
        buffer.put(value);
    }

    public void writeShort(short value) {
        ensureSpaceIsAvailable(2);
        buffer.putShort(value);
    }

    public void writeInt(int value) {
        ensureSpaceIsAvailable(4);
        buffer.putInt(value);
    }

    public void writeLong(long value) {
        ensureSpaceIsAvailable(8);
        buffer.putLong(value);
    }

    public void writeFP3232(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("FP3232 value must be finite");
        }

        long fixed = Math.round(value * FP3232_SCALE);

        int integral = (int) (fixed >> 32);
        int frac = (int) fixed;

        // FP3232 is a struct { int32_t integral; uint32_t frac; } in X11.
        writeInt(integral);
        writeInt(frac);
    }

    public void writeFP3232(int integerPart, long fractionalPart) {
        if (fractionalPart < 0L || fractionalPart > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("fractionalPart must be in range 0 .. 0xFFFFFFFF");
        }

        writeInt(integerPart);
        writeInt((int) fractionalPart);
    }

    public void writeString8(String str) {
        byte[] bytes = str.getBytes(XServer.LATIN1_CHARSET);
        int length = -str.length() & 3;
        ensureSpaceIsAvailable(bytes.length + length);
        buffer.put(bytes);
        if (length > 0) writePad(length);
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) {
        ensureSpaceIsAvailable(length);
        buffer.put(data, offset, length);
    }

    public void write(ByteBuffer data) {
        ensureSpaceIsAvailable(data.remaining());
        buffer.put(data);
    }

    public void writePad(int length) {
        write(ZERO, 0, length);
    }

    /** Streams holding output for clients that are stopped (game suspended). */
    private static final java.util.Set<XOutputStream> deferredStreams =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Client sockets are blocking. A suspended (SIGSTOP) client never reads, so once its socket
     * buffer fills, write() would block forever, on whichever thread is sending, with the X server
     * locks held. Input is injected from the UI thread, so that freezes the whole app, including the
     * Resume button. While the game is suspended the output is kept here instead and written out
     * when the game resumes. Messages that carry a file descriptor are never held back.
     */
    private void flush() throws IOException {
        if (buffer.position() != 0 && ancillaryFd == -1 && clientSocket != null
                && com.winlator.core.ProcessHelper.isPauseActive()) {
            deferredStreams.add(this);
            // Close the window where the game resumed between the check and the add.
            if (com.winlator.core.ProcessHelper.isPauseActive()) return;
            deferredStreams.remove(this);
        }
        flushNow();
    }

    /** Writes out everything held back while the game was suspended. Runs off the calling thread. */
    public static void flushDeferredAsync() {
        if (deferredStreams.isEmpty()) return;
        Thread thread = new Thread(() -> {
            // Two passes: the second catches a stream that deferred while the first was running.
            for (int pass = 0; pass < 2; pass++) {
                for (XOutputStream stream : deferredStreams.toArray(new XOutputStream[0])) {
                    if (com.winlator.core.ProcessHelper.isPauseActive()) return;
                    deferredStreams.remove(stream);
                    stream.lock.lock();
                    try {
                        stream.flushNow();
                    }
                    catch (IOException | RuntimeException ignored) {
                        // The client is gone; its connection is torn down by the reader side.
                    }
                    finally {
                        stream.lock.unlock();
                    }
                }
                try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            }
        }, "XDeferredFlush");
        thread.setDaemon(true);
        thread.start();
    }

    private void flushNow() throws IOException {
        if (buffer.position() != 0) {
            buffer.flip();

            if (ancillaryFd != -1) {
                clientSocket.sendAncillaryMsg(buffer, ancillaryFd);
                ancillaryFd = -1;
            }
            else clientSocket.write(buffer);

            buffer.clear();
        }
    }

    public XStreamLock lock() {
        return new OutputStreamLock();
    }

    private void ensureSpaceIsAvailable(int length) {
        int position = buffer.position();
        if ((buffer.capacity() - position) >= length) return;
        // Grow geometrically: growing by exactly what is missing copies the whole buffer on every write
        // once it is full, which gets slow when output piles up.
        int newCapacity = Math.max(position + length, buffer.capacity() + (buffer.capacity() >> 1));
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(newCapacity).order(buffer.order());
        buffer.rewind();
        newBuffer.put(buffer).position(position);
        buffer = newBuffer;
    }

    public void writeSuccessReply(int sequenceNumber, int replyLength) throws IOException {
        try (XStreamLock lock = lock()) {
            writeByte((byte) 1);
            writeByte((byte) 0);
            writeShort((short) sequenceNumber);
            writeInt(replyLength);
            writePad(24);
        }
    }

    private class OutputStreamLock implements XStreamLock {
        public OutputStreamLock() {
            lock.lock();
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            }
            finally {
                lock.unlock();
            }
        }
    }
}
