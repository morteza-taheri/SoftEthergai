package vn.unlimit.vpngate.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * BLOCK v1.0.69 PART 1 — the 9 -> 10 additive history migration.
 *
 * Same pure-JVM recording-proxy pattern as [Migration8to9Test]: the migration
 * runs against a recording [SupportSQLiteDatabase] proxy, so no Android runtime
 * and no real device database is required. The parity assertions compare the
 * ALTER TABLE DDL against the KSP-exported `10.json`, which is the schema Room
 * validates when it opens the database — a migration whose DDL disagrees with
 * the export by one column is exactly what produces "Migration didn't properly
 * handle" on a real upgrade.
 *
 * The genuine historical schema is the repository's exported `9.json`; this test
 * proves the 9 -> 10 delta is exactly the five approved columns and that the
 * migration never drops, deletes, recreates or backfills.
 *
 * LIMITATION (BLOCK v1.0.69 PART 1 review): this is a **SQL-contract + schema-parity**
 * test. It records the ALTER statements and compares them with the exported `10.json`;
 * it does NOT open a real v9 SQLite database, does NOT execute the migration against
 * rows, and does NOT run Room's own schema validation. A real SQLite-backed migration
 * test would require a dependency (room-testing / sqlite-jdbc / Robolectric) that this
 * unit-test source set does not declare — adding one needs operator approval.
 */
class Migration9to10Test {

    private class RecordingDatabase : InvocationHandler {
        val statements = mutableListOf<String>()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? = when (method.name) {
            "execSQL" -> {
                statements.add(args!![0] as String)
                null
            }
            "toString" -> "RecordingDatabase"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args!![0]
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                String::class.java -> ""
                else -> null
            }
        }

