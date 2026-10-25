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
 * F.29 §12: the 8 -> 9 migration, and the registration invariants around it.
 *
 * Same pure-JVM pattern as `Migration7to8Test`: the migration runs against a
 * recording `SupportSQLiteDatabase` proxy, so no Android runtime is needed.
 * A real device upgrade stays unverified until a device reconnects
 * (NOT TESTABLE — DEVICE UNAVAILABLE).
 *
 * The parity assertion is the load-bearing one: Room validates the *exported*
 * schema on every open, so a migration whose DDL differs from the KSP export
 * by a single column is what produces "Migration didn't properly handle:
 * server_history_summary" on a real upgrade. Comparing the two texts directly
 * — rather than eyeballing them — is what makes that class of bug impossible
 * to ship silently.
 */
class Migration8to9Test {

    /** Records the statements the migration runs, without a database. */
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
        RecordingDatabase().also { AppDatabase.MIGRATION_8_9.migrate(it.asDatabase()) }.statements

    /** Collapse every run of whitespace so line wrapping cannot hide a difference. */
    private fun normalise(sql: String): String = sql.filterNot { it.isWhitespace() }

    // ---------------------------------------------------------------------
    // Bounded migration identity
    // ---------------------------------------------------------------------

    @Test
    fun `the migration is bounded to 8 to 9`() {
        assertEquals(8, AppDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, AppDatabase.MIGRATION_8_9.endVersion)
    }

    // ---------------------------------------------------------------------
    // The three new tables and their four indexes
    // ---------------------------------------------------------------------

    @Test
    fun `the migration creates exactly the three history tables and four indexes`() {
        val statements = migratedStatements()
        val tables = statements.filter { it.startsWith("CREATE TABLE") }
        val indexes = statements.filter { it.startsWith("CREATE INDEX") }

        assertEquals("tables: $tables", 3, tables.size)
        assertEquals("indexes: $indexes", 4, indexes.size)
        // No ALTER, no UPDATE, no backfill (F.29 §12: no backfill).
        assertEquals(0, statements.count { it.startsWith("ALTER TABLE") })
        assertEquals(0, statements.count { it.startsWith("UPDATE") })

        val joined = normalise(statements.joinToString(" "))
        assertTrue(joined.contains("`server_history_summary`".filterNot { it.isWhitespace() }))
        assertTrue(joined.contains("`server_history_protocol`".filterNot { it.isWhitespace() }))
        assertTrue(joined.contains("`server_history_event`".filterNot { it.isWhitespace() }))
    }

    @Test
    fun `no counter carries a SQL DEFAULT clause`() {
        // Room derives NOT NULL from Kotlin nullability and applies Kotlin
        // defaults in the constructor, not in SQLite. A SQL DEFAULT here would
        // make the DDL disagree with the KSP export and fail validation.
        for (sql in migratedStatements()) {
            assertFalse("unexpected DEFAULT: $sql", sql.contains("DEFAULT"))
        }
    }

