package vn.unlimit.vpngate.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS (M2, spec §1.6.36), T-NEW-50..52 —
 * source-shape guards for the bind diagnostics added to [boundToNetwork].
 *
 * These tests do not exercise runtime binding (android.net.Network is not
 * constructable on a plain JVM); they pin the two diagnostic log lines and the
 * continued existence of the binding call so a refactor cannot silently drop
 * them. The source file is located by walking up from the module working dir.
 */
class HttpFetcherBindDiagnosticTest {

    private fun httpFetcherSource(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        val rel = "src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt"
        repeat(6) {
            val candidate = File(dir, rel)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError(
            "Could not locate HttpFetcher.kt for shape assertion (user.dir=" +
                System.getProperty("user.dir") + ")",
        )
    }

    // ── T-NEW-50: the success log line is present ─────────────────────────

    @Test
    fun `bind ok log line is present in source`() {
        val src = httpFetcherSource().readText()
        assertTrue(
            "HttpFetcher.kt must log [bind] ok network=",
            src.contains("[bind] ok network="),
        )
    }

    // ── T-NEW-51: the failure log line is present ────────────────────────

    @Test
    fun `bind failed log line is present in source`() {
        val src = httpFetcherSource().readText()
        assertTrue(
            "HttpFetcher.kt must log [bind] FAILED network=",
            src.contains("[bind] FAILED network="),
        )
    }

    // ── T-NEW-52: boundToNetwork still exists and performs the binding ────

    @Test
    fun `boundToNetwork still exists and performs the binding`() {
        val src = httpFetcherSource().readText()
        assertTrue(
            "boundToNetwork must still be defined",
            src.contains("fun boundToNetwork"),
        )
        assertTrue(
            "boundToNetwork must still build the bound client",
            src.contains("boundToSocketFactory"),
        )
    }

    // ── T-NEW-53: execute() catch logs the exception class for attribution ─

    @Test
    fun `execute catch logs exception class for attribution`() {
        val src = httpFetcherSource().readText()
        assertTrue(
            "OkHttpFetcher.execute() catch (the actual EPERM site) must log the " +
                "exception class so the device EPERM can be attributed " +
                "(diagnostic placement fix v1.0.44)",
            src.contains("exception=\$causeClass") ||
                src.contains("exception=\${e.javaClass.simpleName}"),
        )
    }
}
