package vn.unlimit.softether.test

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import vn.unlimit.softether.model.ServerInfo
import vn.unlimit.softether.test.model.NativeTestResult

/**
 * Instrumented tests for native SoftEther implementation.
 * Tests run against real VPNGate servers using the actual softether_connect API.
 *
 * Test order follows the protocol stack:
 * 1. TCP Connection
 * 2. TLS Handshake
 * 3. SoftEther Handshake (watermark + Hello PACK)
 * 4. Authentication (PACK login)
 * 5. Session Establishment
 * 6. Data Transmission
 * 7. Keepalive
 * 8. Full Lifecycle
 * 9. Multiple Servers
 * 10. DHCP over Tunnel (IP assignment from SecureNAT)
 * 11. Internet Connectivity (DNS query for google.com through tunnel)
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NativeConnectionTest {

    companion object {
        private const val TAG = "NativeConnectionTest"

        @JvmStatic
        private var sharedServer: ServerInfo? = null

        init {
            try {
                System.loadLibrary("softether")
                System.loadLibrary("softether_test")
                Log.d(TAG, "Native libraries loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native libraries", e)
                throw e
            }
        }

        @JvmStatic
        @org.junit.BeforeClass
        fun findServer() {
            Log.d(TAG, "BeforeClass: finding VPNGate server...")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val provider = VpngateServerProvider(context)

            runBlocking {
                // Try VPNGate API first - prefer port 443 servers
                val servers = provider.getSoftEtherServersWithAuth()
                Log.d(TAG, "BeforeClass: VPNGate API returned ${servers.size} SoftEther servers")

                if (servers.isNotEmpty()) {
                    // Sort: prefer port 443 servers first (standard HTTPS port)
                    val sorted = servers.sortedBy { if (it.port == 443) 0 else 1 }
                    sharedServer = ServerAvailabilityChecker.getFirstAvailableServer(sorted, checkTls = true)
                }

                // Fallback: try all servers with port 443
                if (sharedServer == null) {
                    Log.d(TAG, "BeforeClass: no SoftEther server passed TLS, trying fallback...")
                    val fallbackServers = provider.getServers()
                    Log.d(TAG, "BeforeClass: total servers from API: ${fallbackServers.size}")

                    val candidates = if (fallbackServers.isNotEmpty()) {
                        fallbackServers.take(20).map { server ->
                            if (server.port <= 0) server.copy(port = 443) else server
                        }
                    } else {
                        emptyList()
                    }

                    if (candidates.isNotEmpty()) {
                        sharedServer = ServerAvailabilityChecker.getFirstAvailableServer(candidates, checkTls = true)
                    }
                }
            }

            if (sharedServer != null) {
                Log.d(TAG, "BeforeClass: selected server ${sharedServer!!.ip}:${sharedServer!!.port}")
            } else {
                Log.w(TAG, "BeforeClass: no available server found — all tests will be SKIPPED")
            }
        }
    }

    private val testServer: ServerInfo? get() = sharedServer

    // Native method declarations
    private external fun nativeTestTcpConnection(host: String, port: Int, timeoutMs: Int): NativeTestResult
    private external fun nativeTestTlsHandshake(host: String, port: Int, timeoutMs: Int): NativeTestResult
    private external fun nativeTestSoftEtherHandshake(host: String, port: Int, timeoutMs: Int): NativeTestResult
    private external fun nativeTestAuthentication(host: String, port: Int, username: String, password: String, timeoutMs: Int): NativeTestResult
    private external fun nativeTestSession(host: String, port: Int, username: String, password: String, timeoutMs: Int): NativeTestResult
    private external fun nativeTestDataTransmission(host: String, port: Int, username: String, password: String, packetCount: Int, packetSize: Int, timeoutMs: Int): NativeTestResult
    private external fun nativeTestKeepalive(host: String, port: Int, username: String, password: String, durationSeconds: Int, timeoutMs: Int): NativeTestResult
    private external fun nativeTestFullLifecycle(host: String, port: Int, username: String, password: String, timeoutMs: Int): NativeTestResult
    private external fun nativeTestDhcp(host: String, port: Int, username: String, password: String, timeoutMs: Int): NativeTestResult
    private external fun nativeTestInternetConnectivity(host: String, port: Int, username: String, password: String, timeoutMs: Int): NativeTestResult
    private external fun nativeTestRudpV2Loopback(): NativeTestResult
    private external fun nativeTestRudpTransportLoopback(): NativeTestResult
    // F.28AI — Native race stress tests
    private external fun nativeTestA3NatTForceCloseVsDestroy(iterations: Int): NativeTestResult
    private external fun nativeTestA6NatTStress(iterations: Int): NativeTestResult
    private external fun nativeTestB3DhcpVsDisconnect(iterations: Int): NativeTestResult
    // F.28AL-B — B3s DHCP success/disconnect race harness (TEST-ONLY).
    // caseId: 1=C1, 2=C2, 3=C3, 4=C4, 5=C5 (execute ONCE, separately gated), 6=C6.
    private external fun nativeTestB3sDhcpSuccessRace(caseId: Int, iterations: Int): NativeTestResult

    // FIX-NATIVE-P0-BOUNDS — JNI array-length validation and RUDP lock tests.
    // These bridge to the C helpers in test/native_test.c (declarations in
    // native_test.h:68-81, wrappers in test_jni_bridge.c:426-468). They are
    // declared here as INSTANCE methods (`external fun`, not in the companion
    // object) because the C exports all take `jobject thiz`, not `jclass` —
    // i.e. JNI resolves them against an instance, so a companion-object
    // declaration would not link.
    //
    // All six are hermetic: none needs a VPNGate server and none needs a live
    // connection. The five bounds tests build deliberately-wrong arrays against
    // a dummy handle; the RUDP test builds its own context internally. See
    // native_test.c:3387-3397 for the C-side rationale.
    private external fun nativeTestNativeSendRejectsOversizedLength(): NativeTestResult
    private external fun nativeTestNativeReceiveRejectsOversizedMaxLength(): NativeTestResult
    private external fun nativeTestNativeReceiveBatchRejectsOversizedLengthsArray(): NativeTestResult
    private external fun nativeTestNativeSendRawRejectsOversizedLength(): NativeTestResult
    private external fun nativeTestNativeSetClientMacRejectsWrongLengthArray(): NativeTestResult
    private external fun nativeTestRudpRecvSmallBufferReleasesLock(): NativeTestResult

    @After
    fun tearDown() {
        // Cleanup if needed
    }

    @Test
    fun test01TcpConnection() {
        Log.d(TAG, "Running testTcpConnection")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing TCP connection to ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestTcpConnection(testServer!!.ip, testServer!!.port, TestConfig.DEFAULT_TIMEOUT_MS)
        assertTrue(
            "TCP connection failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ TCP connection test passed: ${result.connectTimeMs}ms")
    }

    @Test
    fun test02TlsHandshake() {
        Log.d(TAG, "Running testTlsHandshake")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing TLS handshake to ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestTlsHandshake(testServer!!.ip, testServer!!.port, TestConfig.DEFAULT_TIMEOUT_MS)
        assertTrue(
            "TLS handshake failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ TLS handshake test passed: ${result.connectTimeMs}ms")
    }

    @Test
    fun test03SoftEtherHandshake() {
        Log.d(TAG, "Running testSoftEtherHandshake")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing SoftEther handshake to ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestSoftEtherHandshake(
            testServer!!.ip, testServer!!.port, TestConfig.DEFAULT_TIMEOUT_MS
        )
        assertTrue(
            "SoftEther handshake failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ SoftEther handshake test passed")
    }

    @Test
    fun test04Authentication() {
        Log.d(TAG, "Running testAuthentication")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing authentication to ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestAuthentication(
            testServer!!.ip, testServer!!.port,
            TestConfig.DEFAULT_USERNAME, TestConfig.DEFAULT_PASSWORD,
            TestConfig.AUTH_TIMEOUT_MS
        )
        assertTrue(
            "Authentication failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ Authentication test passed")
    }

    @Test
    fun test05SessionEstablishment() {
        Log.d(TAG, "Running testSessionEstablishment")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing session establishment to ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestSession(
            testServer!!.ip, testServer!!.port,
            TestConfig.DEFAULT_USERNAME, TestConfig.DEFAULT_PASSWORD,
            TestConfig.SESSION_TIMEOUT_MS
        )
        assertTrue(
            "Session establishment failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ Session establishment test passed")
    }

    @Test
    fun test06DataTransmission() {
        Log.d(TAG, "Running testDataTransmission")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing data transmission to ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestDataTransmission(
            testServer!!.ip, testServer!!.port,
            TestConfig.DEFAULT_USERNAME, TestConfig.DEFAULT_PASSWORD,
            TestConfig.DEFAULT_PACKET_COUNT, TestConfig.DEFAULT_PACKET_SIZE,
            TestConfig.DATA_TIMEOUT_MS
        )
        assertTrue(
            "Data transmission failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ Data transmission test passed: ${result.bytesSent} bytes sent, ${result.bytesReceived} bytes received")
    }

    @Test
    fun test07Keepalive() {
        Log.d(TAG, "Running testKeepalive")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing keepalive to ${testServer!!.ip}:${testServer!!.port} for ${TestConfig.KEEPALIVE_DURATION_SECONDS}s")

        val result = nativeTestKeepalive(
            testServer!!.ip, testServer!!.port,
            TestConfig.DEFAULT_USERNAME, TestConfig.DEFAULT_PASSWORD,
            TestConfig.KEEPALIVE_DURATION_SECONDS, TestConfig.SESSION_TIMEOUT_MS
        )
        assertTrue(
            "Keepalive test failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ Keepalive test passed: connection stable for ${TestConfig.KEEPALIVE_DURATION_SECONDS} seconds")
    }

    @Test
    fun test08FullConnectionLifecycle() {
        Log.d(TAG, "Running testFullConnectionLifecycle")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing full connection lifecycle to ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestFullLifecycle(
            testServer!!.ip, testServer!!.port,
            TestConfig.DEFAULT_USERNAME, TestConfig.DEFAULT_PASSWORD,
            TestConfig.LIFECYCLE_TIMEOUT_MS
        )
        assertTrue(
            "Full lifecycle test failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ Full lifecycle test passed: ${result.connectTimeMs}ms total")
    }

    @Test
    fun test09MultipleServers() {
        Log.d(TAG, "Running testMultipleServers")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing with server: ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestFullLifecycle(
            testServer!!.ip, testServer!!.port,
            TestConfig.DEFAULT_USERNAME, TestConfig.DEFAULT_PASSWORD,
            TestConfig.LIFECYCLE_TIMEOUT_MS
        )
        assertTrue(
            "Server test failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ Multiple servers test passed: ${result.connectTimeMs}ms")
    }

    /**
     * Test 10: DHCP over SoftEther Tunnel
     * Verifies IP address assignment from SecureNAT via DHCP
     */
    @Test
    fun test10Dhcp() {
        Log.d(TAG, "Running testDhcp")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing DHCP to ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestDhcp(
            testServer!!.ip, testServer!!.port,
            TestConfig.DEFAULT_USERNAME, TestConfig.DEFAULT_PASSWORD,
            TestConfig.LIFECYCLE_TIMEOUT_MS
        )
        assertTrue(
            "DHCP failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ DHCP test passed: ${result.errorMessage}")
    }

    /**
     * Test 11: Internet Connectivity via DNS
     * Connects to VPN, does DHCP, sends DNS query for google.com through tunnel.
     * If DNS response is received, the VPN tunnel provides real internet access.
     */
    @Test
    fun test11InternetConnectivity() {
        Log.d(TAG, "Running testInternetConnectivity")
        assumeNotNull("No VPNGate server available", testServer)
        Log.d(TAG, "Testing internet connectivity via ${testServer!!.ip}:${testServer!!.port}")

        val result = nativeTestInternetConnectivity(
            testServer!!.ip, testServer!!.port,
            TestConfig.DEFAULT_USERNAME, TestConfig.DEFAULT_PASSWORD,
            TestConfig.LIFECYCLE_TIMEOUT_MS
        )
        assertTrue(
            "Internet connectivity failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ Internet connectivity test passed: ${result.errorMessage}")
    }

    /**
     * Test 12: RUDP V2 (ChaCha20-Poly1305 AEAD) loopback
     * Self-contained - runs a client+server rudp_context_t pair over 127.0.0.1,
     * no VPN server required.
     */
    @Test
    fun test12RudpV2Loopback() {
        Log.d(TAG, "Running testRudpV2Loopback")

        val result = nativeTestRudpV2Loopback()
        assertTrue(
            "RUDP V2 loopback failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "✓ RUDP V2 loopback test passed: ${result.errorMessage}")
    }

    /**
     * Test 13: RUDP transport (NAT-T) loopback
     * Self-contained - runs a client rudp_transport_t against a minimal
     * transport-server over 127.0.0.1: CONNECT -> ESTABLISHED, bidirectional
     * bytes, corrupt packet dropped/recovered, and exposes the UDP fd that the
     * app must VpnService.protect().
     */
@Test
    fun test13RudpTransportLoopback() {
        Log.d(TAG, "Running testRudpTransportLoopback")

        val result = nativeTestRudpTransportLoopback()
        assertTrue(
            "RUDP transport loopback failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "RUDP transport loopback test passed: ${result.errorMessage}")
    }

    // F.28AI — Native race stress tests
    
@Test
    fun test14A3NatTForceCloseVsDestroy() {
        Log.d(TAG, "Running testA3NatTForceCloseVsDestroy")

        val result = nativeTestA3NatTForceCloseVsDestroy(10)
        assertTrue(
            "A3 NAT-T force-close vs destroy failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "A3 NAT-T force-close vs destroy passed: ${result.errorMessage}")
    }

    @Test
    fun test15A6NatTStress() {
        Log.d(TAG, "Running testA6NatTStress")

        val result = nativeTestA6NatTStress(10000)
        assertTrue(
            "A6 NAT-T stress failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "A6 NAT-T stress passed: ${result.errorMessage}")
    }

    @Test
    fun test16B3DhcpVsDisconnect() {
        Log.d(TAG, "Running testB3DhcpVsDisconnect")

        val result = nativeTestB3DhcpVsDisconnect(10)
        assertTrue(
            "B3 DHCP vs disconnect failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3 DHCP vs disconnect passed: ${result.errorMessage}")
    }

    // F.28AI-RUN3-COMPLETION — Progressive stage matrix (parameterized iteration counts)
    // A3 stages: 10 (test14), 100, 1_000, 10_000, 100_000
    @Test
    fun test17A3Stage100() {
        Log.d(TAG, "Running testA3Stage100")
        val result = nativeTestA3NatTForceCloseVsDestroy(100)
        assertTrue(
            "A3 stage 100 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "A3 stage 100 passed: ${result.errorMessage}")
    }

    @Test
    fun test18A3Stage1000() {
        Log.d(TAG, "Running testA3Stage1000")
        val result = nativeTestA3NatTForceCloseVsDestroy(1000)
        assertTrue(
            "A3 stage 1000 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "A3 stage 1000 passed: ${result.errorMessage}")
    }

    @Test
    fun test19A3Stage10000() {
        Log.d(TAG, "Running testA3Stage10000")
        val result = nativeTestA3NatTForceCloseVsDestroy(10000)
        assertTrue(
            "A3 stage 10000 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "A3 stage 10000 passed: ${result.errorMessage}")
    }

    @Test
    fun test20A3Stage100000() {
        Log.d(TAG, "Running testA3Stage100000")
        val result = nativeTestA3NatTForceCloseVsDestroy(100000)
        assertTrue(
            "A3 stage 100000 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "A3 stage 100000 passed: ${result.errorMessage}")
    }

    // A6 stages: 10_000 (test15), 100_000
    @Test
    fun test21A6Stage100000() {
        Log.d(TAG, "Running testA6Stage100000")
        val result = nativeTestA6NatTStress(100000)
        assertTrue(
            "A6 stage 100000 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "A6 stage 100000 passed: ${result.errorMessage}")
    }

    // B3 stages: 10 (test16), 100, 1_000, 10_000
    @Test
    fun test22B3Stage100() {
        Log.d(TAG, "Running testB3Stage100")
        val result = nativeTestB3DhcpVsDisconnect(100)
        assertTrue(
            "B3 stage 100 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3 stage 100 passed: ${result.errorMessage}")
    }

    @Test
    fun test23B3Stage1000() {
        Log.d(TAG, "Running testB3Stage1000")
        val result = nativeTestB3DhcpVsDisconnect(1000)
        assertTrue(
            "B3 stage 1000 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3 stage 1000 passed: ${result.errorMessage}")
    }

    @Test
    fun test24B3Stage10000() {
        Log.d(TAG, "Running testB3Stage10000")
        val result = nativeTestB3DhcpVsDisconnect(10000)
        assertTrue(
            "B3 stage 10000 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3 stage 10000 passed: ${result.errorMessage}")
    }

    // F.28AL-B — B3s deterministic DHCP success/disconnect race matrix (TEST-ONLY).
    // Raw framed socketpair + test-side responder; real production DHCP parser.
    // C5 is NOT executed here: it requires separate build/install/execution approval
    // and runs exactly ONCE when authorized. No production code is touched.
    // Does NOT prove UDP/RUDP/NAT-T, TLS, VpnService, or TUN behavior.

    @Test
    fun test25B3sCaseC1() {
        Log.d(TAG, "Running testB3sCaseC1")
        val result = nativeTestB3sDhcpSuccessRace(1, 1)
        assertTrue(
            "B3s C1 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3s C1 passed: ${result.errorMessage}")
    }

    @Test
    fun test26B3sCaseC2() {
        Log.d(TAG, "Running testB3sCaseC2")
        val result = nativeTestB3sDhcpSuccessRace(2, 1)
        assertTrue(
            "B3s C2 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3s C2 passed: ${result.errorMessage}")
    }

    @Test
    fun test27B3sCaseC3() {
        Log.d(TAG, "Running testB3sCaseC3")
        val result = nativeTestB3sDhcpSuccessRace(3, 100)
        assertTrue(
            "B3s C3 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3s C3 passed: ${result.errorMessage}")
    }

    @Test
    fun test28B3sCaseC4() {
        Log.d(TAG, "Running testB3sCaseC4")
        val result = nativeTestB3sDhcpSuccessRace(4, 10)
        assertTrue(
            "B3s C4 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3s C4 passed: ${result.errorMessage}")
    }

    @Test
    fun test29B3sCaseC6() {
        Log.d(TAG, "Running testB3sCaseC6")
        val result = nativeTestB3sDhcpSuccessRace(6, 1)
        assertTrue(
            "B3s C6 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3s C6 passed: ${result.errorMessage}")
    }

    // F.28AL-E — expose the existing native C5 (genuine no-response timeout)
    // as a discoverable instrumentation test. Silent responder: no OFFER/ACK is
    // provided and teardown is deferred until after DHCP returns, so the run
    // traverses the real 3x5s production timeout. Single iteration, no retries.
    // NOT executed in this phase — physical execution requires separate approval.
    @Test
    fun test30B3sCaseC5() {
        Log.d(TAG, "Running testB3sCaseC5")
        val result = nativeTestB3sDhcpSuccessRace(5, 1)
        assertTrue(
            "B3s C5 failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "B3s C5 passed: ${result.errorMessage}")
    }

    // ------------------------------------------------------------------------
    // FIX-NATIVE-P0-BOUNDS — JNI array-length validation regression tests.
    //
    // These are the instrumentation entry points for findings N-01..N-05 and
    // N-08. Each is HERMETIC: no VPNGate server, no live connection, no
    // network. The C helpers (native_test.c:3384-3675) build the deliberately
    // invalid arrays and call the production JNI entry points directly, so a
    // pass means the new bounds guard fired and no out-of-bounds read/write
    // occurred. Because they need neither `testServer` nor shared state, they
    // deliberately do NOT call `assumeNotNull(...)` — unlike the connection
    // tests above, these must RUN even when no server is reachable.
    //
    // The verdict is computed C-side (`result.success`); Kotlin asserts on it
    // exactly as tests 12/13 do.
    // ------------------------------------------------------------------------

    /**
     * Test 31: N-01 — `nativeSend` rejects a length larger than the array
     * (and a negative length) instead of reading past the Java byte array.
     */
    @Test
    fun test31NativeSendRejectsOversizedLength() {
        Log.d(TAG, "Running testNativeSendRejectsOversizedLength")

        val result = nativeTestNativeSendRejectsOversizedLength()
        assertTrue(
            "N-01 nativeSend bounds check failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "N-01 nativeSend passed: ${result.errorMessage}")
    }

    /**
     * Test 32: N-02 — `nativeReceive` rejects a maxLength larger than the
     * buffer instead of writing past the Java array.
     */
    @Test
    fun test32NativeReceiveRejectsOversizedMaxLength() {
        Log.d(TAG, "Running testNativeReceiveRejectsOversizedMaxLength")

        val result = nativeTestNativeReceiveRejectsOversizedMaxLength()
        assertTrue(
            "N-02 nativeReceive bounds check failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "N-02 nativeReceive passed: ${result.errorMessage}")
    }

    /**
     * Test 33: N-03 — `nativeReceiveBatch` rejects maxLength > buffer array
     * AND maxPackets > lengths array.
     */
    @Test
    fun test33NativeReceiveBatchRejectsOversizedLengthsArray() {
        Log.d(TAG, "Running testNativeReceiveBatchRejectsOversizedLengthsArray")

        val result = nativeTestNativeReceiveBatchRejectsOversizedLengthsArray()
        assertTrue(
            "N-03 nativeReceiveBatch bounds check failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "N-03 nativeReceiveBatch passed: ${result.errorMessage}")
    }

    /**
     * Test 34: N-04 — `nativeSendRaw` rejects a length larger than the array.
     */
    @Test
    fun test34NativeSendRawRejectsOversizedLength() {
        Log.d(TAG, "Running testNativeSendRawRejectsOversizedLength")

        val result = nativeTestNativeSendRawRejectsOversizedLength()
        assertTrue(
            "N-04 nativeSendRaw bounds check failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "N-04 nativeSendRaw passed: ${result.errorMessage}")
    }

    /**
     * Test 35: N-05 — `nativeSetClientMac` rejects an array whose length != 6
     * (and still accepts a correct 6-byte array).
     */
    @Test
    fun test35NativeSetClientMacRejectsWrongLengthArray() {
        Log.d(TAG, "Running testNativeSetClientMacRejectsWrongLengthArray")

        val result = nativeTestNativeSetClientMacRejectsWrongLengthArray()
        assertTrue(
            "N-05 nativeSetClientMac length check failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "N-05 nativeSetClientMac passed: ${result.errorMessage}")
    }

    /**
     * Test 36: N-08 — `rudp_recv` with a buffer smaller than the queued block
     * must return -1 AND release `ctx->lock`.
     *
     * Unlike tests 31-35, this one needs a real `rudp_context_t`, but it does
     * NOT need a server: the C test creates the context via `rudp_create(1)`
     * and enqueues a block directly, so no UDP and no peer are involved. The
     * lock release is proven by a second thread acquiring `ctx->lock` within a
     * bounded 2000 ms wait; if the lock were still held, the C helper detaches
     * that thread and reports failure rather than hanging the suite.
     */
    @Test
    fun test36RudpRecvSmallBufferReleasesLock() {
        Log.d(TAG, "Running testNativeRudpRecvSmallBufferReleasesLock")

        val result = nativeTestRudpRecvSmallBufferReleasesLock()
        assertTrue(
            "N-08 rudp_recv lock release failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "N-08 rudp_recv passed: ${result.errorMessage}")
    }
}
