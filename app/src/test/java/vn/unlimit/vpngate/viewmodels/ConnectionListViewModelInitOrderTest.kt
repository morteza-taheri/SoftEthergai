package vn.unlimit.vpngate.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.0.57 (§1.6.55) — the launch-crash regression guard.
 *
 * ## What went wrong
 *
 * `ConnectionListViewModel` had its `init` block at the top of the class
 * (line 45) while `_importedServers` was declared ~220 lines below (line 268).
 * Kotlin initialises properties **in declaration order**, so an `init` block
 * runs *between* the declarations around it. `viewModelScope` uses
 * `Dispatchers.Main.immediate`, which executes a coroutine body
 * **synchronously up to its first suspension point**, so the
 * `viewModelScope.launch` issued from `init` reached
 * `_importedServers.value = …` before the field was assigned — a
 * `NullPointerException` on `MutableStateFlow.setValue`, and the app died on
 * launch. It shipped in `ec3a73b` (v1.0.54) and was found only on a physical
 * device in BLOCK v1.0.56.
 *
 * ## Why this is a source-shape test
 *
 * The JVM suite cannot construct this class: `dataUtil` is
 * `App.instance!!.dataUtil!!` (a non-null assertion on a process singleton
 * with no JVM seam) and `viewModelScope` needs an `AndroidViewModel`. There is
 * no Robolectric and no `kotlinx-coroutines-test` on the classpath, and adding
 * either for one ordering bug was judged the wrong trade.
 *
 * So the invariant is pinned directly on the source text: **the `init` block
 * must appear after every property declaration in the class.** That is exactly
 * the condition that makes the crash impossible, it is cheap, and it is the
 * same pattern `AutoModeControllerTest` already uses for the armed-loss
 * funnel and `DefaultExcludedAppsTest` uses for the shipped asset.
 *
 * A test that constructs the ViewModel and drives `refreshImportedServers()`
 * under an immediate dispatcher would be strictly better, and is the right
 * follow-up once the class gains a test seam (or Robolectric lands). This test
 * is deliberately honest about being the weaker guard rather than pretending
 * to be the stronger one.
 */
class ConnectionListViewModelInitOrderTest {

    private val source: String by lazy {
        val file = File("src/main/java/vn/unlimit/vpngate/viewmodels/ConnectionListViewModel.kt")
        assertTrue("source not found at ${file.absolutePath}", file.isFile)
        file.readText(Charsets.UTF_8)
    }

    /** Property declarations that matter for constructor ordering. */
    private fun propertyLines(): List<Pair<Int, String>> =
        source.lineSequence().withIndex().mapNotNull { (idx, line) ->
            // Class-level members are indented exactly four spaces. Local `val`s
            // inside function bodies are indented deeper, and a line inside a
            // KDoc or a string literal must not be mistaken for a declaration.
            if (!line.startsWith("    ") || line.startsWith("        ")) return@mapNotNull null
            val trimmed = line.trimStart()
            val isProp = trimmed.startsWith("private val ") ||
                trimmed.startsWith("private var ") ||
                trimmed.startsWith("internal val ") ||
                trimmed.startsWith("val ") ||
                trimmed.startsWith("var ")
            if (!isProp) null else (idx + 1) to trimmed
        }.toList()

    @Test
    fun `the init block appears after every property declaration`() {
        val initLine = source.lineSequence().withIndex()
            .firstOrNull { (_, line) -> line.startsWith("    init {") }
            ?.index
            ?: fail("no init block found in ConnectionListViewModel")

        val props = propertyLines()
        assertTrue("no property declarations found — the scan is broken", props.isNotEmpty())

        val after = props.filter { (lineNo, _) -> lineNo > initLine + 1 }
        assertEquals(
            "every property must be declared ABOVE the init block, because an " +
                "init block runs between the declarations around it and a " +
                "viewModelScope.launch issued from it can otherwise touch a " +
                "still-null field. Offending declarations: " +
                after.joinToString { "line ${it.first}: ${it.second.take(60)}" },
            emptyList<Pair<Int, String>>(),
            after,
        )
    }

    @Test
    fun `the init block still loads the imported servers`() {
        // The move must not have dropped the work the init block exists to do.
        val initBody = source.substringAfter("    init {").substringBefore("    }")
        assertTrue(
            "init must still seed the connection cache",
            initBody.contains("vpnGateConnectionList.value = dataUtil.connectionsCache"),
        )
        assertTrue(
            "init must still seed the last-updated timestamp",
            initBody.contains("lastUpdatedTime.value = dataUtil.connectionCacheUpdatedAt"),
        )
        assertTrue(
            "init must still load the imported CSV rows",
            initBody.contains("refreshImportedServers()"),
        )
    }

    @Test
    fun `refreshImportedServers still writes the imported flow`() {
        // The write that crashed must still exist — the fix moved the init
        // block, it did not change what the coroutine does.
        val body = source.substringAfter("fun refreshImportedServers()")
        assertTrue(
            "refreshImportedServers must still publish into _importedServers",
            body.contains("_importedServers.value ="),
        )
    }

    @Test
    fun `the crash site carries a comment explaining the ordering`() {
        // So the next person who wants to move the init block back up (or add
        // a property below it) finds out why they cannot.
        val initBody = source.substringAfter("    init {").substringBefore("    }")
        assertTrue(
            "the init block must document the ordering constraint",
            initBody.contains("Dispatchers.Main.immediate"),
        )
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
