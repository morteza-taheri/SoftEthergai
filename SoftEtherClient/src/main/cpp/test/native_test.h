#ifndef NATIVE_TEST_H
#define NATIVE_TEST_H

#include <stdint.h>
#include <stdbool.h>
#include <time.h>
#include <fcntl.h>

#ifdef __cplusplus
extern "C" {
#endif

// Test result structure
typedef struct {
    bool success;
    int error_code;
    char message[256];
    long duration_ms;
} native_test_result_t;

// Test configuration
typedef struct {
    const char* host;
    int port;
    const char* username;
    const char* password;
    int timeout_ms;
    int packet_count;
    int packet_size;
    int duration_seconds;
} native_test_config_t;

// Test functions
native_test_result_t test_tcp_connection(const native_test_config_t* config);
native_test_result_t test_tls_handshake(const native_test_config_t* config);
native_test_result_t test_softether_handshake(const native_test_config_t* config);
native_test_result_t test_authentication(const native_test_config_t* config);
native_test_result_t test_session(const native_test_config_t* config);
native_test_result_t test_data_transmission(const native_test_config_t* config);
native_test_result_t test_keepalive(const native_test_config_t* config);
native_test_result_t test_full_lifecycle(const native_test_config_t* config);
native_test_result_t test_dhcp(const native_test_config_t* config);
native_test_result_t test_internet_connectivity(const native_test_config_t* config);
native_test_result_t test_rudp_v2_loopback(void);
native_test_result_t test_rudp_transport_loopback(void);

// F.28AI — Native race stress tests (no production modifications)
native_test_result_t test_a3_nat_t_force_close_vs_destroy(int iterations);
native_test_result_t test_a6_nat_t_stress(int iterations);
native_test_result_t test_b3_dhcp_vs_disconnect(int iterations);

// F.28AL-B — B3s: DHCP success publication vs softether_disconnect (TEST-ONLY).
// case_id: 1=C1 success-before-disconnect, 2=C2 disconnect-before-response,
//          3=C3 simultaneous response/disconnect race (use iterations=100),
//          4=C4 success-publication window (use iterations=10),
//          5=C5 genuine-timeout control (execute ONCE, separately gated),
//          6=C6 invalid/wrong-xid response control (use iterations=1).
native_test_result_t test_b3s_dhcp_success_race(int case_id, int iterations);

// FIX-NATIVE-P0-BOUNDS — JNI array-length validation regression tests
// (N-01..N-05). Each calls the production JNI entry point with a deliberately
// invalid length/array and asserts the call is rejected (-1) instead of
// reading past the Java array. Requires a live JNIEnv, so the real entry
// points are invoked by the bridge wrappers in test_jni_bridge.c; these
// helpers receive the env the bridge built.
#include <jni.h>

// N-01: nativeSend must reject length > array length (and negative length).
native_test_result_t test_nativeSend_rejectsOversizedLength(JNIEnv* env);
// N-02: nativeReceive must reject maxLength > buffer length.
native_test_result_t test_nativeReceive_rejectsOversizedMaxLength(JNIEnv* env);
// N-03: nativeReceiveBatch must reject maxLength > buffer length AND
//       maxPackets > lengths length.
native_test_result_t test_nativeReceiveBatch_rejectsOversizedLengthsArray(JNIEnv* env);
// N-04: nativeSendRaw must reject length > array length.
native_test_result_t test_nativeSendRaw_rejectsOversizedLength(JNIEnv* env);
// N-05: nativeSetClientMac must reject an array whose length != 6.
native_test_result_t test_nativeSetClientMac_rejectsWrongLengthArray(JNIEnv* env);
// N-08: rudp_recv with a too-small buffer must release ctx->lock, proven by a
//       second thread acquiring the same lock within a bounded wait.
native_test_result_t test_rudp_recv_smallBufferReleasesLock(void);
// N-15-A: rudp_destroy() called while another thread is inside rudp_poll()
//         must not free the context out from under it (use-after-free).
native_test_result_t test_rudp_destroy_during_poll(void);
// N-15-B: the lifetime invariants at birth (refcount==1, destroying==0) and a
//         single rudp_destroy() returning cleanly.
native_test_result_t test_rudp_destroy_is_idempotent(void);
// N-15-C: every guarded entry point balances its own lifetime reference across
//         many rounds (no leak, no early free).
native_test_result_t test_rudp_ref_balance(void);
// N-16-A: the public softether_connect_with_hub must acquire conn->connect_mutex
//         itself, so a concurrent call blocks while the lock is held.
native_test_result_t test_softether_connect_with_hub_takes_lock(void);
// N-16-B: softether_reconnect with no stored server info returns cleanly.
native_test_result_t test_reconnect_no_info_returns(void);

// Utility functions
void test_result_init(native_test_result_t* result, bool success, int error_code, 
                      const char* message, long duration_ms);
long get_test_timestamp_ms(void);
const char* test_error_to_string(int error_code);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_TEST_H
