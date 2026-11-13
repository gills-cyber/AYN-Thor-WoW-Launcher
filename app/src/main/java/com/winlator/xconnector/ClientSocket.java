package com.winlator.xconnector;

import androidx.annotation.Keep;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public class ClientSocket {
    public final int fd;
    private final ArrayDeque<Integer> ancillaryFds = new ArrayDeque<>();

    static {
        System.loadLibrary("winlator");
    }

    public ClientSocket(int fd) {
        this.fd = fd;
    }

    public boolean hasAncillaryFds() {
        return !ancillaryFds.isEmpty();
    }

    public int getAncillaryFd() {
        return hasAncillaryFds() ? ancillaryFds.poll() : -1;
    }

    @Keep
    public void addAncillaryFd(int ancillaryFd) {
        ancillaryFds.add(ancillaryFd);
    }

    public int read(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = read(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to read data.");
    }

    public void write(ByteBuffer data) throws IOException {
        int bytesWritten = write(fd, data, data.limit());
        if (bytesWritten >= 0) {
            data.position(bytesWritten);
        }
        else throw new IOException("Failed to write data.");
    }

    /**
     * Writes as much of the first [length] bytes of [data] as the socket takes right now, without
     * ever waiting for the client. Returns how many bytes went out (possibly 0). Falls back to a
     * blocking write of everything when the non-blocking library is not in the APK.
     */
    public int writeNonBlocking(ByteBuffer data, int length) throws IOException {
        int bytesWritten = NonBlockingSocket.trySend(fd, data, length);
        if (bytesWritten == -2) {
            // No non-blocking send in this build: write the way the original app did (waits for the client).
            write(data);
            return length;
        }
        if (bytesWritten < 0) throw new IOException("Failed to write data.");
        return bytesWritten;
    }

    public int recvAncillaryMsg(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = recvAncillaryMsg(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to receive ancillary messages.");
    }

    public void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException {
        int bytesSent = sendAncillaryMsg(fd, data, data.limit(), ancillaryFd);
        if (bytesSent >= 0) {
            data.position(bytesSent);
        }
        else throw new IOException("Failed to send ancillary messages.");
    }

    private native int read(int fd, ByteBuffer data, int offset, int length);

    private native int write(int fd, ByteBuffer data, int length);

    private native int recvAncillaryMsg(int clientFd, ByteBuffer data, int offset, int length);

    private native int sendAncillaryMsg(int clientFd, ByteBuffer data, int length, int ancillaryFd);
}
