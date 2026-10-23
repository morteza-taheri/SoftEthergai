package vn.unlimit.vpngate.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.data.remote.ManualServerEntity
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * v1.0.55 (`V1054_VERIFY_REPORT` G-2): imported CSV rows must be re-emitted on
 * every write, so a refresh cannot delete them.
 *
 * v1.0.54 shipped the rule — `manualServerRows()` returns
 * `legacyRows + importedRows`, and the single write applies `withManualRows` to
 * the result — but no test covered it. `manualServerRows()` was private and
 * reached `App.instance`, a singleton the JVM harness cannot construct, so the
 * one line that makes the feature work was the one line nothing checked. The
 * function now takes its two sources as providers; production passes the
 * singleton lookups, and these tests pass fakes.
 *
 * What is asserted here is the *emission*, not the parser: `ManualCsvImportTest`
 * and `ManualImportStoreTest` already own the parsing and the file format.
 */
class ImportedServerEmissionTest {

    // ------------------------------------------------------------------ fakes

    private class FakeDao(private val rows: List<ManualServerEntity>) : ManualServerDao {
        override fun observeAll(): Flow<List<ManualServerEntity>> = flowOf(rows)
        override suspend fun listAll(): List<ManualServerEntity> = rows
        override suspend fun listEnabled(): List<ManualServerEntity> = rows.filter { it.enabled }
        override suspend fun findByKey(host: String, port: Int, protocol: String): ManualServerEntity? =
            rows.firstOrNull { it.host == host && it.port == port && it.protocol == protocol }

        override suspend fun upsert(item: ManualServerEntity): Long = 1L
        override suspend fun deleteById(id: Long) = Unit
    }

    private fun legacyRow(host: String, enabled: Boolean = true) = ManualServerEntity(
        host = host,
        port = 443,
        protocol = VpnProtocolNames.SOFTETHER,
        label = null,
        notes = null,
        enabled = enabled,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun importedRow(host: String): VPNGateItem = VPNGateItem(
        hostName = host,
        ip = host,
        countryLong = null,
        countryShort = null,
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = null,
    )

    /** A network dataset that does not contain any of the user's hosts. */
    private fun networkRows(vararg hosts: String): List<VPNGateItem> =
        hosts.map { importedRow(it) }

    // -------------------------------------------------------------- emission

    @Test
    fun `imported rows are emitted when there is no legacy dao`() = runBlocking {
        val emitted = manualServerRows(
            importedProvider = { listOf(importedRow("203.0.113.9")) },
            daoProvider = { null },
        )

        assertEquals(listOf("203.0.113.9"), emitted.map { it.hostName })
    }

    @Test
    fun `imported rows are emitted alongside legacy rows`() = runBlocking {
        val emitted = manualServerRows(
            importedProvider = { listOf(importedRow("203.0.113.9")) },
            daoProvider = { FakeDao(listOf(legacyRow("198.51.100.4"))) },
        )

        assertEquals(
            "the older surface is emitted first, then the imported rows",
            listOf("198.51.100.4", "203.0.113.9"),
            emitted.map { it.hostName },
        )
    }

    @Test
    fun `a disabled legacy row is still not emitted and does not hide imported rows`() = runBlocking {
        val emitted = manualServerRows(
            importedProvider = { listOf(importedRow("203.0.113.9")) },
            daoProvider = { FakeDao(listOf(legacyRow("198.51.100.4", enabled = false))) },
        )

        assertEquals(listOf("203.0.113.9"), emitted.map { it.hostName })
    }

    @Test
    fun `an empty import list leaves the legacy rows exactly as they were`() = runBlocking {
        val emitted = manualServerRows(
            importedProvider = { emptyList() },
            daoProvider = { FakeDao(listOf(legacyRow("198.51.100.4"))) },
        )

        assertEquals(listOf("198.51.100.4"), emitted.map { it.hostName })
    }

    @Test
    fun `a failing import provider does not break the refresh`() = runBlocking {
        // A corrupt `manual_import_servers.json` must cost the user their imports,
        // not their whole server list. `ManualImportStore` already reads a corrupt
        // file as empty, so this covers the layer above it: the emission must
        // never throw into the refresh.
        val emitted = manualServerRows(
            importedProvider = { throw IllegalStateException("corrupt store") },
            daoProvider = { FakeDao(listOf(legacyRow("198.51.100.4"))) },
        )

        assertEquals(listOf("198.51.100.4"), emitted.map { it.hostName })
    }

    @Test
    fun `a failing dao does not cost the user their imported rows`() = runBlocking {
        val emitted = manualServerRows(
            importedProvider = { listOf(importedRow("203.0.113.9")) },
            daoProvider = { throw IllegalStateException("database closed") },
        )

        assertEquals(listOf("203.0.113.9"), emitted.map { it.hostName })
    }

    // ------------------------------------------------------------ survival

    @Test
    fun `imported rows survive a wholesale replace`() = runBlocking {
        // `replaceAll` stores exactly what it is given, so the merge is the only
        // thing standing between an imported server and deletion.
        val emitted = manualServerRows(
            importedProvider = { listOf(importedRow("203.0.113.9")) },
            daoProvider = { null },
        )
        val network = networkRows("192.0.2.9", "192.0.2.10")

        val stored = withManualRows(network, emitted)

        assertTrue(
            "the imported row must be in the stored list, got ${stored.map { it.hostName }}",
            stored.any { it.hostName == "203.0.113.9" },
        )
        assertTrue(
            "no network row may be dropped to make room",
            stored.map { it.hostName }.containsAll(listOf("192.0.2.9", "192.0.2.10")),
        )
    }

    @Test
    fun `an imported row the network already serves is not duplicated`() = runBlocking {
        val emitted = manualServerRows(
            importedProvider = { listOf(importedRow("192.0.2.9")) },
            daoProvider = { null },
        )
        val network = networkRows("192.0.2.9", "192.0.2.10")

        val stored = withManualRows(network, emitted)

        assertEquals(
            "the collected row wins; the user's copy is not added beside it",
            2,
            stored.size,
        )
        assertEquals(1, stored.count { it.hostName == "192.0.2.9" })
    }

    @Test
    fun `an empty emission leaves the network dataset untouched by identity`() = runBlocking {
        val emitted = manualServerRows(
            importedProvider = { emptyList() },
            daoProvider = { null },
        )
        val network = networkRows("192.0.2.9")

        assertTrue(
            "an empty manual set must be a no-op, not a copy",
            network === withManualRows(network, emitted),
        )
    }
}
