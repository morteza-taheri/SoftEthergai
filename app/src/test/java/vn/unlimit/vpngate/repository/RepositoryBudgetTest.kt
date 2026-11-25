package vn.unlimit.vpngate.repository

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.RedirectPolicy

/**
 * The update timeout, end to end.
 *
 * v1.0.88. Three separate defects produced the report "the timeout setting
 * does nothing", and this class pins the last one:
 *
 *  1. every source had its own hard-coded 12 s race timeout (fixed v1.0.87 —
 *     the source budget is now derived from the total);
 *  2. the source budget is 45 % of the total, so 120 s really does buy more
 *     than 45 s;
 *  3. **the Settings value never reached the Update button.** The list
 *     screen's repository was built as
 *     `VpnServerRepository(cacheDir = application.filesDir)`, i.e. with the
 *     hard-coded 45 s default, while only the background scheduler passed the
 *     setting. Device log, 2026-11-25, setting at 120 s:
 *
 *         [budget] total=45000ms source=20250ms directory=8000ms
 *
 *     which is the default, not the setting.
 *
 * A provider is what fixes (3): the deadline is read per refresh, so a change
 * applies to the next run without recreating the ViewModel or restarting.
 */
class RepositoryBudgetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val logs = CopyOnWriteArrayList<String>()

    /** Reports nothing acquired, so [VpnServerRepository.refresh] returns fast. */
    private class NullFetcher : HttpFetcher {
        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): Nothing? = null
    }

    private fun repository(
        mainTimeoutMs: Long = 45_000L,
        provider: (() -> Long)? = null,
    ): VpnServerRepository = VpnServerRepository(
        fetcher = NullFetcher(),
        cacheDir = tempFolder.newFolder(),
        routing = { CollectorRoutingInput(tunnelUsable = false, hasBoundNetwork = false) },
        pathLog = { logs.add(it) },
        fetchLog = { logs.add(it) },
        mainTimeoutMs = mainTimeoutMs,
        mainTimeoutProvider = provider,
    )

    private fun budgetLine(): String =
        logs.firstOrNull { it.startsWith("[budget]") }
            ?: error("no [budget] line was logged; saw ${logs.toList()}")

    @Test
    fun `the run states the budget it uses`() = runBlocking {
        repository().refresh()
        val line = budgetLine()
        // 45 s total: 45 % = 20 250 ms for one source, 45/6 = 7 500 ms for the
        // directory, floored at 8 000 ms.
        assertTrue(line, line.contains("total=45000ms"))
        assertTrue(line, line.contains("source=20250ms"))
        assertTrue(line, line.contains("directory=8000ms"))
    }

    @Test
    fun `a 120 second setting produces a 120 second deadline`() = runBlocking {
        repository(provider = { 120_000L }).refresh()
        val line = budgetLine()
        assertTrue(line, line.contains("total=120000ms"))
        assertTrue(line, line.contains("source=54000ms"))
        assertTrue(line, line.contains("directory=20000ms"))
    }

    @Test
    fun `changing the setting applies to the next refresh on the same instance`() = runBlocking {
        var settingSeconds = 45
        val repo = repository(provider = { settingSeconds * 1000L })

        repo.refresh()
        assertTrue(budgetLine(), budgetLine().contains("total=45000ms"))

        settingSeconds = 120
        logs.clear()
        repo.refresh()
        assertTrue(budgetLine(), budgetLine().contains("total=120000ms"))
    }

    @Test
    fun `a provider that reports nothing falls back to the injected deadline`() = runBlocking {
        // A zero/absent preference must never produce a zero deadline, which
        // would abort every refresh instantly and look like "the list is empty".
        repository(mainTimeoutMs = 30_000L, provider = { 0L }).refresh()
        assertTrue(budgetLine(), budgetLine().contains("total=30000ms"))
    }

    @Test
    fun `the source budget never drops below the old hard-coded floor`() = runBlocking {
        // Even at the smallest allowed setting (10 s) a single source keeps a
        // 12 s floor, so lowering the setting cannot make a working link fail.
        repository(provider = { 10_000L }).refresh()
        val line = budgetLine()
        assertTrue(line, line.contains("total=10000ms"))
        assertTrue(line, line.contains("source=12000ms"))
    }
}
