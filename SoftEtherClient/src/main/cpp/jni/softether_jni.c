#include "softether_jni.h"
#include "softether_protocol.h"
#include "softether_crypto.h"
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <signal.h>

// SEC-01 (TOFU fingerprint pinning): implemented in crypto/aes_wrapper.c. It is
// declared here rather than in softether_crypto.h because this phase's
// authorized file set is limited to aes_wrapper.c and this file; the signature
// is kept byte-for-byte identical to the definition so the two translation
// units agree.
extern int ssl_get_peer_cert_fingerprint(ssl_context_t* ctx, uint8_t* out_sha256, size_t out_len);

#define TAG "SoftEtherJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    signal(SIGPIPE, SIG_IGN);
    LOGD("JNI_OnLoad: SIGPIPE ignored");
    return JNI_VERSION_1_6;
}

// JNI Implementation for SoftEtherClient

JNIEXPORT jlong JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeCreate(
    JNIEnv *env, jobject thiz) {
    LOGD("nativeCreate called");
    
    softether_connection_t* conn = softether_create();
    if (conn == NULL) {
        LOGE("Failed to create connection");
        return 0;
    }
    
    return (jlong)conn;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDestroy(
    JNIEnv *env, jobject thiz, jlong handle) {
    LOGD("nativeDestroy called");
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    
    // Get state before destroying for logging
    softether_state_t state = softether_get_state(conn);
    LOGD("Destroying connection in state: %s", softether_state_string(state));
    
    softether_destroy(conn);
    LOGD("nativeDestroy completed");
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnect(
    JNIEnv *env, jobject thiz, jlong handle, jstring host, jint port, 
    jstring username, jstring password) {
    LOGD("nativeConnect called");
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return ERR_UNKNOWN;
    }
    
    const char* host_str = (*env)->GetStringUTFChars(env, host, NULL);
    const char* username_str = (*env)->GetStringUTFChars(env, username, NULL);
    const char* password_str = (*env)->GetStringUTFChars(env, password, NULL);
    
    if (host_str == NULL || username_str == NULL || password_str == NULL) {
        LOGE("Failed to get string parameters");
        if (host_str) (*env)->ReleaseStringUTFChars(env, host, host_str);
        if (username_str) (*env)->ReleaseStringUTFChars(env, username, username_str);
        if (password_str) (*env)->ReleaseStringUTFChars(env, password, password_str);
        return ERR_UNKNOWN;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    int result = softether_connect(conn, host_str, port, username_str, password_str);
    
    (*env)->ReleaseStringUTFChars(env, host, host_str);
    (*env)->ReleaseStringUTFChars(env, username, username_str);
    (*env)->ReleaseStringUTFChars(env, password, password_str);
    
    return result;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnectWithHub(
    JNIEnv *env, jobject thiz, jlong handle, jstring host, jint port, 
    jstring username, jstring password, jstring hubName, jboolean useTcp,
    jstring clientProductName, jstring clientVersion, jint clientBuild,
    jstring clientOsName, jstring clientOsVersion, jstring clientOsProductId,
    jstring clientHostName, jstring clientIpAddress, jint clientPort,
    jstring serverHostName, jstring serverIpAddress, jint serverPort) {
    LOGD("nativeConnectWithHub called (useTcp=%d)", (int)useTcp);    
    if (handle == 0) {
        LOGE("Invalid handle");
        return ERR_UNKNOWN;
    }
    
    const char* host_str = (*env)->GetStringUTFChars(env, host, NULL);
    const char* username_str = (*env)->GetStringUTFChars(env, username, NULL);
    const char* password_str = (*env)->GetStringUTFChars(env, password, NULL);
    const char* hub_name_str = (*env)->GetStringUTFChars(env, hubName, NULL);
    const char* client_product_name_str = (*env)->GetStringUTFChars(env, clientProductName, NULL);
    const char* client_version_str = (*env)->GetStringUTFChars(env, clientVersion, NULL);
    const char* client_os_name_str = (*env)->GetStringUTFChars(env, clientOsName, NULL);
    const char* client_os_version_str = (*env)->GetStringUTFChars(env, clientOsVersion, NULL);
    const char* client_os_product_id_str = (*env)->GetStringUTFChars(env, clientOsProductId, NULL);
    const char* client_host_name_str = (*env)->GetStringUTFChars(env, clientHostName, NULL);
    const char* client_ip_address_str = (*env)->GetStringUTFChars(env, clientIpAddress, NULL);
    const char* server_host_name_str = (*env)->GetStringUTFChars(env, serverHostName, NULL);
    const char* server_ip_address_str = (*env)->GetStringUTFChars(env, serverIpAddress, NULL);
    
    if (host_str == NULL || username_str == NULL || password_str == NULL || hub_name_str == NULL ||
        client_product_name_str == NULL || client_version_str == NULL || client_os_name_str == NULL ||
        client_os_version_str == NULL || client_os_product_id_str == NULL || client_host_name_str == NULL ||
        client_ip_address_str == NULL || server_host_name_str == NULL || server_ip_address_str == NULL) {
        LOGE("Failed to get string parameters");
        if (host_str) (*env)->ReleaseStringUTFChars(env, host, host_str);
        if (username_str) (*env)->ReleaseStringUTFChars(env, username, username_str);
        if (password_str) (*env)->ReleaseStringUTFChars(env, password, password_str);
        if (hub_name_str) (*env)->ReleaseStringUTFChars(env, hubName, hub_name_str);
        if (client_product_name_str) (*env)->ReleaseStringUTFChars(env, clientProductName, client_product_name_str);
        if (client_version_str) (*env)->ReleaseStringUTFChars(env, clientVersion, client_version_str);
        if (client_os_name_str) (*env)->ReleaseStringUTFChars(env, clientOsName, client_os_name_str);
        if (client_os_version_str) (*env)->ReleaseStringUTFChars(env, clientOsVersion, client_os_version_str);
        if (client_os_product_id_str) (*env)->ReleaseStringUTFChars(env, clientOsProductId, client_os_product_id_str);
        if (client_host_name_str) (*env)->ReleaseStringUTFChars(env, clientHostName, client_host_name_str);
        if (client_ip_address_str) (*env)->ReleaseStringUTFChars(env, clientIpAddress, client_ip_address_str);
        if (server_host_name_str) (*env)->ReleaseStringUTFChars(env, serverHostName, server_host_name_str);
        if (server_ip_address_str) (*env)->ReleaseStringUTFChars(env, serverIpAddress, server_ip_address_str);
        return ERR_UNKNOWN;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;

    // N-16: connect_mutex is now held by softether_connect_with_hub itself;
    // see softether_protocol.c. The lock used to live here, which left
    // nativeConnect() and softether_reconnect() — the other two paths into
    // the connect code — unserialised against softether_disconnect().
    int result = softether_connect_with_hub(conn, host_str, port, username_str, password_str, hub_name_str, (int)useTcp,
        client_product_name_str, client_version_str, (int)clientBuild,
        client_os_name_str, client_os_version_str, client_os_product_id_str,
        client_host_name_str, client_ip_address_str, (int)clientPort,
        server_host_name_str, server_ip_address_str, (int)serverPort);
    
    (*env)->ReleaseStringUTFChars(env, host, host_str);
    (*env)->ReleaseStringUTFChars(env, username, username_str);
    (*env)->ReleaseStringUTFChars(env, password, password_str);
    (*env)->ReleaseStringUTFChars(env, hubName, hub_name_str);
    (*env)->ReleaseStringUTFChars(env, clientProductName, client_product_name_str);
    (*env)->ReleaseStringUTFChars(env, clientVersion, client_version_str);
    (*env)->ReleaseStringUTFChars(env, clientOsName, client_os_name_str);
    (*env)->ReleaseStringUTFChars(env, clientOsVersion, client_os_version_str);
    (*env)->ReleaseStringUTFChars(env, clientOsProductId, client_os_product_id_str);
    (*env)->ReleaseStringUTFChars(env, clientHostName, client_host_name_str);
    (*env)->ReleaseStringUTFChars(env, clientIpAddress, client_ip_address_str);
    (*env)->ReleaseStringUTFChars(env, serverHostName, server_host_name_str);
    (*env)->ReleaseStringUTFChars(env, serverIpAddress, server_ip_address_str);
    
    return result;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetAuthType(
    JNIEnv *env, jobject thiz, jlong handle, jint authType) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;
    softether_set_auth_type(conn, (int)authType);
    LOGD("Auth type set to %d via JNI", (int)authType);
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDisconnect(
    JNIEnv *env, jobject thiz, jlong handle) {
    LOGD("nativeDisconnect called");
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    
    // Check if connection is in a state that can be disconnected
    softether_state_t state = softether_get_state(conn);
    if (state == STATE_DISCONNECTED || state == STATE_DISCONNECTING) {
        LOGD("Connection already disconnected or disconnecting, state=%s", 
             softether_state_string(state));
        return;
    }
    
    LOGD("Disconnecting connection in state: %s", softether_state_string(state));
    softether_disconnect(conn);
    LOGD("nativeDisconnect completed");
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetState(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) {
        return STATE_DISCONNECTED;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    return (jint)softether_get_state(conn);
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSend(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint length) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }

    // N-01: validate the requested length against the real array size before
    // handing it to the native layer. Without this, a Java caller could pass a
    // length larger than `data` and make softether_send read past the buffer.
    // Mirrors the bounds check in nativeSendSlice.
    if (data == NULL) {
        LOGE("Invalid data array");
        return -1;
    }
    if (length < 0) {
        LOGE("Invalid length: %d", length);
        return -1;
    }
    jsize array_len = (*env)->GetArrayLength(env, data);
    if (length > array_len) {
        LOGE("Send length out of bounds: length=%d array=%d", length, array_len);
        return -1;
    }

    jbyte* data_bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (data_bytes == NULL) {
        LOGE("Failed to get data bytes");
        return -1;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    int result = softether_send(conn, (const uint8_t*)data_bytes, (size_t)length);
    
    (*env)->ReleaseByteArrayElements(env, data, data_bytes, JNI_ABORT);
    
    return result;
}

// Phase 13E: send a slice of the Java buffer without any copy on the
// Kotlin side (TUN read loop passes its scratch buffer + offset directly).
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSendSlice(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint offset, jint length) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }
    if (offset < 0 || length < 0) {
        LOGE("Invalid offset/length");
        return -1;
    }
    if (data == NULL) {
        LOGE("Null data array");
        return -1;
    }

    jsize array_len = (*env)->GetArrayLength(env, data);
    if ((jlong)offset + length > array_len) {
        LOGE("Slice out of bounds: offset=%d len=%d array=%d", offset, length, array_len);
        return -1;
    }

    jbyte* data_bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (data_bytes == NULL) {
        LOGE("Failed to get data bytes");
        return -1;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    int result = softether_send(conn, (const uint8_t*)data_bytes + offset, (size_t)length);

    (*env)->ReleaseByteArrayElements(env, data, data_bytes, JNI_ABORT);

    return result;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeReceive(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer, jint maxLength) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }

    // N-02: validate maxLength against the real array size. Without this, a
    // Java caller could pass a maxLength larger than `buffer` and make
    // softether_receive / the RUDP path memcpy past the end of it.
    if (buffer == NULL) {
        LOGE("Invalid buffer array");
        return -1;
    }
    if (maxLength < 0) {
        LOGE("Invalid maxLength: %d", maxLength);
        return -1;
    }
    jsize buffer_len = (*env)->GetArrayLength(env, buffer);
    if (maxLength > buffer_len) {
        LOGE("Receive maxLength out of bounds: maxLength=%d buffer=%d",
             maxLength, buffer_len);
        return -1;
    }

    jbyte* buffer_bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buffer_bytes == NULL) {
        LOGE("Failed to get buffer bytes");
        return -1;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    int result = softether_receive(conn, (uint8_t*)buffer_bytes, (size_t)maxLength);
    
    if (result > 0) {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, 0);
    } else {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, JNI_ABORT);
    }
    
    return result;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetSocketFd(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) {
        LOGE("Invalid handle for getSocketFd");
        return -1;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;
    return conn->socket_fd;
}

// Phase 13D: receive multiple frames per JNI crossing.
// Returns total bytes written into `buffer` (0 = nothing available), or -1 on
// error. lengths[0..n-1] hold per-frame sizes; entries beyond n are zeroed,
// so callers detect the frame count by scanning for the first zero.
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeReceiveBatch(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer, jint maxLength,
    jintArray lengths, jint maxPackets) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }
    if (buffer == NULL || lengths == NULL || maxPackets <= 0 || maxLength <= 0) {
        LOGE("Invalid arguments for receiveBatch");
        return -1;
    }

    // N-03: the existing check only rejects non-positive values; it never
    // compares them against the real array sizes. The native layer writes up
    // to `maxLength` bytes into `buffer` and up to `maxPackets` entries into
    // `lengths`, so both bounds must hold or we corrupt the Java heap.
    {
        jsize buffer_len = (*env)->GetArrayLength(env, buffer);
        if (maxLength > buffer_len) {
            LOGE("receiveBatch maxLength out of bounds: maxLength=%d buffer=%d",
                 maxLength, buffer_len);
            return -1;
        }
        jsize lengths_len = (*env)->GetArrayLength(env, lengths);
        if (maxPackets > lengths_len) {
            LOGE("receiveBatch maxPackets out of bounds: maxPackets=%d lengths=%d",
                 maxPackets, lengths_len);
            return -1;
        }
    }

    jbyte* buffer_bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buffer_bytes == NULL) {
        LOGE("Failed to get buffer bytes");
        return -1;
    }
    jint* length_elems = (*env)->GetIntArrayElements(env, lengths, NULL);
    if (length_elems == NULL) {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, JNI_ABORT);
        LOGE("Failed to get lengths array");
        return -1;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    uint32_t count = 0;
    int result = softether_receive_batch(conn, (uint8_t*)buffer_bytes, (uint32_t)maxLength,
                                         (uint32_t*)length_elems, (uint32_t)maxPackets,
                                         &count);

    (*env)->ReleaseIntArrayElements(env, lengths, length_elems, 0);
    if (result > 0) {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, 0);
    } else {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, JNI_ABORT);
    }

    return result;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetMaxConnection(
    JNIEnv *env, jobject thiz, jlong handle, jint maxConnections) {
    if (handle == 0) {
        LOGE("Invalid handle for setMaxConnection");
        return;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;
    if (maxConnections < 1) maxConnections = 1;
    if (maxConnections > MAX_SE_CONNECTIONS) maxConnections = MAX_SE_CONNECTIONS;
    conn->max_connection = maxConnections;
    LOGD("Set max_connection to %d", conn->max_connection);
}

// Phase 17: set half/full-duplex mode. halfConnection=1 -> half-duplex (directional
// C2S/S2C split, primary becomes C2S); halfConnection=0 -> full-duplex (all BOTH).
JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetHalfConnection(
    JNIEnv *env, jobject thiz, jlong handle, jboolean halfConnection) {
    if (handle == 0) {
        LOGE("Invalid handle for setHalfConnection");
        return;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;
    conn->half_connection = halfConnection ? 1 : 0;
    LOGD("Set half_connection to %d", conn->half_connection);
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetNumConnections(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return 0;
    softether_connection_t* conn = (softether_connection_t*)handle;
    return (jint)softether_get_num_connections(conn);
}

JNIEXPORT jintArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetAllSocketFds(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;

    int fds[MAX_SE_CONNECTIONS + 1];
    int count = softether_get_active_socket_fds(conn, fds, MAX_SE_CONNECTIONS + 1);

    if (count == 0) return NULL;

    jintArray arr = (*env)->NewIntArray(env, count);
    if (arr == NULL) return NULL;

    jint* jfds = (jint*)malloc(sizeof(jint) * count);
    if (jfds == NULL) return NULL;
    for (int i = 0; i < count; i++) {
        jfds[i] = (jint)fds[i];
    }
    (*env)->SetIntArrayRegion(env, arr, 0, count, jfds);
    free(jfds);

    LOGD("nativeGetAllSocketFds: returned %d FDs", count);
    return arr;
}

// Phase 13G: permanent traffic/health counters.
// Returns long[9]: { txPackets, txBytes, rxPackets, rxBytes, rxSkippedBlocks,
//                    rudpOverflowCount, rudpRxPackets, rudpTickGaps, rudpDataSuspended }
// or NULL when the handle is invalid.
JNIEXPORT jlongArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetStats(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;

    softether_stats_t st;
    softether_get_stats(conn, &st);

    enum {
        IDX_TX_PACKETS, IDX_TX_BYTES, IDX_RX_PACKETS, IDX_RX_BYTES,
        IDX_RX_SKIPPED, IDX_RUDP_OVERFLOW, IDX_RUDP_RX, IDX_RUDP_GAPS,
        IDX_RUDP_SUSPENDED, IDX_COUNT
    };

    jlongArray arr = (*env)->NewLongArray(env, IDX_COUNT);
    if (arr == NULL) return NULL;

    jlong vals[IDX_COUNT];
    vals[IDX_TX_PACKETS] = (jlong)st.tx_packets;
    vals[IDX_TX_BYTES] = (jlong)st.tx_bytes;
    vals[IDX_RX_PACKETS] = (jlong)st.rx_packets;
    vals[IDX_RX_BYTES] = (jlong)st.rx_bytes;
    vals[IDX_RX_SKIPPED] = (jlong)st.rx_skipped_blocks;
    vals[IDX_RUDP_OVERFLOW] = (jlong)st.rudp_overflow_count;
    vals[IDX_RUDP_RX] = (jlong)st.rudp_rx_packets;
    vals[IDX_RUDP_GAPS] = (jlong)st.rudp_tick_gaps;
    vals[IDX_RUDP_SUSPENDED] = (jlong)st.rudp_data_suspended;

    (*env)->SetLongArrayRegion(env, arr, 0, IDX_COUNT, vals);
    return arr;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetRudpSocketFd(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return -1;
    softether_connection_t* conn = (softether_connection_t*)handle;
    if (conn->rudp) {
        return rudp_get_udp_fd(conn->rudp);
    }
    return -1;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetNatTUdpSocketFd(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return -1;
    softether_connection_t* conn = (softether_connection_t*)handle;
    // N-02 / N-12: snapshot the NAT-T transport and its fd under connect_mutex.
    // softether_disconnect() (which nulls / destroys conn->nat_t_transport)
    // runs serialized by connect_mutex, so taking it here guarantees the
    // pointer and the underlying udp_fd cannot be torn down while we read them.
    // We copy the int fd out before releasing the lock, so even a subsequent
    // free of conn after unlock cannot affect the returned value.
    int fd = -1;
    pthread_mutex_lock(&conn->connect_mutex);
    if (conn->using_nat_t && conn->nat_t_transport != NULL) {
        fd = rudp_transport_get_udp_fd(conn->nat_t_transport);
    }
    pthread_mutex_unlock(&conn->connect_mutex);
    return fd;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetOption(
    JNIEnv *env, jobject thiz, jlong handle, jint option, jlong value) {
    LOGD("nativeSetOption called: option=%d, value=%ld", option, (long)value);
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    
    switch (option) {
        case 1: // OPTION_TIMEOUT
            // N-06: range-check the jlong before casting to int. A value
            // outside [100, 120000] ms would either spin (too small) or hang
            // the connect budget far too long (too large).
            if (value < 100 || value > 120000) {
                LOGE("OPTION_TIMEOUT out of range [100,120000]: %ld", (long)value);
                break;
            }
            conn->timeout_ms = (int)value;
            LOGD("Set timeout to %d ms", conn->timeout_ms);
            break;
        case 4: // OPTION_UDP_PORT (seUdpPort) — enables the direct R-UDP stage
            // N-06: a UDP port must be a valid 1..65535 port number.
            if (value < 1 || value > 65535) {
                LOGE("OPTION_UDP_PORT out of range [1,65535]: %ld", (long)value);
                break;
            }
            conn->udp_port = (int)value;
            LOGD("Set UDP port to %d", conn->udp_port);
            break;
        case 5: // OPTION_UDP_ONLY — skip the doomed direct-TCP attempt
            // N-06: this is a boolean flag; only 0 or 1 are meaningful.
            if (value != 0 && value != 1) {
                LOGE("OPTION_UDP_ONLY must be 0 or 1: %ld", (long)value);
                break;
            }
            conn->udp_only = (int)value;
            LOGD("Set UDP-only to %d", conn->udp_only);
            break;
        default:
            LOGE("Unknown option: %d", option);
            break;
    }
}

JNIEXPORT jintArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDoDhcp(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) {
        LOGE("Invalid handle for DHCP");
        return NULL;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    dhcp_result_t result;
    memset(&result, 0, sizeof(result));

    int ret = softether_do_dhcp(conn, &result);

    // After DHCP success, resolve gateway MAC via ARP
    if (ret == 0 && result.success && result.gateway != 0) {
        conn->assigned_ip = result.assigned_ip;
        LOGD("DHCP success, resolving gateway MAC...");
        int arp_ret = softether_resolve_gateway(conn, result.gateway);
        if (arp_ret != 0) {
            LOGW("Gateway ARP resolution failed, falling back to DNS server");
            if (result.dns_server != 0) {
                softether_resolve_gateway(conn, result.dns_server);
            }
        }
    }

    // Return array: [success, assigned_ip, subnet_mask, gateway, dns_server, dns_server2, lease_time]
    jintArray arr = (*env)->NewIntArray(env, 7);
    if (arr == NULL) return NULL;

    jint values[7];
    values[0] = (ret == 0 && result.success) ? 1 : 0;
    values[1] = (jint)result.assigned_ip;
    values[2] = (jint)result.subnet_mask;
    values[3] = (jint)result.gateway;
    values[4] = (jint)result.dns_server;
    values[5] = (jint)result.dns_server2;
    values[6] = (jint)result.lease_time;

    (*env)->SetIntArrayRegion(env, arr, 0, 7, values);
    return arr;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeForceCloseSocket(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    softether_connection_t* conn = (softether_connection_t*)handle;
    softether_force_close_socket(conn);
}

// Set a stable client MAC before connect (local-bridge ARP stability).
JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetClientMac(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray mac) {
    if (handle == 0 || mac == NULL) return;

    // N-05: softether_set_client_mac copies exactly 6 bytes out of the array,
    // so a shorter array would read past its end. Require exactly 6.
    jsize mac_len = (*env)->GetArrayLength(env, mac);
    if (mac_len != 6) {
        LOGE("setClientMac requires a 6-byte array, got %d", mac_len);
        return;
    }

    jbyte* m = (*env)->GetByteArrayElements(env, mac, NULL);
    if (m == NULL) return;
    softether_connection_t* conn = (softether_connection_t*)handle;
    softether_set_client_mac(conn, (const uint8_t*)m);
    (*env)->ReleaseByteArrayElements(env, mac, m, JNI_ABORT);
}

// Benchmark/diagnostics: return this session's virtual MAC (6 bytes) or NULL.
JNIEXPORT jbyteArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetClientMac(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;
    jbyteArray arr = (*env)->NewByteArray(env, 6);
    if (arr == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, 6, (jbyte*)conn->client_mac);
    return arr;
}

// Benchmark/diagnostics: send a raw L2 Ethernet frame (bypasses the
// auto-eth-header wrapping of softether_send). Returns bytes sent or -1.
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSendRaw(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint length) {
    if (handle == 0 || data == NULL || length <= 0) return -1;

    // N-04: `length > 0` alone is not enough — it must also not exceed the
    // real array size, otherwise softether_send_raw reads past `data`.
    {
        jsize array_len = (*env)->GetArrayLength(env, data);
        if (length > array_len) {
            LOGE("SendRaw length out of bounds: length=%d array=%d", length, array_len);
            return -1;
        }
    }

    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) return -1;
    softether_connection_t* conn = (softether_connection_t*)handle;
    int ret = softether_send_raw(conn, (const uint8_t*)buf, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return ret;
}

// SEC-01 (TOFU fingerprint pinning): the SHA-256 fingerprint (32 bytes) of the
// certificate the peer presented on this connection's completed TLS handshake.
//
// Returns a 32-byte jbyteArray, or NULL when the handle is invalid, the
// connection has no live SSL object, or no peer certificate is available. The
// Kotlin side converts the bytes to lowercase hex and performs the TOFU
// comparison; this entry point is read-only and changes no verify policy.
JNIEXPORT jbyteArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetPeerFingerprint(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) {
        LOGE("nativeGetPeerFingerprint: invalid handle");
        return NULL;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    // conn->ssl is a void* holding the ssl_context_t* for the primary transport;
    // it is only non-NULL once the TLS handshake has completed.
    if (conn->ssl == NULL) {
        LOGE("nativeGetPeerFingerprint: no SSL context (handshake not complete)");
        return NULL;
    }

    uint8_t fingerprint[32];
    if (ssl_get_peer_cert_fingerprint((ssl_context_t*)conn->ssl, fingerprint, sizeof(fingerprint)) != 0) {
        return NULL;
    }

    jbyteArray arr = (*env)->NewByteArray(env, 32);
    if (arr == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, 32, (jbyte*)fingerprint);
    return arr;
}