        fun asDatabase(): SupportSQLiteDatabase =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                this,
            ) as SupportSQLiteDatabase
    }

    private fun migratedStatements(): List<String> =
        RecordingDatabase().also { AppDatabase.MIGRATION_9_10.migrate(it.asDatabase()) }.statements

    private fun normalise(sql: String): String = sql.filterNot { it.isWhitespace() }

    private fun schemaText(version: Int): String =
        File("schemas/vn.unlimit.vpngate.db.AppDatabase/$version.json").readText()

    private val counters = listOf(
        "wifiConsecutiveFailures",
        "cellularConsecutiveFailures",
        "unknownConsecutiveFailures",
        // BLOCK v1.0.75 PART C (D-8), folded into version 10.
        "missedRefreshCount",
    )
    private val timestamps = listOf(
        "lastRefreshSeenAt",
        "firstListedAt",
        // BLOCK v1.0.75 PART C (D-7), folded into version 10.
        "wifiLastFailureAt",
        "cellularLastFailureAt",
        "unknownLastFailureAt",
    )

    // ---------------------------------------------------------------------
    // Bounded migration identity
    // ---------------------------------------------------------------------

    @Test
    fun `the migration is bounded to 9 to 10`() {
        assertEquals(9, AppDatabase.MIGRATION_9_10.startVersion)
        assertEquals(10, AppDatabase.MIGRATION_9_10.endVersion)
    }

    // ---------------------------------------------------------------------
    // Exactly five additive columns, nothing else
    // ---------------------------------------------------------------------

    @Test
    fun `the migration adds exactly the five approved columns and nothing else`() {
        val statements = migratedStatements()
        val alters = statements.filter { it.startsWith("ALTER TABLE") }
        assertEquals("alters: $alters", 9, alters.size)
        assertEquals(0, statements.count { it.uppercase().contains("DROP") })
        assertEquals(0, statements.count { it.uppercase().contains("DELETE") })
        assertEquals(0, statements.count { it.uppercase().contains("TRUNCATE") })
        assertEquals(0, statements.count { it.startsWith("CREATE TABLE") })
        assertEquals(0, statements.count { it.startsWith("UPDATE") })

        val joined = normalise(statements.joinToString(" "))
        for (col in counters + timestamps) {
            assertTrue("missing column $col", joined.contains("ADDCOLUMN`$col`"))
        }
        for (sql in alters) {
            assertTrue("every ALTER targets server_history_summary: $sql", sql.contains("server_history_summary"))
        }
    }

    @Test
    fun `the counters are NOT NULL DEFAULT 0 and the timestamps are nullable without a default`() {
        val byColumn = migratedStatements().associateBy { sql ->
            Regex("ADD COLUMN `([A-Za-z]+)`").find(sql)?.groupValues?.get(1) ?: ""
        }
        for (col in counters) {
            val sql = byColumn.getValue(col).uppercase()
            assertTrue("$col must be NOT NULL: $sql", sql.contains("NOT NULL"))
            assertTrue("$col must DEFAULT 0: $sql", sql.contains("DEFAULT 0"))
        }
        for (col in timestamps) {
            val sql = byColumn.getValue(col).uppercase()
            assertTrue("$col must be INTEGER: $sql", sql.contains("INTEGER"))
            assertFalse("$col must be nullable: $sql", sql.contains("NOT NULL"))
            assertFalse("$col must not be backfilled: $sql", sql.contains("DEFAULT"))
        }
    }

    @Test
    fun `the migration never drops deletes or recreates a table`() {
        for (sql in migratedStatements()) {
            val upper = sql.uppercase()
            assertFalse("destructive: $sql", upper.contains("DROP"))
            assertFalse("destructive: $sql", upper.contains("DELETE"))
            assertFalse("destructive: $sql", upper.contains("TRUNCATE"))
            assertFalse("would recreate: $sql", upper.contains("CREATE TABLE"))
        }
    }

    // ---------------------------------------------------------------------
    // Schema parity with the exported 10.json (and 9.json has none of them)
    // ---------------------------------------------------------------------

    @Test
    fun `the new columns exist in 10 json and are absent from 9 json`() {
        // declaresField() matches whitespace-free JSON, so normalise both sides.
        val nine = schemaText(9).filterNot { it.isWhitespace() }
        val ten = schemaText(10).filterNot { it.isWhitespace() }
        assertTrue(ten.contains("\"version\":10"))
        for (col in counters + timestamps) {
            assertTrue("10.json must declare $col", declaresField(ten, col))
            assertFalse("9.json must not declare $col", declaresField(nine, col))
        }
    }

    @Test
    fun `10 json declares the counters NOT NULL DEFAULT 0 and the timestamps nullable`() {
        val ten = schemaText(10).filterNot { it.isWhitespace() }
        for (col in counters) {
            val obj = fieldObject(ten, col)
            assertTrue("$col affinity: $obj", obj.contains("\"affinity\":\"INTEGER\""))
            assertTrue("$col notNull: $obj", obj.contains("\"notNull\":true"))
            assertTrue("$col defaultValue: $obj", obj.contains("\"defaultValue\":\"0\"") || obj.contains("\"defaultValue\":0"))
        }
        for (col in timestamps) {
            val obj = fieldObject(ten, col)
            assertTrue("$col affinity: $obj", obj.contains("\"affinity\":\"INTEGER\""))
            assertFalse("$col notNull: $obj", obj.contains("\"notNull\":true"))
            assertFalse("$col defaultValue: $obj", obj.contains("\"defaultValue\""))
        }
    }

    private fun declaresField(whitespaceFreeJson: String, column: String): Boolean =
        whitespaceFreeJson.contains("\"columnName\":\"$column\"") ||
            whitespaceFreeJson.contains("\"fieldPath\":\"$column\"")

    /** Extract the JSON object for a field (from its columnName/fieldPath to the matching `}`). */
    private fun fieldObject(whitespaceFreeJson: String, column: String): String {
        val marker = if (whitespaceFreeJson.contains("\"columnName\":\"$column\"")) {
            "\"columnName\":\"$column\""
        } else {
            "\"fieldPath\":\"$column\""
        }
        val at = whitespaceFreeJson.indexOf(marker)
        assertTrue("column $column not found in 10.json", at >= 0)
        val start = whitespaceFreeJson.lastIndexOf('{', at)
        var depth = 0
        var i = start
        while (i < whitespaceFreeJson.length) {
            when (whitespaceFreeJson[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return whitespaceFreeJson.substring(start, i + 1)
                }
            }
            i++
        }
        return whitespaceFreeJson.substring(start)
    }
}
