package vn.unlimit.vpngate.db

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigDao
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigEntity
import vn.unlimit.vpngate.utils.DataUtil

/**
 * v1.0.46 (§1.6.39) — T-NEW-67..69: the default emergency source seeder.
 *
 * CHANGE A exists because `emergency_csv_publisher.py` writes
 * `R.string.default_emergency_source_url` into
 * `app/src/main/res/values/emergency_defaults.xml`, but before this phase no
 * Kotlin code read it — so a fresh install had an empty
 * `emergency_source_config` table, `ServerListUpdates.emergencyDataset()`
 * called `dao.listEnabled()` and got nothing, and the emergency path was dead.
 *
 * These tests drive [App.seedEmergencyDefaultIfNeeded] directly. It takes its
 * collaborators as parameters precisely so this is possible on a plain JVM:
 *
 *  - the DAO is an **interface**, so a hand-written fake stands in for Room.
 *    (Room's `inMemoryDatabaseBuilder` needs an Android `Context`, and this
 *    module has JUnit 4 only — no Robolectric — so a real in-memory database
 *    is not available here.)
 *  - the preference store is injected through `DataUtil`'s existing
 *    `internal constructor(sharedPreferences)` seam.
 *
 * The contract pinned here is the one that protects the user:
 * **once**, **idempotent**, **non-destructive**.
 */
class EmergencyDefaultSeedTest {

    private val url =
        "https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv"

    private fun dataUtil() = DataUtil(SeedFakeSharedPreferences())

    // ── T-NEW-67: a fresh install gets exactly one row ────────────────────

    @Test
    fun `T-NEW-67 fresh install seeds exactly one default row from the resource URL`() = runBlocking {
        val dao = FakeEmergencySourceDao()
        val dataUtil = dataUtil()

        val seeded = App.seedEmergencyDefaultIfNeeded(dataUtil, dao, url, now = 1_700_000_000_000L)

        assertTrue("the seeder must report that it inserted the row", seeded)
        assertEquals("exactly one row must exist", 1, dao.rows.size)

        val row = dao.rows.single()
        assertEquals("the row must carry the resource URL", url, row.url)
        assertEquals("the label marks the row as the app's default", "Default", row.label)
        assertTrue("the seeded source must be enabled", row.enabled)
        assertEquals(1_700_000_000_000L, row.createdAt)
        assertEquals(1_700_000_000_000L, row.updatedAt)
        assertEquals("id must stay unset so Room autogenerates it", 0, row.id)

        assertTrue(
            "the once-only flag must be set, or every start would try again",
            dataUtil.hasSeededEmergencyDefault(),
        )
    }

    // ── T-NEW-68: never overwrite a source the user already configured ─────

