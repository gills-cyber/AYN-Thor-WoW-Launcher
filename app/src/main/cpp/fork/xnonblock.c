/*
 * Non-blocking socket send for the X server's output streams.
 *
 * This is its own small library because libwinlator.so is shipped prebuilt
 * and cannot be rebuilt from this repository.  It is compiled by the CI
 * workflow (.github/workflows/build.yml).  When the library is missing the
 * Java side falls back to the blocking write.
 */
#include <errno.h>
#include <jni.h>
#include <sys/socket.h>
#include <sys/types.h>

/*
 * Sends as much as the socket takes right now and never waits for the peer.
 * Returns the number of bytes sent (0 when the socket buffer is full), or -1
 * when the connection is broken.
 */
JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_NonBlockingSocket_send(JNIEnv *env, jclass cls, jint fd,
                                                    jobject data, jint length) {
    char *addr = (*env)->GetDirectBufferAddress(env, data);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    jint total = 0;

    if (addr == NULL || length < 0 || capacity < length) return -1;

    while (total < length) {
        ssize_t sent = send(fd, addr + total, (size_t)(length - total), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (sent > 0) {
            total += (jint)sent;
            continue;
        }
        if (sent < 0 && errno == EINTR) continue;
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
        return -1;
    }
    return total;
}
