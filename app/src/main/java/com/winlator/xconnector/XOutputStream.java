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

    /** Streams with output that could not be written yet: the client is suspended or not reading. */
    private static final java.util.Set<XOutputStream> deferredStreams =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicBoolean flusherRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** A stream is holding this much unsent output or more: pointer motion is dropped meanwhile. */
    private static final int HIGH_BACKLOG_BYTES = 64 * 1024;
    /** A stream holding this much unsent output belongs to a client that is gone for good. */
    private static final int MAX_BACKLOG_BYTES = 32 * 1024 * 1024;
    private static volatile boolean backlogHigh = false;

    /** Set once the client's socket is closed (or about to be): nothing may be written after that. */
    private volatile boolean closed = false;

    /** True while some client is far behind on reading; callers drop droppable input (pointer motion). */
    public static boolean isBacklogHigh() {
        return backlogHigh;
    }

    /**
     * A client that is suspended (SIGSTOP) or busy never reads its socket. A blocking write to it
     * would hang forever once the socket buffer fills, on whichever thread is sending and with the
     * X server locks held. Input is injected from the UI thread, so that froze the whole app,
     * including the Resume button. So output is never written with a blocking call: what does not
     * fit stays in this buffer, in order, and a background thread sends it when the client reads
     * again. While the game is suspended nothing is sent at all.
     */
    private void flush() throws IOException {
        if (buffer.position() != 0 && ancillaryFd == -1 && clientSocket != null
                && com.winlator.core.ProcessHelper.isPauseActive()) {
            deferredStreams.add(this);
            // Close the window where the game resumed between the check and the add.
            if (com.winlator.core.ProcessHelper.isPauseActive()) {
                ensureFlusher();
                return;
            }
        }
        flushNow();
    }

    /**
     * Called when the client's connection is torn down, before its socket is closed. Held-back output
     * must never be written afterwards: the descriptor number may already belong to another file.
     */
    public void discardDeferred() {
        closed = true;
        deferredStreams.remove(this);
    }

    /** Drops everything held back for every client. For teardown, where there is nobody left to read it. */
    public static void discardAllDeferred() {
        for (XOutputStream stream : deferredStreams.toArray(new XOutputStream[0])) stream.discardDeferred();
        backlogHigh = false;
    }

    /** Makes sure held-back output gets sent (after a resume, for example). Never blocks the caller. */
    public static void flushDeferredAsync() {
        if (!deferredStreams.isEmpty()) ensureFlusher();
    }

    private static void ensureFlusher() {
        if (!flusherRunning.compareAndSet(false, true)) return;
        Thread thread = new Thread(XOutputStream::runFlusher, "XDeferredFlush");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runFlusher() {
        try {
            int idlePasses = 0;
            while (idlePasses < 100) {
                boolean paused = com.winlator.core.ProcessHelper.isPauseActive();
                if (deferredStreams.isEmpty()) {
                    backlogHigh = false;
                    idlePasses++;
                }
                else {
                    idlePasses = 0;
                    if (!paused) {
                        boolean high = false;
                        for (XOutputStream stream : deferredStreams.toArray(new XOutputStream[0])) {
                            stream.lock.lock();
                            try {
                                // flushNow puts the stream back if the client still is not reading.
                                deferredStreams.remove(stream);
                                stream.flushNow();
                                if (stream.buffer.position() >= HIGH_BACKLOG_BYTES) high = true;
                            }
                            catch (IOException | RuntimeException | LinkageError e) {
                                // The client is gone; the reader side tears its connection down.
                                deferredStreams.remove(stream);
                            }
                            finally {
                                stream.lock.unlock();
                            }
                        }
                        backlogHigh = high;
                    }
                }
                Thread.sleep(paused ? 50 : 4);
            }
        }
        catch (InterruptedException ignored) {
        }
        finally {
            flusherRunning.set(false);
            // Something may have been deferred between the last look and clearing the flag.
            if (!deferredStreams.isEmpty()) ensureFlusher();
        }
    }

    /** Must be called with [lock] held. */
    private void flushNow() throws IOException {
        if (closed) {
            buffer.clear();
            ancillaryFd = -1;
            return;
        }
        if (buffer.position() == 0) return;

        buffer.flip();
        if (ancillaryFd != -1) {
            // A message that carries a file descriptor has to go out in one piece with it.
            try {
                clientSocket.sendAncillaryMsg(buffer, ancillaryFd);
            }
            finally {
                ancillaryFd = -1;
                buffer.clear();
            }
            return;
        }

        int length = buffer.limit();
        int written;
        try {
            written = clientSocket.writeNonBlocking(buffer, length);
        }
        catch (IOException e) {
            buffer.clear();
            throw e;
        }
        if (written >= length) {
            buffer.clear();
            return;
        }
        // The client's socket is full. Keep the unsent tail at the front; later output is appended
        // behind it, so the byte order the client sees never changes.
        buffer.position(written);
        buffer.compact();
        if (buffer.position() >= MAX_BACKLOG_BYTES) {
            // The client has not read for so long that holding more would exhaust memory. Give up on
            // it: the IOException makes the connection handler drop this client.
            closed = true;
            deferredStreams.remove(this);
            buffer.clear();
            throw new IOException("Client stopped reading; dropped after " + MAX_BACKLOG_BYTES + " bytes of backlog.");
        }
        if (buffer.position() >= HIGH_BACKLOG_BYTES) backlogHigh = true;
        deferredStreams.add(this);
        ensureFlusher();
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
