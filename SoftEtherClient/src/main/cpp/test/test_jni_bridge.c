#include "../jni/softether_jni.h"
#include "native_test.h"
#include "softether_protocol.h"
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#define TAG "TestJNIBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Helper function to create NativeTestResult jobject from native_test_result_t
static jobject create_test_result(JNIEnv *env, const native_test_result_t* native_result) {
    jclass result_class = (*env)->FindClass(env, "vn/unlimit/softether/test/model/NativeTestResult");
    if (result_class == NULL) {
        LOGE("Failed to find NativeTestResult class");
        return NULL;
    }
    
    jmethodID constructor = (*env)->GetMethodID(env, result_class, "<init>",
                                                 "(ZILjava/lang/String;J)V");
    if (constructor == NULL) {
        LOGE("Failed to find NativeTestResult constructor");
        return NULL;
    }
    
    jstring message = (*env)->NewStringUTF(env, native_result->message);
    
    jobject result = (*env)->NewObject(env, result_class, constructor,
                                       native_result->success ? JNI_TRUE : JNI_FALSE,
                                       native_result->error_code,
                                       message,
                                       (jlong)native_result->duration_ms);
    
    (*env)->DeleteLocalRef(env, message);
    (*env)->DeleteLocalRef(env, result_class);
    
    return result;
}

