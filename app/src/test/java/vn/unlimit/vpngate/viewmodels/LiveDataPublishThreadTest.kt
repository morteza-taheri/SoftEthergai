package vn.unlimit.vpngate.viewmodels

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.0.59 (§1.6.58) PART A — the LiveData-thread regression guard.
 *
 * ## The crash
 *
 * `MutableLiveData.setValue` asserts the main thread. The CSV-import path
 * (v1.0.54) assigned `.value` from inside `withContext(Dispatchers.IO)` at two
 * sites — `materializeImported()` (after an import) and
 * `removeImportedServer()` (after a delete) — so the data work completed and
 * then the app died with
 * `IllegalStateException: Cannot invoke setValue on a background thread`.
 * Reproduced on device `5585fecc` at 16:07:13 and 16:09:54.
 *
 * ## How this test catches it
 *
 * Two halves, because neither alone is enough:
 *
 * 1. **A real thread-asserting executor.** `ArchTaskExecutor` is replaced with
 *    a delegate whose `isMainThread()` is true only for one designated thread.
 *    That reproduces the platform check faithfully, so `setValue` off that
 *    thread throws here exactly as it did on the device — proven by
 *    [setValue off the designated main thread throws].
 * 2. **The fix is exercised, not just described.**
 *    [publishLiveData hops to the main dispatcher] calls the real
 *    [publishLiveData] from a background thread and asserts the value landed —
 *    which can only happen if the hop moved the write onto the main thread.
 *
 * ## What this test cannot do, and does not pretend to
 *
 * It does **not** construct `ConnectionListViewModel` and drive
 * `importManualCsv()`: `dataUtil` is `App.instance!!.dataUtil!!` with no JVM
 * seam, `viewModelScope` needs an `AndroidViewModel`, and there is no
 * Robolectric and no `kotlinx-coroutines-test` on this classpath — the same
 * constraint documented in `ConnectionListViewModelInitOrderTest`. The
 * source-shape half ([no LiveData is written inside an IO block]) is what
 * connects the two: it fails if any `.value =` on a `MutableLiveData` reappears
 * inside a `withContext(Dispatchers.IO|Default)` block anywhere in
 * `app/src/main`. Its limit is honest and worth stating: it is a text scan, so
 * it cannot see a LiveData write that a helper method hides from it, and it
 * cannot see an assignment written as `setValue(...)`.
 */
class LiveDataPublishThreadTest {

    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: CoroutineDispatcher

    @Volatile
    private var mainThread: Thread? = null

