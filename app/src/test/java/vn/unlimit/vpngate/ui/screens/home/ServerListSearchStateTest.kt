package vn.unlimit.vpngate.ui.screens.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * Regression tests for the Server List search defect.
 *
 * [VPNGateConnectionList] is mutated in place by the query path, so the search
 * pipeline below stands in only for the SQL-backed `advancedFilter`/`filter`/
 * `sort` calls. Everything it touches around them — the keyword lifecycle and,
 * above all, the state/display boundary that decides what Compose renders — is
 * the real production code in [ServerListSearchState].
 */
class ServerListSearchStateTest {

    private val japan = item("public-vpn-1.opengw.net", "219.100.37.87", "Japan", "Daiyuu Nobori", 562)
    private val usa = item("public-vpn-2.opengw.net", "128.199.1.5", "United States", "Acme Networks", 120)
    private val germany = item("public-vpn-3.opengw.net", "91.2.3.4", "Germany", "Deutsche Telekom", 40)
    private val all = listOf(japan, usa, germany)

    // ----- Helpers -------------------------------------------------------

    /**
     * Mirrors ServerListSearchState's keyword rules and the production refresh
     * order (clear keyword -> advanced filter -> keyword -> sort), then
     * publishes through the real state boundary.
     */
    private fun refresh(
        state: ServerListSearchState,
        model: VPNGateConnectionList,
        source: List<VPNGateItem>,
        filter: VPNGateConnectionList.Filter? = null,
        sortProperty: String = "",
        sortType: Int = VPNGateConnectionList.ORDER.ASC,
    ) {
        val keyword = state.effectiveKeyword()
        model.clearKeyword()
        var result = source
        if (filter != null) {
            result = result.filter { it.speed >= 100 }
        }
        if (keyword.isNotEmpty()) {
            result = result.filter { matchesKeyword(it, keyword) }
        }
        if (sortProperty.isNotEmpty()) {
            result = when (sortProperty) {
                VPNGateConnectionList.SortProperty.SPEED ->
                    result.sortedBy { if (sortType == VPNGateConnectionList.ORDER.DESC) -it.speed else it.speed }
                VPNGateConnectionList.SortProperty.COUNTRY ->
                    result.sortedBy { it.countryLong }
                else -> result
            }
        }
        state.publish(model.fromVPNGateItems(result))
    }

    /** Mirrors VPNGateConnectionList.getFilterQuery()'s predicate. */
    private fun matchesKeyword(candidate: VPNGateItem, keyword: String): Boolean {
        val needle = keyword.lowercase()
        return listOfNotNull(candidate.countryLong, candidate.hostName, candidate.operator, candidate.ip)
            .any { it.lowercase().contains(needle) }
    }

    private fun item(
        hostName: String,
        ip: String,
        country: String,
        operator: String,
        speed: Int,
    ) = VPNGateItem(
        hostName = hostName,
        ip = ip,
        countryLong = country,
        countryShort = country.take(2).uppercase(),
        operator = operator,
        speed = speed,
        ping = 10,
        score = 100,
        tcpPort = 443,
        logType = null,
        message = null,
        openVpnConfigData = null,
    )

    private fun ServerListSearchState.hostNames(): List<String> =
        rows?.mapNotNull { it.hostName } ?: emptyList()

    // ----- Regression: the identity failure that broke search -----------

    /**
     * The core regression. The shared model is the *same instance* before and
     * after filtering (filtering mutates it in place and returns `this`), so
     * the previous design - storing that instance in Compose state - published
     * an identical state value and the list never recomposed. The displayed
     * snapshot must instead be a different value holding the filtered rows.
     */
    @Test
    fun `search changes the displayed snapshot even though the model instance is reused`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        state.onQueryChanged("Japan")
        refresh(state, model, all)

        val filteredRows = state.rows
        assertEquals("only Japan must remain", listOf("public-vpn-1.opengw.net"), state.hostNames())
        assertEquals(1, state.rowCount)

        // Re-publishing the very same model must still surface a new value.
        state.onQueryChanged("Germany")
        refresh(state, model, all)