// JNI implementations for test functions

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestTcpConnection(
    JNIEnv *env, jobject thiz, jstring host, jint port, jint timeoutMs) {
    
    LOGD("nativeTestTcpConnection called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.timeout_ms = timeoutMs;
    config.packet_count = 0;
    config.packet_size = 0;
    config.duration_seconds = 0;
    
    if (config.host == NULL) {
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_TCP_CONNECT,
                        "Failed to get host string", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_tcp_connection(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestTlsHandshake(
    JNIEnv *env, jobject thiz, jstring host, jint port, jint timeoutMs) {
    
    LOGD("nativeTestTlsHandshake called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.timeout_ms = timeoutMs;
    
    if (config.host == NULL) {
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_TLS_HANDSHAKE,
                        "Failed to get host string", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_tls_handshake(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestSoftEtherHandshake(
    JNIEnv *env, jobject thiz, jstring host, jint port, jint timeoutMs) {
    
    LOGD("nativeTestSoftEtherHandshake called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.timeout_ms = timeoutMs;
    
    if (config.host == NULL) {
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_PROTOCOL_VERSION,
                        "Failed to get host string", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_softether_handshake(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestAuthentication(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint timeoutMs) {
    
    LOGD("nativeTestAuthentication called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.username = (*env)->GetStringUTFChars(env, username, NULL);
    config.password = (*env)->GetStringUTFChars(env, password, NULL);
    config.timeout_ms = timeoutMs;
    
    if (config.host == NULL || config.username == NULL || config.password == NULL) {
        if (config.host) (*env)->ReleaseStringUTFChars(env, host, config.host);
        if (config.username) (*env)->ReleaseStringUTFChars(env, username, config.username);
        if (config.password) (*env)->ReleaseStringUTFChars(env, password, config.password);
        
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_AUTHENTICATION,
                        "Failed to get parameters", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_authentication(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    (*env)->ReleaseStringUTFChars(env, username, config.username);
    (*env)->ReleaseStringUTFChars(env, password, config.password);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestSession(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint timeoutMs) {
    
    LOGD("nativeTestSession called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.username = (*env)->GetStringUTFChars(env, username, NULL);
    config.password = (*env)->GetStringUTFChars(env, password, NULL);
    config.timeout_ms = timeoutMs;
    
    if (config.host == NULL || config.username == NULL || config.password == NULL) {
        if (config.host) (*env)->ReleaseStringUTFChars(env, host, config.host);
        if (config.username) (*env)->ReleaseStringUTFChars(env, username, config.username);
        if (config.password) (*env)->ReleaseStringUTFChars(env, password, config.password);
        
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_SESSION,
                        "Failed to get parameters", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_session(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    (*env)->ReleaseStringUTFChars(env, username, config.username);
    (*env)->ReleaseStringUTFChars(env, password, config.password);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestDataTransmission(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint packetCount,
    jint packetSize, jint timeoutMs) {
    
    LOGD("nativeTestDataTransmission called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.username = (*env)->GetStringUTFChars(env, username, NULL);
    config.password = (*env)->GetStringUTFChars(env, password, NULL);
    config.timeout_ms = timeoutMs;
    config.packet_count = packetCount;
    config.packet_size = packetSize;
    
    if (config.host == NULL || config.username == NULL || config.password == NULL) {
        if (config.host) (*env)->ReleaseStringUTFChars(env, host, config.host);
        if (config.username) (*env)->ReleaseStringUTFChars(env, username, config.username);
        if (config.password) (*env)->ReleaseStringUTFChars(env, password, config.password);
        
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_DATA_TRANSMISSION,
                        "Failed to get parameters", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_data_transmission(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    (*env)->ReleaseStringUTFChars(env, username, config.username);
    (*env)->ReleaseStringUTFChars(env, password, config.password);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestKeepalive(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint durationSeconds, jint timeoutMs) {
    
    LOGD("nativeTestKeepalive called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.username = (*env)->GetStringUTFChars(env, username, NULL);
    config.password = (*env)->GetStringUTFChars(env, password, NULL);
    config.timeout_ms = timeoutMs;
    config.duration_seconds = durationSeconds;
    
    if (config.host == NULL || config.username == NULL || config.password == NULL) {
        if (config.host) (*env)->ReleaseStringUTFChars(env, host, config.host);
        if (config.username) (*env)->ReleaseStringUTFChars(env, username, config.username);
        if (config.password) (*env)->ReleaseStringUTFChars(env, password, config.password);
        
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_DATA_TRANSMISSION,
                        "Failed to get parameters", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_keepalive(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    (*env)->ReleaseStringUTFChars(env, username, config.username);
    (*env)->ReleaseStringUTFChars(env, password, config.password);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestFullLifecycle(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint timeoutMs) {
    
    LOGD("nativeTestFullLifecycle called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.username = (*env)->GetStringUTFChars(env, username, NULL);
    config.password = (*env)->GetStringUTFChars(env, password, NULL);
    config.timeout_ms = timeoutMs;
    
    if (config.host == NULL || config.username == NULL || config.password == NULL) {
        if (config.host) (*env)->ReleaseStringUTFChars(env, host, config.host);
        if (config.username) (*env)->ReleaseStringUTFChars(env, username, config.username);
        if (config.password) (*env)->ReleaseStringUTFChars(env, password, config.password);
        
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_UNKNOWN,
                        "Failed to get parameters", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_full_lifecycle(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    (*env)->ReleaseStringUTFChars(env, username, config.username);
    (*env)->ReleaseStringUTFChars(env, password, config.password);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestDhcp(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint timeoutMs) {
    
    LOGD("nativeTestDhcp called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.username = (*env)->GetStringUTFChars(env, username, NULL);
    config.password = (*env)->GetStringUTFChars(env, password, NULL);
    config.timeout_ms = timeoutMs;
    
    if (config.host == NULL || config.username == NULL || config.password == NULL) {
        if (config.host) (*env)->ReleaseStringUTFChars(env, host, config.host);
        if (config.username) (*env)->ReleaseStringUTFChars(env, username, config.username);
        if (config.password) (*env)->ReleaseStringUTFChars(env, password, config.password);
        
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_SESSION,
                        "Failed to get parameters", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_dhcp(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    (*env)->ReleaseStringUTFChars(env, username, config.username);
    (*env)->ReleaseStringUTFChars(env, password, config.password);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestInternetConnectivity(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint timeoutMs) {
    
    LOGD("nativeTestInternetConnectivity called");
    
    native_test_config_t config = {0};
    config.host = (*env)->GetStringUTFChars(env, host, NULL);
    config.port = port;
    config.username = (*env)->GetStringUTFChars(env, username, NULL);
    config.password = (*env)->GetStringUTFChars(env, password, NULL);
    config.timeout_ms = timeoutMs;
    
    if (config.host == NULL || config.username == NULL || config.password == NULL) {
        if (config.host) (*env)->ReleaseStringUTFChars(env, host, config.host);
        if (config.username) (*env)->ReleaseStringUTFChars(env, username, config.username);
        if (config.password) (*env)->ReleaseStringUTFChars(env, password, config.password);
        
        native_test_result_t error_result = {0};
        test_result_init(&error_result, false, ERR_DATA_TRANSMISSION,
                        "Failed to get parameters", 0);
        return create_test_result(env, &error_result);
    }
    
    native_test_result_t result = test_internet_connectivity(&config);
    
    (*env)->ReleaseStringUTFChars(env, host, config.host);
    (*env)->ReleaseStringUTFChars(env, username, config.username);
    (*env)->ReleaseStringUTFChars(env, password, config.password);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestRudpV2Loopback(
    JNIEnv *env, jobject thiz) {
    
    LOGD("nativeTestRudpV2Loopback called");
    
    native_test_result_t result = test_rudp_v2_loopback();
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestRudpTransportLoopback(
    JNIEnv *env, jobject thiz) {
    
    LOGD("nativeTestRudpTransportLoopback called");
    
    native_test_result_t result = test_rudp_transport_loopback();
    
    return create_test_result(env, &result);
}

// F.28AI — JNI bridge for native race stress tests

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestA3NatTForceCloseVsDestroy(
    JNIEnv *env, jobject thiz, jint iterations) {
    
    LOGD("nativeTestA3NatTForceCloseVsDestroy called with iterations=%d", iterations);
    
    native_test_result_t result = test_a3_nat_t_force_close_vs_destroy((int)iterations);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestA6NatTStress(
    JNIEnv *env, jobject thiz, jint iterations) {
    
    LOGD("nativeTestA6NatTStress called with iterations=%d", iterations);
    
    native_test_result_t result = test_a6_nat_t_stress((int)iterations);
    
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestB3DhcpVsDisconnect(
    JNIEnv *env, jobject thiz, jint iterations) {
    
    LOGD("nativeTestB3DhcpVsDisconnect called with iterations=%d", iterations);
    
    native_test_result_t result = test_b3_dhcp_vs_disconnect((int)iterations);
    
    return create_test_result(env, &result);
}

// F.28AL-B — JNI bridge for the B3s DHCP success/disconnect race harness.
// Case IDs mirror native_test.h B3S_CASE_* (1=C1, 2=C2, 3=C3, 4=C4, 5=C5, 6=C6).
// TEST-ONLY: exercises the raw framed data channel with a test-side responder;
// no production code path is altered.
JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestB3sDhcpSuccessRace(
    JNIEnv *env, jobject thiz, jint caseId, jint iterations) {

    LOGD("nativeTestB3sDhcpSuccessRace called with caseId=%d iterations=%d",
         caseId, iterations);

    native_test_result_t result =
        test_b3s_dhcp_success_race((int)caseId, (int)iterations);

    return create_test_result(env, &result);
}

// FIX-NATIVE-P0-BOUNDS — bridge wrappers for the JNI array-length validation
// tests (N-01..N-05) and the RUDP lock-leak test (N-08). The length tests need
// the live JNIEnv to build the deliberately wrong arrays, so they are driven
// from here; the assertions themselves live in native_test.c.

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestNativeSendRejectsOversizedLength(
    JNIEnv *env, jobject thiz) {
    LOGD("nativeTestNativeSendRejectsOversizedLength called");
    native_test_result_t result = test_nativeSend_rejectsOversizedLength(env);
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestNativeReceiveRejectsOversizedMaxLength(
    JNIEnv *env, jobject thiz) {
    LOGD("nativeTestNativeReceiveRejectsOversizedMaxLength called");
    native_test_result_t result = test_nativeReceive_rejectsOversizedMaxLength(env);
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestNativeReceiveBatchRejectsOversizedLengthsArray(
    JNIEnv *env, jobject thiz) {
    LOGD("nativeTestNativeReceiveBatchRejectsOversizedLengthsArray called");
    native_test_result_t result =
        test_nativeReceiveBatch_rejectsOversizedLengthsArray(env);
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestNativeSendRawRejectsOversizedLength(
    JNIEnv *env, jobject thiz) {
    LOGD("nativeTestNativeSendRawRejectsOversizedLength called");
    native_test_result_t result = test_nativeSendRaw_rejectsOversizedLength(env);
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestNativeSetClientMacRejectsWrongLengthArray(
    JNIEnv *env, jobject thiz) {
    LOGD("nativeTestNativeSetClientMacRejectsWrongLengthArray called");
    native_test_result_t result =
        test_nativeSetClientMac_rejectsWrongLengthArray(env);
    return create_test_result(env, &result);
}

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestRudpRecvSmallBufferReleasesLock(
    JNIEnv *env, jobject thiz) {
    LOGD("nativeTestRudpRecvSmallBufferReleasesLock called");
    native_test_result_t result = test_rudp_recv_smallBufferReleasesLock();
    return create_test_result(env, &result);
}