    @Test
    fun `T-NEW-68 an existing user source is never overwritten and the flag is set`() = runBlocking {
        val userRow = EmergencySourceConfigEntity(
            id = 7,
            url = "https://example.com/my-own-list.csv",
            label = "Mine",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val dao = FakeEmergencySourceDao(mutableListOf(userRow))
        val dataUtil = dataUtil()

        val seeded = App.seedEmergencyDefaultIfNeeded(dataUtil, dao, url)

        assertFalse("nothing may be inserted when the table is not empty", seeded)
        assertEquals("the table must still hold only the user's row", 1, dao.rows.size)
        assertEquals(userRow, dao.rows.single())
        assertEquals("no upsert may reach the DAO", 0, dao.upsertCount)
        assertTrue(
            "the flag must still be set, so we never re-check on later starts",
            dataUtil.hasSeededEmergencyDefault(),
        )
    }

    @Test
    fun `T-NEW-68b a disabled user row also blocks the seed`() = runBlocking {
        // "Non-empty" means any row, not just an enabled one: a user who added
        // a source and then disabled it has still configured the feature.
        val disabled = EmergencySourceConfigEntity(
            id = 3,
            url = "https://example.com/disabled.csv",
            label = "Disabled",
            enabled = false,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val dao = FakeEmergencySourceDao(mutableListOf(disabled))
        val dataUtil = dataUtil()

        assertFalse(App.seedEmergencyDefaultIfNeeded(dataUtil, dao, url))
        assertEquals(listOf(disabled), dao.rows)
        assertTrue(dataUtil.hasSeededEmergencyDefault())
    }

    // ── T-NEW-69: a deleted seeded row is not resurrected ──────────────────

    @Test
    fun `T-NEW-69 flag set and table empty means the user deleted it - no re-seed`() = runBlocking {
        val dao = FakeEmergencySourceDao()
        val dataUtil = dataUtil()
        // Simulate: the seed ran on an earlier launch and the user then removed
        // the row. That deletion is an explicit choice.
        dataUtil.markEmergencyDefaultSeeded()

        val seeded = App.seedEmergencyDefaultIfNeeded(dataUtil, dao, url)

        assertFalse("an empty table with the flag set must NOT be re-seeded", seeded)
        assertTrue("the table must stay empty", dao.rows.isEmpty())
        assertEquals("the DAO must not be touched at all", 0, dao.upsertCount)
    }

    // ── guards around the seed (defensive, but they decide a real outcome) ──

    @Test
    fun `a blank resource URL seeds nothing and leaves the flag unset`() = runBlocking {
        val dao = FakeEmergencySourceDao()
        val dataUtil = dataUtil()

        val seeded = App.seedEmergencyDefaultIfNeeded(dataUtil, dao, "   ")

        assertFalse(seeded)
        assertTrue(dao.rows.isEmpty())
        assertFalse(
            "a missing URL must not burn the once-only flag — the publisher may run later",
            dataUtil.hasSeededEmergencyDefault(),
        )
    }

    @Test
    fun `an invalid URL seeds nothing and leaves the flag unset`() = runBlocking {
        val dao = FakeEmergencySourceDao()
        val dataUtil = dataUtil()

        val seeded = App.seedEmergencyDefaultIfNeeded(dataUtil, dao, "not-a-url")

        assertFalse(seeded)
        assertTrue(dao.rows.isEmpty())
        assertFalse(dataUtil.hasSeededEmergencyDefault())
    }

    @Test
    fun `a DAO failure is swallowed and leaves the flag unset so the next start retries`() = runBlocking {
        val dao = FakeEmergencySourceDao(failOnRead = true)
        val dataUtil = dataUtil()

        val seeded = App.seedEmergencyDefaultIfNeeded(dataUtil, dao, url)

        assertFalse("a failure must be reported as 'nothing seeded', never thrown", seeded)
        assertFalse(dataUtil.hasSeededEmergencyDefault())
    }

    @Test
    fun `running the seeder twice inserts only one row`() = runBlocking {
        val dao = FakeEmergencySourceDao()
        val dataUtil = dataUtil()

        assertTrue(App.seedEmergencyDefaultIfNeeded(dataUtil, dao, url))
        assertFalse(App.seedEmergencyDefaultIfNeeded(dataUtil, dao, url))

        assertEquals("idempotent: exactly one row after two calls", 1, dao.rows.size)
        assertEquals(1, dao.upsertCount)
    }
}

/**
 * Minimal stand-in for [EmergencySourceConfigDao].
 *
 * The real type is a Room `@Dao` **interface**, so implementing it by hand is
 * enough — no Room, no SQLite, no Context. `observeAll()` returns a Flow of the
 * current rows, which is exactly what the seeder reads.
 */
private class FakeEmergencySourceDao(
    val rows: MutableList<EmergencySourceConfigEntity> = mutableListOf(),
    private val failOnRead: Boolean = false,
) : EmergencySourceConfigDao {

    var upsertCount = 0
        private set

    override fun observeAll(): Flow<List<EmergencySourceConfigEntity>> {
        if (failOnRead) throw IllegalStateException("database closed (test double)")
        return flowOf(rows.toList())
    }

    override suspend fun listEnabled(): List<EmergencySourceConfigEntity> =
        rows.filter { it.enabled }

    override suspend fun getByUrl(url: String): EmergencySourceConfigEntity? =
        rows.firstOrNull { it.url == url }

    override suspend fun upsert(item: EmergencySourceConfigEntity) {
        upsertCount++
        rows.removeAll { it.url == item.url }
        rows.add(item)
    }

    override suspend fun deleteByUrl(url: String) {
        rows.removeAll { it.url == url }
    }
}

/**
 * In-memory [SharedPreferences]. Named uniquely to avoid a top-level private
 * class collision with the same-named fakes in the other test files.
 */
private class SeedFakeSharedPreferences : SharedPreferences {

    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = SeedFakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class SeedFakeEditor(
    private val values: MutableMap<String, Any?>,
) : SharedPreferences.Editor {

    private val pending = LinkedHashMap<String, Any?>()
    private val removed = HashSet<String>()
    private var cleared = false

    override fun putString(key: String, value: String?): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putStringSet(
        key: String,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor {
        pending[key] = values
        return this
    }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun remove(key: String): SharedPreferences.Editor {
        removed.add(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        cleared = true
        return this
    }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        if (cleared) values.clear()
        removed.forEach { values.remove(it) }
        values.putAll(pending)
    }
}