    @Before
    fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-main").also { mainThread = it }
        }
        // Force the thread to exist so `mainThread` is set before any assertion.
        mainExecutor.submit { }.get()
        mainDispatcher = mainExecutor.asCoroutineDispatcher()

        ArchTaskExecutor.getInstance().setDelegate(
            object : TaskExecutor() {
                override fun executeOnDiskIO(runnable: Runnable) {
                    Thread(runnable, "test-io").start()
                }

                override fun postToMainThread(runnable: Runnable) {
                    mainExecutor.execute(runnable)
                }

                override fun isMainThread(): Boolean = Thread.currentThread() === mainThread
            },
        )
    }

    @After
    fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
        mainExecutor.shutdownNow()
    }

    // ------------------------------------------------- the platform contract

    @Test
    fun `setValue off the designated main thread throws`() {
        val live = MutableLiveData<Int>()

        val thrown = runCatching { live.value = 1 }.exceptionOrNull()

        assertTrue(
            "the harness must reproduce the platform check, otherwise every other " +
                "assertion in this file is vacuous; got $thrown",
            thrown is IllegalStateException,
        )
        assertTrue(
            "unexpected message: ${thrown?.message}",
            thrown?.message.orEmpty().contains("background thread"),
        )
    }

    @Test
    fun `setValue on the designated main thread is accepted`() {
        val live = MutableLiveData<Int>()

        mainExecutor.submit { live.value = 2 }.get()

        assertEquals(2, live.value)
    }

    // ------------------------------------------------------------- the fix

    @Test
    fun `publishLiveData hops to the main dispatcher`() = runBlocking {
        val live = MutableLiveData<Int>()
        assertNotSame(
            "the test body must NOT already be on the designated main thread, " +
                "or this proves nothing",
            mainThread,
            Thread.currentThread(),
        )

        publishLiveData(live, 7, mainDispatcher)

        assertEquals(7, live.value)
    }

    @Test
    fun `publishLiveData has finished the write when it returns`() = runBlocking {
        // The ordering guarantee the callers rely on: `importManualCsv` returns
        // the parse result to a dialog immediately after publishing, so the
        // list must already be published. `postValue` would not guarantee this.
        val live = MutableLiveData<String>()

        publishLiveData(live, "published", mainDispatcher)

        assertEquals("published", live.value)
    }

    @Test
    fun `publishLiveData propagates a failure instead of swallowing it`() = runBlocking {
        // A closed executor must surface as an exception, not as a silently
        // dropped publish — the caller's summary dialog depends on the write.
        val live = MutableLiveData<Int>()
        val doomed = Executors.newSingleThreadExecutor()
        val doomedDispatcher = doomed.asCoroutineDispatcher()
        doomed.shutdownNow()

        val thrown = runCatching { publishLiveData(live, 1, doomedDispatcher) }.exceptionOrNull()

        assertTrue("expected the hop to fail loudly, got $thrown", thrown != null)
    }

    // --------------------------------------------------- the source-shape half

    @Test
    fun `no LiveData is written inside an IO block`() {
        val root = File("src/main/java")
        assertTrue("source root not found at ${root.absolutePath}", root.isDirectory)

        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file -> violations += violationsIn(file) }

        assertEquals(
            "a MutableLiveData `.value =` inside withContext(Dispatchers.IO/Default) " +
                "throws IllegalStateException on a real device (v1.0.59 PART A). " +
                "Use publishLiveData(...) instead. Offenders:\n" +
                violations.joinToString("\n"),
            emptyList<String>(),
            violations,
        )
    }

    @Test
    fun `the two import paths publish through the helper`() {
        val source = File("src/main/java/vn/unlimit/vpngate/viewmodels/ConnectionListViewModel.kt")
        assertTrue("source not found at ${source.absolutePath}", source.isFile)
        val text = source.readText(Charsets.UTF_8)

        val materialize = text.substringAfter("private suspend fun materializeImported()")
            .substringBefore("\n    }")
        assertTrue(
            "materializeImported must publish through publishLiveData",
            materialize.contains("publishLiveData(vpnGateConnectionList"),
        )

        val remove = text.substringAfter("fun removeImportedServer(hostName: String)")
            .substringBefore("\n    }")
        assertTrue(
            "removeImportedServer must publish through publishLiveData",
            remove.contains("publishLiveData("),
        )
    }

    // ------------------------------------------------------------- the scan

    /**
     * The file with every comment and string literal blanked to spaces.
     *
     * Offsets and newlines are preserved, so a match found here maps to the
     * same line in the original. This is what makes the scan correct rather
     * than approximately correct: the first version searched the raw text, and
     * a KDoc line that merely *mentioned* `withContext(Dispatchers.IO)` was
     * taken for a real block, so its brace match ran to the end of the
     * enclosing function and reported four `.value =` assignments as offenders
     * when every one of them sits after its block.
     */
    private fun codeOnly(text: String): String {
        val out = text.toCharArray()
        var i = 0
        while (i < out.size) {
            val c = text[i]
            when {
                c == '/' && i + 1 < out.size && text[i + 1] == '/' -> {
                    while (i < out.size && text[i] != '\n') {
                        out[i] = ' '
                        i++
                    }
                }

                c == '/' && i + 1 < out.size && text[i + 1] == '*' -> {
                    while (i < out.size &&
                        !(text[i] == '*' && i + 1 < out.size && text[i + 1] == '/')
                    ) {
                        if (text[i] != '\n') out[i] = ' '
                        i++
                    }
                    if (i < out.size) {
                        out[i] = ' '
                        if (i + 1 < out.size) out[i + 1] = ' '
                        i += 2
                    }
                }

                c == '"' && text.startsWith("\"\"\"", i) -> {
                    repeat(3) { k -> if (i + k < out.size) out[i + k] = ' ' }
                    i += 3
                    while (i < out.size && !text.startsWith("\"\"\"", i)) {
                        if (text[i] != '\n') out[i] = ' '
                        i++
                    }
                    repeat(3) { k -> if (i + k < out.size) out[i + k] = ' ' }
                    i += 3
                }

                c == '"' -> {
                    out[i] = ' '
                    i++
                    while (i < out.size && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < out.size) {
                            out[i] = ' '
                            i++
                        }
                        if (text[i] != '\n') out[i] = ' '
                        i++
                    }
                    if (i < out.size) {
                        out[i] = ' '
                        i++
                    }
                }

                else -> i++
            }
        }
        return String(out)
    }

    /** Every `name.value =` where `name` is a `MutableLiveData` declared in the file, inside an IO block. */
    private fun violationsIn(file: File): List<String> {
        val text = file.readText(Charsets.UTF_8)
        val code = codeOnly(text)
        val liveNames = Regex("""\b(?:val|var)\s+(\w+)\s*[:=][^\n]*MutableLiveData<""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toSet()
        if (liveNames.isEmpty()) return emptyList()

        val spans = ioBlockSpans(code)
        if (spans.isEmpty()) return emptyList()

        val hits = mutableListOf<String>()
        for (name in liveNames) {
            val pattern = Regex("""\b${Regex.escape(name)}\s*\.\s*value\s*=""")
            for (match in pattern.findAll(code)) {
                val inside = spans.any { span -> match.range.first in span }
                if (inside) {
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    hits += "${file.path}:$line  ($name.value =)"
                }
            }
        }
        return hits
    }

    /**
     * Character spans of `withContext(Dispatchers.IO)` / `(Dispatchers.Default)`
     * blocks in [code] (already comment- and string-free), found by brace
     * matching.
     */
    private fun ioBlockSpans(code: String): List<IntRange> {
        val markers = listOf(
            "withContext(Dispatchers.IO)",
            "withContext(Dispatchers.Default)",
            "withContext(kotlinx.coroutines.Dispatchers.IO)",
        )
        val spans = mutableListOf<IntRange>()
        for (marker in markers) {
            var from = code.indexOf(marker)
            while (from >= 0) {
                val open = code.indexOf('{', from)
                if (open < 0) break
                var depth = 0
                var i = open
                var end = code.length - 1
                while (i < code.length) {
                    when (code[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                end = i
                                break
                            }
                        }
                    }
                    i++
                }
                spans += open..end
                from = code.indexOf(marker, open)
            }
        }
        return spans
    }
}
