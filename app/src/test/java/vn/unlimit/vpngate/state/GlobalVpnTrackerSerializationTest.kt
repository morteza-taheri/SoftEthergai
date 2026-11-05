package vn.unlimit.vpngate.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEC-F10 (deep-review refresh, 2026-11-05) — source-shape regression.
 *
 * `GlobalVpnTracker` needs `Handler(Looper.getMainLooper())` and therefore
 * cannot be instantiated on this module's plain-JVM test runtime (no
 * Robolectric). The contract is pinned textually, the same convention used by
 * `SelfExclusionRemovedTest` and `GlobalVpnTrackerRoutingTest`.
 *
 * The defect: `updateState` did `reconcile(liveStacks.toList())` ->
 * `liveStacks.clear()` -> `addAll()` without serializing the whole
 * read-modify-write. `CopyOnWriteArrayList` only keeps the list structurally
 * safe; two producers (SoftEther / OpenVPN / SSTP callbacks arrive on
 * different threads) could each snapshot the same ledger and then clobber
 * each other's event.
 */
class GlobalVpnTrackerSerializationTest {

    private fun trackerSource(): String {
        val relative = "app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt"
        for (path in listOf(relative, "../$relative", "../../$relative")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relative (cwd=${File(".").absolutePath})")
    }

    @Test
    fun `updateState holds one lock across reconcile and republish`() {
        val source = trackerSource()
        val start = source.indexOf("fun updateState(")
        assertTrue("updateState must exist", start >= 0)
        // Take the whole function body up to the next top-level KDoc.
        val end = source.indexOf("\n    /**", start).let { if (it < 0) source.length else it }
        val body = source.substring(start, end)

        assertTrue("a dedicated state lock must be declared", source.contains("private val stateLock = Any()"))
        assertTrue("updateState must enter the lock", body.contains("synchronized(stateLock)"))
        assertTrue("reconcile must run inside the lock", body.contains("VpnStackLedger.reconcile("))
        assertTrue("the ledger replacement must run inside the lock", body.contains("liveStacks.clear()"))
        assertTrue("republish must run inside the lock", body.contains("addAll(verdict.live)"))

        val lockAt = body.indexOf("synchronized(stateLock)")
        val reconcileAt = body.indexOf("VpnStackLedger.reconcile(")
        val clearAt = body.indexOf("liveStacks.clear()")
        val addAllAt = body.indexOf("addAll(verdict.live)")
        assertTrue("reconcile must be after lock acquisition", reconcileAt > lockAt)
        assertTrue("clear must be after lock acquisition", clearAt > lockAt)
        assertTrue("addAll must be after lock acquisition", addAllAt > lockAt)
    }

    @Test
    fun `connectedAtMs reads the anchor under the same lock`() {
        val source = trackerSource()
        assertTrue(
            "the anchor read must be guarded by stateLock too",
            source.contains("internal fun connectedAtMs(): Long = synchronized(stateLock)"),
        )
    }
}