    @Test
    fun `the event table is the only autoincrement table`() {
        val tables = migratedStatements().filter { it.startsWith("CREATE TABLE") }
        val withAutoincrement = tables.filter { it.contains("AUTOINCREMENT") }
        assertEquals(1, withAutoincrement.size)
        assertTrue(withAutoincrement.single().contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        // The summary is keyed by the server identity, not a synthetic id.
        assertTrue(tables.single { it.contains("server_history_summary") }.contains("PRIMARY KEY(`hostName`)"))
        // The protocol table is keyed by the pair.
        assertTrue(tables.single { it.contains("server_history_protocol") }.contains("PRIMARY KEY(`hostName`, `protocol`)"))
    }

    // ---------------------------------------------------------------------
    // Non-destructiveness — pre-existing data must survive
    // ---------------------------------------------------------------------

    @Test
    fun `the migration never drops deletes or recreates an existing table`() {
        for (sql in migratedStatements()) {
            val upper = sql.uppercase()
            assertFalse("destructive: $sql", upper.contains("DROP"))
            assertFalse("destructive: $sql", upper.contains("DELETE"))
            assertFalse("destructive: $sql", upper.contains("TRUNCATE"))
            // Recreating a table that already exists would lose its rows. Every
            // CREATE here is IF NOT EXISTS on a brand-new table.
            assertFalse("would recreate: $sql", upper.contains("CREATE TABLE") && !upper.contains("IF NOT EXISTS"))
        }
    }

    @Test
    fun `the migration declares no foreign key`() {
        // A cascade on VPNGateItem.hostName would delete the whole history the
        // moment replaceAll() ran its deleteAll().
        for (sql in migratedStatements()) {
            assertFalse("foreign key would cascade: $sql", sql.uppercase().contains("FOREIGN KEY"))
        }
    }

    // ---------------------------------------------------------------------
    // Registration invariants
    // ---------------------------------------------------------------------

    @Test
    fun `the database exposes the server history dao accessor`() {
        val methods = AppDatabase::class.java.declaredMethods.map { it.name }
        assertTrue(methods.contains("serverHistoryDao"))
    }

    // ---------------------------------------------------------------------
    // Schema parity — the migration DDL IS the exported schema
    // ---------------------------------------------------------------------

    /**
     * Every `createSql` the export declares, with Room's `${TABLE_NAME}`
     * placeholder resolved, in file order.
     *
     * Hand-extracted rather than parsed with `org.json` on purpose: the unit
     * test source set declares no JSON dependency, and adding one to prove a
     * schema is self-consistent would be a strange trade. The field this needs
     * is a flat string whose value never contains an escaped quote, so a
     * bounded extraction is exact for this file.
     */
    private fun exportedCreateSql(): List<String> {
        val text = File("schemas/vn.unlimit.vpngate.db.AppDatabase/9.json").readText()
        val out = mutableListOf<String>()
        // The tableName of every entity, in file order; only the history ones
        // are of interest.
        val tableNames = Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        val historyTables = tableNames.filter { it.contains("history") }.toSet()
        // Walk the text, tracking the most recent history table name seen.
        val combined = Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"|\"createSql\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        var currentTable: String? = null
        for (m in combined.findAll(text)) {
            val named = m.groupValues[1]
            if (named.isNotEmpty()) {
                currentTable = if (named in historyTables) named else null
                continue
            }
            val sql = m.groupValues[2]
            if (sql.isEmpty()) continue
            // A table's createSql always follows its tableName; an index's
            // createSql follows its own "name" field inside the same entity.
            val resolved = currentTable ?: continue
            out += sql.replace("\${TABLE_NAME}", resolved)
        }
        return out
    }

    /** Every `name` the export declares on a history table's index. */
    private fun exportedIndexNames(): Set<String> {
        val text = File("schemas/vn.unlimit.vpngate.db.AppDatabase/9.json").readText()
        val names = Regex("\"name\"\\s*:\\s*\"(index_server_history_[a-zA-Z_]+)\"").findAll(text)
        return names.map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `the generated version 9 schema matches the migration DDL exactly`() {
        // KSP exports the schema from the entity definition, so the export is
        // the runtime-observable proof that the version MIGRATION_8_9 produces
        // is the version Room validates when it opens the database.
        val schemaFile = File("schemas/vn.unlimit.vpngate.db.AppDatabase/9.json")
        assertTrue(
            "expected the exported schema at ${schemaFile.absolutePath}",
            schemaFile.isFile,
        )

        val schema = schemaFile.readText().filterNot { it.isWhitespace() }
        assertTrue(schema.contains("\"version\":9"))

        val migrationDdl = normalise(migratedStatements().joinToString(" "))
        val expected = exportedCreateSql()

        // 3 tables + 4 indexes.
        assertEquals("schema objects found in 9.json: $expected", 7, expected.size)
        for (sql in expected) {
            assertTrue(
                "migration DDL does not contain the exported schema statement:\n$sql",
                migrationDdl.contains(normalise(sql)),
            )
        }
    }

    @Test
    fun `every index the migration creates is one the export declares`() {
        val exportNames = exportedIndexNames()
        assertEquals(4, exportNames.size)

        val createdNames = migratedStatements()
            .filter { it.startsWith("CREATE INDEX") }
            .map { it.substringAfter("IF NOT EXISTS ").substringBefore(" ").trim('`') }
            .toSet()
        assertEquals("index names created by the migration", exportNames, createdNames)
    }
}
