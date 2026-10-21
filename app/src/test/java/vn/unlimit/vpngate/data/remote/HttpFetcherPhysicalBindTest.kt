package vn.unlimit.vpngate.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.0.45 (§1.6.38) — T-NEW-58..60: the explicit PHYSICAL bind helper.
 *
 * v1.0.44 Phase B established (P2) that `path=open` was *unbound*, so when the
 * app's own tunnel was up the request silently went through it. v1.0.45
 * replaces it with [boundToPhysicalNetwork], which binds to a NOT_VPN network.
 *
 * `android.net.Network` is not constructable on a plain JVM, so these are
 * source-shape guards plus one runtime check of the shared client builder. They
 * pin:
 *
 *  - **T-NEW-58** — the helper exists, logs its own `[bind-physical]` prefix,
 *    and the three-value [CollectorPath] no longer contains OPEN/VPN.
 *  - **T-NEW-59** — the failure line carries the errno so a device EPERM can be
 *    told apart from a construction-time failure.
 *  - **T-NEW-60** — the helper reuses [boundToSocketFactory] and rethrows, so
 *    the PHYSICAL client cannot drift from the TUNNEL client's configuration.
 */
class HttpFetcherPhysicalBindTest {

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

    private fun source(): String = httpFetcherSource().readText()

    // ── T-NEW-58: the helper and its success line exist ────────────────────

    @Test
    fun `T-NEW-58a boundToPhysicalNetwork is defined and takes a Network`() {
        val src = source()
        assertTrue(
            "HttpFetcher.kt must define boundToPhysicalNetwork(network: Network)",
            src.contains("fun boundToPhysicalNetwork(network: Network)"),
        )
    }

    @Test
    fun `T-NEW-58b the physical bind has its own success log prefix`() {
        val src = source()
        assertTrue(
            "the physical bind must log [bind-physical] ok network= so a device " +
                "run can tell it apart from the tunnel bind",
            src.contains("[bind-physical] ok network="),
        )
    }

    @Test
    fun `T-NEW-58c CollectorPath has exactly PHYSICAL, TUNNEL and EMERGENCY`() {
        val src = source()
        val start = src.indexOf("enum class CollectorPath {")
        assertTrue("CollectorPath must exist", start >= 0)
        val body = src.substring(start, src.indexOf("}", start))
        val constants = body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("/") && it != "enum class CollectorPath {" }
            .map { it.substringBefore(',').substringBefore(' ').trim() }
            .filter { it.isNotEmpty() }
        assertEquals(
            "the enum must carry exactly the three honest transports, got $constants",
            listOf("PHYSICAL", "TUNNEL", "EMERGENCY"),
            constants,
        )
        assertFalse("the OPEN constant must be gone, got $constants", constants.contains("OPEN"))
        assertFalse("the VPN constant must be gone, got $constants", constants.contains("VPN"))
    }

    // ── T-NEW-59: the failure line carries the errno ───────────────────────

    @Test
    fun `T-NEW-59 the physical bind failure logs the errno`() {
        val src = source()
        assertTrue(
            "the physical bind must log [bind-physical] FAILED network= on failure",
            src.contains("[bind-physical] FAILED network="),
        )
        assertTrue(
            "the failure line must carry errno= so EPERM is attributable",
            src.contains("errno=\${e.message}"),
        )
    }

    // ── T-NEW-60: no drift from the tunnel client ──────────────────────────

    @Test
    fun `T-NEW-60 the physical helper reuses boundToSocketFactory and rethrows`() {
        val src = source()
        val start = src.indexOf("fun boundToPhysicalNetwork(network: Network)")
        assertTrue("helper must exist", start >= 0)
        val body = src.substring(start, src.indexOf("\nclass OkHttpFetcher", start))

        assertTrue(
            "the physical client must be built by the shared boundToSocketFactory, " +
                "or its timeouts/DNS could drift from the tunnel client: $body",
            body.contains("boundToSocketFactory(network.socketFactory)"),
        )
        assertTrue(
            "a failed bind must rethrow so the caller sees a real failure: $body",
            body.contains("throw e"),
        )
        assertFalse(
            "the helper must not swallow the failure into a null client: $body",
            body.contains("return null"),
        )
    }

    @Test
    fun `T-NEW-60b both bind helpers still exist side by side`() {
        val src = source()
        assertTrue("the tunnel bind must survive", src.contains("fun boundToNetwork(network: Network)"))
        assertTrue("the physical bind must exist", src.contains("fun boundToPhysicalNetwork(network: Network)"))
        assertTrue(
            "the tunnel bind must keep its own [bind] prefix",
            src.contains("[bind] ok network="),
        )
    }
}
