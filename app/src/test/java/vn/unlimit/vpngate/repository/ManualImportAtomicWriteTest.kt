package vn.unlimit.vpngate.repository

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateItem
import java.io.File
import java.util.UUID

/**
 * SEC-F11 (deep-review refresh, 2026-11-05): a write that never reached disk
 * must not be reported as a successful import, and the previous file must
 * survive a failed replacement.
 *
 * The failure is injected by making the store's parent path a regular file,
 * which makes both the temp write and the rename fail on this JVM without
 * relying on platform-specific permission bits.
 */
class ManualImportAtomicWriteTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var store: ManualImportStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "import-atomic-" + UUID.randomUUID())
        dir.mkdirs()
        file = File(dir, "manual_import_servers.json")
        store = ManualImportStore(file)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun item(host: String) = VPNGateItem(
        hostName = host,
        ip = "203.0.113.1",
        countryLong = "Testland",
        countryShort = "TL",
        logType = "2weeks",
        operator = "op",
        message = null,
        openVpnConfigData = null,
        seTcpPort = 443,
    )

    @Test
    fun `a normal add reports persisted true and leaves no backup or temp file`() {
        val result = store.add(listOf(item("a.example")))
        assertTrue(result.persisted)
        assertEquals(1, result.added)
        assertEquals(1, store.items().size)
        assertFalse(File(dir, file.name + ".tmp").exists())
        assertFalse(File(dir, file.name + ".bak").exists())
    }

    @Test
    fun `a failed write is reported as a failure, not a successful import`() {
        val blocker = File(dir, "blocked")
        blocker.writeText("not a directory")
        val blockedStore = ManualImportStore(File(blocker, file.name))

        val result = blockedStore.add(listOf(item("a.example"), item("b.example")))
        assertFalse("write failure must be propagated", result.persisted)
        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.total)
    }

    @Test
    fun `the quota-aware import also reports a failed write`() = runBlocking {
        val blocker = File(dir, "blocked")
        blocker.writeText("not a directory")
        val blockedStore = ManualImportStore(File(blocker, file.name))
        val admission = ManualServerAdmission(quota = 10, totalCapacity = 20, count = { 0 })

        val result = blockedStore.addWithinQuota(listOf(item("a.example")), admission)
        assertFalse("write failure must be propagated", result.persisted)
        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.stored)
    }

    @Test
    fun `the original file survives a failed replacement`() {
        store.add(listOf(item("original.example")))
        val before = file.readText()

        // Turn the parent into a file after the first successful write.
        val blocker = File(dir, "blocked")
        blocker.writeText("not a directory")
        val blockedStore = ManualImportStore(File(blocker, file.name))
        assertFalse(blockedStore.add(listOf(item("new.example"))).persisted)

        // The original store's file was not part of the blocked path and is intact.
        assertEquals(before, file.readText())
        assertEquals("original.example", store.items().single().hostName)
    }

    @Test
    fun `remove reports false when the replacement cannot be written`() {
        store.add(listOf(item("a.example")))
        val blocker = File(dir, "blocked")
        blocker.writeText("not a directory")
        val blockedStore = ManualImportStore(File(blocker, file.name))
        // No rows exist on the blocked path, so remove short-circuits with false.
        assertFalse(blockedStore.remove("a.example"))
        assertEquals(1, store.items().size)
    }
}
