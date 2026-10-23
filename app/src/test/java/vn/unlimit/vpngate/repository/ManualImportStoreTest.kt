package vn.unlimit.vpngate.repository

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
 * v1.0.54 (§1.6.49) PART 4 — [ManualImportStore], the JSON file that keeps
 * imported rows alive across refreshes.
 *
 * Why this store exists at all is argued in its own KDoc; what this class pins
 * is the contract the rest of the app relies on:
 *
 *  - identity is `hostName`, so a second import updates instead of duplicating;
 *  - writes are atomic, so a crash cannot leave a file that reads back empty;
 *  - a corrupt file reads as empty and is *not* overwritten by the read;
 *  - the store never deletes anything it did not put there.
 *
 * Pure JVM against a real temporary directory — a fake `File` would not prove
 * the atomic-rename path, which is the only interesting part.
 */
class ManualImportStoreTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var store: ManualImportStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "manual-import-" + UUID.randomUUID())
        dir.mkdirs()
        file = File(dir, "manual_import_servers.json")
        store = ManualImportStore(file)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun item(host: String, ip: String = "203.0.113.1"): VPNGateItem = VPNGateItem(
        hostName = host,
        ip = ip,
        countryLong = "Testland",
        countryShort = "TL",
        logType = "2weeks",
        operator = "op",
        message = null,
        openVpnConfigData = null,
        seTcpPort = 443,
    )

    // ── empty / fresh ────────────────────────────────────────────────────

    @Test
    fun `a store whose file does not exist reads as empty`() {
        assertTrue(store.items().isEmpty())
    }

    @Test
    fun `adding nothing is a no-op that creates no file`() {
        val result = store.add(emptyList())
        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertFalse(file.exists())
    }

    @Test
    fun `adding an empty host name is skipped`() {
        val result = store.add(listOf(item(""), item("   ")))
        assertEquals(0, result.added)
        assertTrue(store.items().isEmpty())
    }

    // ── add ──────────────────────────────────────────────────────────────

    @Test
    fun `a first import reports every row as added`() {
        val result = store.add(listOf(item("a.example"), item("b.example")))
        assertEquals(2, result.added)
        assertEquals(0, result.updated)
        assertEquals(2, store.items().size)
    }

    @Test
    fun `re-importing the same rows reports them as updated, not added`() {
        store.add(listOf(item("a.example")))
        val second = store.add(listOf(item("a.example", ip = "198.51.100.9")))
        assertEquals(0, second.added)
        assertEquals(1, second.updated)
        assertEquals(1, store.items().size)
        assertEquals("198.51.100.9", store.items().single().ip)
    }

    @Test
    fun `a mixed import reports added and updated separately`() {
        store.add(listOf(item("a.example")))
        val second = store.add(listOf(item("a.example"), item("b.example")))
        assertEquals(1, second.added)
        assertEquals(1, second.updated)
        assertEquals(2, second.total)
    }

    @Test
    fun `host name matching ignores surrounding whitespace`() {
        store.add(listOf(item("a.example")))
        val second = store.add(listOf(item("  a.example  ")))
        assertEquals(0, second.added)
        assertEquals(1, second.updated)
    }

    @Test
    fun `insertion order is preserved`() {
        store.add(listOf(item("c.example"), item("a.example"), item("b.example")))
        assertEquals(
            listOf("c.example", "a.example", "b.example"),
            store.items().map { it.hostName },
        )
    }

    @Test
    fun `a row round-trips every field it was given`() {
        val original = item("a.example").copy(
            seTcpPort = 443,
            seUdpPort = 500,
            seUdpSupported = true,
            sstpPort = 8443,
            isSSTPSupport = true,
            firstSeenAt = 111L,
            lastSeenAt = 222L,
            healthScore = 50,
        )
        store.add(listOf(original))
        assertEquals(original, store.items().single())
    }

    // ── remove / clear ───────────────────────────────────────────────────

    @Test
    fun `removing a known host drops exactly that row`() {
        store.add(listOf(item("a.example"), item("b.example")))
        assertTrue(store.remove("a.example"))
        assertEquals(listOf("b.example"), store.items().map { it.hostName })
    }

    @Test
    fun `removing an unknown host reports false and changes nothing`() {
        store.add(listOf(item("a.example")))
        assertFalse(store.remove("nope.example"))
        assertEquals(1, store.items().size)
    }

    @Test
    fun `clearing deletes the file and reads back empty`() {
        store.add(listOf(item("a.example")))
        assertTrue(store.clear())
        assertTrue(store.items().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `clearing an absent store reports false`() {
        assertFalse(store.clear())
    }

    // ── durability ───────────────────────────────────────────────────────

    @Test
    fun `rows survive a new store instance over the same file`() {
        store.add(listOf(item("a.example"), item("b.example")))
        val reopened = ManualImportStore(file)
        assertEquals(
            store.items().map { it.hostName },
            reopened.items().map { it.hostName },
        )
    }

    @Test
    fun `a write leaves no temp file behind`() {
        store.add(listOf(item("a.example")))
        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("expected no temp files, found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `a corrupt file reads as empty and is not overwritten by the read`() {
        file.writeText("{ this is not json", Charsets.UTF_8)
        assertTrue(store.items().isEmpty())
        // The read must not repair or replace it: silently rewriting a file the
        // store did not understand is how data loss gets hidden.
        assertEquals("{ this is not json", file.readText(Charsets.UTF_8))
    }

    @Test
    fun `an empty file reads as empty`() {
        file.writeText("", Charsets.UTF_8)
        assertTrue(store.items().isEmpty())
    }

    @Test
    fun `an add after a corrupt file replaces it with a valid one`() {
        file.writeText("not json at all", Charsets.UTF_8)
        val result = store.add(listOf(item("a.example")))
        assertEquals(1, result.added)
        assertEquals(1, store.items().size)
    }

    @Test
    fun `the store creates its parent directory when missing`() {
        val nested = File(File(dir, "a/b/c"), "manual_import_servers.json")
        ManualImportStore(nested).add(listOf(item("a.example")))
        assertTrue(nested.isFile)
    }

    @Test
    fun `concurrent adds from many threads lose no rows`() {
        val threads = (1..8).map { t ->
            Thread {
                repeat(25) { i -> store.add(listOf(item("host-$t-$i"))) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(200, store.items().size)
    }
}