        assertEquals(listOf("public-vpn-3.opengw.net"), state.hostNames())
        assertNotSame(
            "a re-filtered result must be a new state value, not the same instance",
            filteredRows,
            state.rows,
        )
    }

    @Test
    fun `published rows are a copy and not the shared model`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList().fromVPNGateItems(all)
        state.publish(model)

        val published = state.rows
        assertEquals(3, published!!.size)

        // Mutating the shared model afterwards must not rewrite the display.
        model.clear()
        assertEquals("displayed rows are a snapshot", 3, state.rows!!.size)
        assertNotSame(model, state.rows)
    }

    // ----- Search behaviour ----------------------------------------------

    @Test
    fun `search by country name returns only matching rows`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        state.onQueryChanged("Japan")
        refresh(state, model, all)

        assertEquals(listOf("public-vpn-1.opengw.net"), state.hostNames())
    }

    @Test
    fun `search also matches hostname operator and ip`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        state.onQueryChanged("128.199")
        refresh(state, model, all)
        assertEquals(listOf("public-vpn-2.opengw.net"), state.hostNames())

        state.onQueryChanged("Deutsche")
        refresh(state, model, all)
        assertEquals(listOf("public-vpn-3.opengw.net"), state.hostNames())

        state.onQueryChanged("public-vpn-1")
        refresh(state, model, all)
        assertEquals(listOf("public-vpn-1.opengw.net"), state.hostNames())
    }

    @Test
    fun `empty search behaves as the unfiltered list`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        state.onQueryChanged("Japan")
        refresh(state, model, all)
        assertEquals(1, state.rowCount)

        state.onQueryChanged("")
        refresh(state, model, all)

        assertEquals(3, state.rowCount)
        assertEquals(
            listOf("public-vpn-1.opengw.net", "public-vpn-2.opengw.net", "public-vpn-3.opengw.net"),
            state.hostNames(),
        )
    }

    @Test
    fun `clearing the search restores the full list`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        state.onQueryChanged("Germany")
        refresh(state, model, all)
        assertEquals(1, state.rowCount)

        state.closeSearch()
        refresh(state, model, all)

        assertEquals(3, state.rowCount)
    }

    @Test
    fun `search clear search clear cycles work repeatedly`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        repeat(3) {
            state.onQueryChanged("United States")
            refresh(state, model, all)
            assertEquals(1, state.rowCount)
            assertEquals(listOf("public-vpn-2.opengw.net"), state.hostNames())

            state.closeSearch()
            refresh(state, model, all)
            assertEquals(3, state.rowCount)
        }
    }

    @Test
    fun `repeated identical searches are idempotent`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        state.onQueryChanged("Japan")
        refresh(state, model, all)
        val first = state.hostNames()
        repeat(3) {
            refresh(state, model, all)
            assertEquals(first, state.hostNames())
            assertEquals(1, state.rowCount)
        }
    }

    // ----- Keyword lifecycle ---------------------------------------------

    @Test
    fun `re-entering the screen does not retain the previous keyword`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        state.onQueryChanged("37.87")
        refresh(state, model, all)
        assertEquals(1, state.rowCount)

        // Leaving the screen drops the UI state; the shared model must not keep
        // constraining the next query.
        val reopened = ServerListSearchState()
        assertEquals("", reopened.effectiveKeyword())
        refresh(reopened, model, all)

        assertNull("the shared model must not keep a stale keyword", model.mKeyword)
        assertEquals("full list must return", 3, reopened.rowCount)
    }

    @Test
    fun `clearKeyword drops the keyword so advanced queries are not narrowed`() {
        val model = VPNGateConnectionList()
        model.mKeyword = "Japan"
        model.clearKeyword()
        assertNull(model.mKeyword)
    }

    @Test
    fun `effective keyword ignores blank input and closed search`() {
        val state = ServerListSearchState()
        state.onQueryChanged("Japan")
        assertEquals("Japan", state.effectiveKeyword())

        state.onQueryChanged("   ")
        assertEquals("", state.effectiveKeyword())

        state.onQueryChanged("Japan")
        state.closeSearch()
        assertEquals("", state.effectiveKeyword())
    }

    @Test
    fun `query change drives the open state of the search field`() {
        val state = ServerListSearchState()
        assertTrue(!state.isSearching)

        state.onQueryChanged("Ja")
        assertTrue(state.isSearching)
        assertEquals("Ja", state.keyword)

        state.onQueryChanged("")
        assertTrue(!state.isSearching)

        state.onQueryChanged("Japan")
        state.openSearch()
        assertTrue(state.isSearching)
        assertEquals("Japan", state.keyword)
    }

    // ----- Existing sort / advanced filter behaviour ---------------------

    @Test
    fun `advanced filter still narrows the list`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()
        val filter = VPNGateConnectionList.Filter()

        refresh(state, model, all, filter = filter)
        assertEquals(
            "only servers at or above the speed floor remain",
            listOf("public-vpn-1.opengw.net", "public-vpn-2.opengw.net"),
            state.hostNames(),
        )
    }

    @Test
    fun `sorting still orders the displayed rows`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        refresh(state, model, all, sortProperty = VPNGateConnectionList.SortProperty.SPEED, sortType = VPNGateConnectionList.ORDER.ASC)
        assertEquals(
            listOf("public-vpn-3.opengw.net", "public-vpn-2.opengw.net", "public-vpn-1.opengw.net"),
            state.hostNames(),
        )

        refresh(state, model, all, sortProperty = VPNGateConnectionList.SortProperty.SPEED, sortType = VPNGateConnectionList.ORDER.DESC)
        assertEquals(
            listOf("public-vpn-1.opengw.net", "public-vpn-2.opengw.net", "public-vpn-3.opengw.net"),
            state.hostNames(),
        )
    }

    @Test
    fun `search combines with sorting`() {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()

        state.onQueryChanged("opengw")
        refresh(state, model, all, sortProperty = VPNGateConnectionList.SortProperty.COUNTRY)

        assertEquals(3, state.rowCount)
        assertEquals(
            listOf("public-vpn-3.opengw.net", "public-vpn-1.opengw.net", "public-vpn-2.opengw.net"),
            state.hostNames(),
        )
    }

    // ----- Concurrency ----------------------------------------------------

    @Test
    fun `an older slow search cannot overwrite a newer search result`() = runBlocking {
        val state = ServerListSearchState()
        val model = VPNGateConnectionList()
        val scope = CoroutineScope(Dispatchers.Default)

        // Older keystroke: slow, and would publish Japan if it were not cancelled.
        state.startRefresh(scope) {
            delay(400)
            state.publish(model.fromVPNGateItems(listOf(japan)))
        }
        delay(50)

        // Newer keystroke: fast, publishes Germany.
        state.startRefresh(scope) {
            state.publish(model.fromVPNGateItems(listOf(germany)))
        }
        delay(700)

        assertEquals(
            "the newest search must win",
            listOf("public-vpn-3.opengw.net"),
            state.hostNames(),
        )
    }
}