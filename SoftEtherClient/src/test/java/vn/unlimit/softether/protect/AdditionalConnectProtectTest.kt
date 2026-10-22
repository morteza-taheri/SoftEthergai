package vn.unlimit.softether.protect

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.0.51 (ADDITIONAL-CONNECT-PROTECT) — source-shape tests.
 *
 * The defect these pin lives in C (`softether_additional_connect`) and in the
 * JNI upcall, neither of which can be executed from a plain JVM unit test: the
 * native library needs an Android host and a live VpnService. The repo already
 * uses this source-shape pattern where a module cannot be run in isolation, so
 * these tests read the shipped sources as text and assert the invariants that
 * make the fix correct. They are deliberately narrow: they fail if someone
 * moves the protect call after the connect, drops the fail-closed branch, or
 * forgets to detach the protector on teardown.
 *
 * Physical-device proof is a separate step (V-VERIFY-3, PENDING).
 */
class AdditionalConnectProtectTest {

    private val repoRoot: File = run {
        // Gradle runs unit tests with the module directory as the working dir.
        var dir: File = File(".").absoluteFile
        while (!File(dir, "SoftEtherClient").isDirectory) {
            val parent = dir.parentFile ?: break
            dir = parent
        }
        dir
    }

    private fun read(relativePath: String): String {
        val f = File(repoRoot, relativePath)
        assertTrue("missing source file: ${f.absolutePath}", f.isFile)
        // The working tree is checked out with CRLF on Windows; normalise so
        // multi-line markers match on every platform.
        return f.readText().replace("\r\n", "\n")
    }

    private val protocolC by lazy {
        read("SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c")
    }
    private val protocolH by lazy {
        read("SoftEtherClient/src/main/cpp/softether-core/include/softether_protocol.h")
    }
    private val jniC by lazy { read("SoftEtherClient/src/main/cpp/jni/softether_jni.c") }
    private val controllerKt by lazy {
        read("SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt")
    }
    private val clientKt by lazy {
        read("SoftEtherClient/src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt")
    }

    /** Slice of [text] from [startMarker] up to [endMarker] (exclusive). */
    private fun slice(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        assertTrue("start marker not found: $startMarker", start >= 0)
        val end = text.indexOf(endMarker, start)
        assertTrue("end marker not found after start: $endMarker", end > start)
        return text.substring(start, end)
    }

    private fun additionalConnectBody(): String =
        slice(protocolC, "int softether_additional_connect(softether_connection_t* conn) {",
            "\nint softether_select_send_socket(")

    // ---------------------------------------------------------------------
    // T-PROTECT-01 — the additional-connect socket is protected BEFORE connect
    // ---------------------------------------------------------------------
    @Test
    fun `T-PROTECT-01 additional connect protects the socket before connecting`() {
        val body = additionalConnectBody()

        val idxCreate = body.indexOf("socket_create(SOCKET_TYPE_TCP)")
        val idxProtect = body.indexOf("conn->protect_socket_fn(conn->protect_socket_ctx, sock->fd)")
        val idxConnect = body.indexOf("socket_connect_timeout(sock, conn->server_ip")

        assertTrue("socket_create(SOCKET_TYPE_TCP) missing from additional connect", idxCreate >= 0)
        assertTrue("protect hook is not invoked from additional connect", idxProtect >= 0)
        assertTrue("socket_connect_timeout(server_ip) missing from additional connect", idxConnect >= 0)

        assertTrue(
            "protect must be called after socket_create (was $idxProtect, create was $idxCreate)",
            idxProtect > idxCreate,
        )
        assertTrue(
            "protect must be called BEFORE connect — that ordering IS the v1.0.51 fix " +
                "(protect=$idxProtect, connect=$idxConnect)",
            idxProtect < idxConnect,
        )
    }

    // ---------------------------------------------------------------------
    // T-PROTECT-02 — a failed protect aborts the attempt (fail closed)
    // ---------------------------------------------------------------------
    @Test
    fun `T-PROTECT-02 failed protect aborts the additional connect before dialling`() {
        val body = additionalConnectBody()
        val idxFailure = body.indexOf("socket protect failed")
        val idxConnect = body.indexOf("socket_connect_timeout(sock, conn->server_ip")

        assertTrue("no failure log for a failed protect", idxFailure >= 0)
        assertTrue("failed protect must abort before connect", idxFailure < idxConnect)

        val abortBranch = body.substring(idxFailure, idxConnect)
        assertTrue(
            "failed protect must destroy the socket instead of dialling it",
            abortBranch.contains("socket_destroy(sock)"),
        )
        assertTrue(
            "failed protect must abandon this additional connection",
            abortBranch.contains("return -1"),
        )
        assertTrue(
            "failed protect must count as a failure so the retry budget is honoured",
            abortBranch.contains("additional_failed_count++"),
        )
    }

    // ---------------------------------------------------------------------
    // T-PROTECT-03 — the hook is plumbed through the connection, not a global
    // ---------------------------------------------------------------------
    @Test
    fun `T-PROTECT-03 protector is registered per connection and clearable`() {
        assertTrue(
            "connection struct must carry the protect hook",
            protocolH.contains("int (*protect_socket_fn)(void* ctx, int fd);"),
        )
        assertTrue(
            "connection struct must carry the hook context",
            protocolH.contains("void* protect_socket_ctx;"),
        )
        assertTrue(
            "setter declaration missing",
            protocolH.contains("int softether_set_socket_protector(softether_connection_t* conn,"),
        )
        assertTrue(
            "clear declaration missing",
            protocolH.contains("void softether_clear_socket_protector(softether_connection_t* conn);"),
        )
        assertTrue(
            "setter implementation missing",
            protocolC.contains("int softether_set_socket_protector(softether_connection_t* conn,"),
        )
        assertTrue(
            "clear implementation must null the hook so teardown cannot re-enter it",
            protocolC.contains("conn->protect_socket_fn = NULL;"),
        )
    }

