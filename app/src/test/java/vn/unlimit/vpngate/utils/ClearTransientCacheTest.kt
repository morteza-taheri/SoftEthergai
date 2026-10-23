package vn.unlimit.vpngate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.0.55 (`V1054_VERIFY_REPORT` G-1): the "Clear cache" contract, tested.
 *
 * v1.0.54 deleted three Settings controls and replaced two `DataUtil` accessors
 * with one [DataUtil.clearTransientCache]. The contract — *delete the transient
 * cache and the legacy expiry marker, and nothing else* — was written down in a
 * KDoc comment and asserted by no test at all: the instance method needs a
 * `Context`, and this module's unit tests are plain JVM with no Robolectric.
 *
 * [DataUtil.clearTransientCache] now takes the two directories as arguments, so
 * the production code path can be exercised against real directories. These
 * tests are therefore not a model of the behaviour; they are the behaviour.
 *
 * The files that matter are the ones that must SURVIVE. `filesDir` is where the
 * Room database, `manual_import_servers.json` and the emergency-source
 * configuration live, and `clearTransientCache` is one `deleteRecursively()`
 * away from destroying all of it — which is exactly why it is pinned here.
 */
class ClearTransientCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cacheDir(): File = temp.newFolder("cache")

    private fun filesDir(): File = temp.newFolder("files")

    private fun write(dir: File, name: String, body: String = "x"): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeText(body, Charsets.UTF_8)
        }

    // ---------------------------------------------------------------- deletes

    @Test
    fun `a populated cache directory is emptied`() {
        val cache = cacheDir()
        write(cache, "img_1.png")
        write(cache, "http/responses.bin")
        write(cache, "nested/deep/file.tmp")

        assertTrue(DataUtil.clearTransientCache(cache, filesDir()))

        assertEquals(
            "every child of cacheDir must be gone, got ${cache.list()?.toList()}",
            emptyList<String>(),
            cache.list()?.toList(),
        )
    }

    @Test
    fun `the cache directory itself survives`() {
        val cache = cacheDir()
        write(cache, "img_1.png")

        DataUtil.clearTransientCache(cache, filesDir())

        assertTrue("cacheDir is the platform's to remove, not ours", cache.isDirectory)
    }

    @Test
    fun `the legacy expiry marker is deleted`() {
        val files = filesDir()
        val marker = write(files, DataUtil.CONNECTION_CACHE_KEY, """{"expires":"2026-01-01"}""")

        assertTrue(DataUtil.clearTransientCache(cacheDir(), files))

        assertFalse("the legacy marker must go", marker.exists())
    }

    // --------------------------------------------------------------- survives

    @Test
    fun `imported servers in filesDir are untouched`() {
        val files = filesDir()
        val imported = write(files, "manual_import_servers.json", """[{"hostName":"a.example"}]""")

        DataUtil.clearTransientCache(cacheDir(), files)

        assertTrue("imported servers are user data, not cache", imported.isFile)
        assertEquals(
            """[{"hostName":"a.example"}]""",
            imported.readText(Charsets.UTF_8),
        )
    }

    @Test
    fun `the database and its journals in filesDir are untouched`() {
        val files = filesDir()
        val db = write(files, "app_database", "sqlite")
        val wal = write(files, "app_database-wal", "wal")
        val shm = write(files, "app_database-shm", "shm")

        DataUtil.clearTransientCache(cacheDir(), files)

        assertTrue("the server dataset must never be cleared by a cache button", db.isFile)
        assertTrue(wal.isFile)
        assertTrue(shm.isFile)
    }

    @Test
    fun `any other filesDir entry survives`() {
        val files = filesDir()
        val preferences = write(files, "app_preferences.xml", "<map/>")
        val datastore = write(files, "datastore/prefs.pb", "pb")

        DataUtil.clearTransientCache(cacheDir(), files)

        assertTrue(preferences.isFile)
        assertTrue(datastore.isFile)
    }

    @Test
    fun `a sibling of the cache directory is untouched`() {
        // The two directories are passed in; nothing outside them is reachable.
        val root = temp.newFolder("root")
        val cache = File(root, "cache").apply { mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        write(cache, "junk.tmp")
        val outsider = write(root, "outsider.txt", "keep me")

        DataUtil.clearTransientCache(cache, files)

        assertTrue(outsider.isFile)
        assertEquals("keep me", outsider.readText(Charsets.UTF_8))
    }

    // ------------------------------------------------------------- edge cases

    @Test
    fun `a missing directory is a success, not a failure`() {
        val gone = File(temp.root, "never-created")

        assertTrue(
            "an absent cacheDir means there is nothing to clean",
            DataUtil.clearTransientCache(gone, File(temp.root, "also-missing")),
        )
    }

    @Test
    fun `null directories are a no-op success`() {
        // Production passes `mContext?.cacheDir` / `mContext?.filesDir`, which are
        // null on the test-only DataUtil(SharedPreferences) constructor. Returning
        // false there would make every such call report a failed cleanup.
        assertTrue(DataUtil.clearTransientCache(null, null))
    }

    @Test
    fun `an already-clean cache is a success`() {
        assertTrue(DataUtil.clearTransientCache(cacheDir(), filesDir()))
    }

    @Test
    fun `running it twice is idempotent`() {
        val cache = cacheDir()
        val files = filesDir()
        write(cache, "a.tmp")
        write(files, DataUtil.CONNECTION_CACHE_KEY)

        assertTrue(DataUtil.clearTransientCache(cache, files))
        assertTrue("a second clear must not start failing", DataUtil.clearTransientCache(cache, files))
    }
}
