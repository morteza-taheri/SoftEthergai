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
 * BLOCK v1.0.75 PART C (D-7/D-8) — the 10 -> 11 additive history migration.
 *
 * Why 10 -> 11 and not a fold into 10: the LIVE device database reports
 * `user_version = 10` (pulled read-only on 2026-10-27 before installing
 * anything), so the Room-10 schema DID ship. Folding the new columns into 10
 * would make Room's identity check fail on every already-upgraded device.
 *
 * Same recording-proxy pattern as [Migration9to10Test]: the migration runs
 * against a recording [SupportSQLiteDatabase] proxy and its DDL is compared with
 * the KSP-exported `11.json`. LIMITATION identical to the 9 -> 10 test: this is a
 * SQL-contract + schema-parity test, not a real SQLite execution.
 */
class Migration10to11Test {

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
        RecordingDatabase().also { AppDatabase.MIGRATION_10_11.migrate(it.asDatabase()) }.statements

    private fun normalise(sql: String): String = sql.filterNot { it.isWhitespace() }

    private fun schemaText(version: Int): String =
        File("schemas/vn.unlimit.vpngate.db.AppDatabase/$version.json").readText()

    /** Declares a field by name in a whitespace-free schema JSON. */
    private fun declaresField(json: String, column: String): Boolean =
        json.contains("\"columnName\":\"$column\"")

    private val counters = listOf("missedRefreshCount")
    private val timestamps = listOf("wifiLastFailureAt", "cellularLastFailureAt", "unknownLastFailureAt")

    @Test
    fun `the migration is bounded to 10 to 11`() {
        assertEquals(10, AppDatabase.MIGRATION_10_11.startVersion)
        assertEquals(11, AppDatabase.MIGRATION_10_11.endVersion)
    }

    @Test
    fun `the migration adds exactly the four approved columns and nothing destructive`() {
        val statements = migratedStatements()
        val alters = statements.filter { it.startsWith("ALTER TABLE") }
        assertEquals("alters: $alters", 4, alters.size)
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
    fun `the counter is NOT NULL DEFAULT 0 and the timestamps are nullable without a default`() {
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
    fun `the four columns exist in 11 json and in neither 10 nor 9 json`() {
        val nine = schemaText(9).filterNot { it.isWhitespace() }
        val ten = schemaText(10).filterNot { it.isWhitespace() }
        val eleven = schemaText(11).filterNot { it.isWhitespace() }
        assertTrue(eleven.contains("\"version\":11"))
        for (col in counters + timestamps) {
            assertTrue("11.json must declare $col", declaresField(eleven, col))
            assertFalse("10.json must not declare $col", declaresField(ten, col))
            assertFalse("9.json must not declare $col", declaresField(nine, col))
        }
    }

    @Test
    fun `11 json declares the counter NOT NULL DEFAULT 0 and the timestamps nullable`() {
        val eleven = schemaText(11).filterNot { it.isWhitespace() }
        for (col in counters) {
            val i = eleven.indexOf("\"columnName\":\"$col\"")
            assertTrue(i >= 0)
            val window = eleven.substring(i, minOf(eleven.length, i + 400))
            assertTrue("$col must be notNull", window.contains("\"notNull\":true"))
            assertTrue("$col must default 0", window.contains("\"defaultValue\":\"0\""))
        }
        for (col in timestamps) {
            val i = eleven.indexOf("\"columnName\":\"$col\"")
            assertTrue(i >= 0)
            val window = eleven.substring(i, minOf(eleven.length, i + 400))
            assertFalse("$col must be nullable", window.contains("\"notNull\":true"))
        }
    }
}