    // ---------------------------------------------------------------------
    // T-PROTECT-04 — JNI upcall: attach/detach, exception-safe, refs released
    // ---------------------------------------------------------------------
    @Test
    fun `T-PROTECT-04 JNI upcall attaches the JavaVM and never leaks the service ref`() {
        val upcall = slice(jniC, "static int jni_protect_upcall(", "static int jni_protect_socket_cb(")

        assertTrue("upcall must resolve JNIEnv with GetEnv", upcall.contains("GetEnv"))
        assertTrue("upcall must attach detached background threads", upcall.contains("AttachCurrentThread"))
        assertTrue("upcall must detach what it attached", upcall.contains("DetachCurrentThread"))
        assertTrue("upcall must test for a pending exception", upcall.contains("ExceptionCheck"))
        assertTrue("upcall must clear a pending exception", upcall.contains("ExceptionClear"))
        assertTrue(
            "upcall must call VpnService.protect(int)",
            upcall.contains("GetMethodID(env, cls, \"protect\", \"(I)Z\")"),
        )
        assertTrue(
            "upcall must take a local ref so a concurrent release is safe",
            upcall.contains("NewLocalRef"),
        )
        assertTrue(
            "a released protector must fail closed rather than call into Java",
            upcall.contains("p->released"),
        )

        val registration = slice(
            jniC,
            "Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetSocketProtector(",
            "JNIEXPORT jintArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetAllSocketFds",
        )
        assertTrue("registration must take a global ref", registration.contains("NewGlobalRef"))
        assertTrue("registration must drop a replaced global ref", registration.contains("DeleteGlobalRef"))
        assertTrue(
            "registration must arm the native hook",
            registration.contains("softether_set_socket_protector(conn, jni_protect_socket_cb, p)"),
        )
    }

    @Test
    fun `T-PROTECT-05 nativeDestroy detaches the protector after the thread is joined`() {
        val destroy = slice(
            jniC,
            "Java_vn_unlimit_softether_client_SoftEtherClient_nativeDestroy(",
            "Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetSocketProtector(",
        )

        val idxClear = destroy.indexOf("softether_clear_socket_protector(conn);")
        val idxDestroy = destroy.indexOf("softether_destroy(conn);")
        val idxRelease = destroy.indexOf("jni_socket_protector_release(env, protector);")

        assertTrue("nativeDestroy must clear the hook", idxClear >= 0)
        assertTrue("nativeDestroy must still destroy the connection", idxDestroy >= 0)
        assertTrue("nativeDestroy must release the protector", idxRelease >= 0)

        assertTrue(
            "the hook must be cleared before the connection is freed",
            idxClear < idxDestroy,
        )
        assertTrue(
            "the host ref must be released only AFTER softether_destroy() joined the " +
                "background additional-connect thread (release=$idxRelease, destroy=$idxDestroy)",
            idxRelease > idxDestroy,
        )
    }

    // ---------------------------------------------------------------------
    // T-PROTECT-06 — Kotlin arms the protector on both connect paths
    // ---------------------------------------------------------------------
    @Test
    fun `T-PROTECT-06 controller arms the protector before every nativeConnectWithHub`() {
        assertTrue(
            "SoftEtherClient must expose the bridge method",
            clientKt.contains("fun setSocketProtector(handle: Long, protector: Any?): Boolean"),
        )
        assertTrue(
            "SoftEtherClient must declare the JNI entry point",
            clientKt.contains("external fun nativeSetSocketProtector(handle: Long, protector: Any?): Boolean"),
        )

        val armSites = Regex("client\\.setSocketProtector\\(created, service\\)")
            .findAll(controllerKt).map { it.range.first }.toList()
        val connectSites = Regex("client\\.nativeConnectWithHub\\(")
            .findAll(controllerKt).map { it.range.first }.toList()

        assertTrue(
            "expected the protector to be armed on both the connect and reconnect paths, " +
                "found ${armSites.size}",
            armSites.size == 2,
        )
        assertTrue("expected two nativeConnectWithHub call sites, found ${connectSites.size}",
            connectSites.size == 2)

        // Each arming site must precede the connect site that follows it.
        for (arm in armSites) {
            val nextConnect = connectSites.firstOrNull { it > arm }
            assertNotNull("no nativeConnectWithHub follows the arming site at $arm", nextConnect)
            assertTrue(
                "protector must be armed before the connect it protects (arm=$arm, connect=$nextConnect)",
                nextConnect!! > arm,
            )
        }
    }

    // ---------------------------------------------------------------------
    // T-PROTECT-07 — the periodic safety net and fd lifecycle are preserved
    // ---------------------------------------------------------------------
    @Test
    fun `T-PROTECT-07 periodic protect safety net and protectedFds reset are preserved`() {
        assertTrue(
            "the receive-loop safety net must survive the fix",
            controllerKt.contains("if (receiveCount % 50 == 0) {"),
        )
        assertTrue(
            "the safety net must still call protectAdditionalSockets()",
            controllerKt.contains("protectAdditionalSockets()"),
        )

        val clears = Regex("protectedFds\\.clear\\(\\)").findAll(controllerKt).count()
        assertTrue(
            "protectedFds must be cleared on every native teardown so a recycled fd " +
                "number cannot suppress protect() in a new session (found $clears clears)",
            clears >= 2,
        )
    }
}
