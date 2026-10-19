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
 * RETENTION-IMPL-PHASE-1 (spec §1.6.31): the 7 -> 8 migration, and the
 * registration invariants around it.
 *
 * Same pure-JVM pattern as `Migration6to7Test`: the migration runs against a
 * recording `SupportSQLiteDatabase` proxy, so no Android runtime is needed. A
 * real device upgrade stays unverified until a device reconnects.
 */
class Migration7to8Test {

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
        RecordingDatabase().also { AppDatabase.MIGRATION_7_8.migrate(it.asDatabase()) }.statements

    // ---------------------------------------------------------------------
    // Bounded migration identity
    // ---------------------------------------------------------------------

    @Test
    fun `the migration is bounded to 7 to 8`() {
        assertEquals(7, AppDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, AppDatabase.MIGRATION_7_8.endVersion)
    }

    // ---------------------------------------------------------------------
    // The three additive retention columns
    // ---------------------------------------------------------------------

    @Test
    fun `the migration adds exactly the three retention columns`() {
        val alters = migratedStatements().filter { it.startsWith("ALTER TABLE") }
        assertEquals(3, alters.size)

        val joined = alters.joinToString(" ") { it }.uppercase()
        // Every new column is present and is NOT NULL DEFAULT 0 so existing
        // rows survive the upgrade untouched.
        assertTrue("firstSeenAt: $joined", joined.contains("FIRSTSEENAT INTEGER NOT NULL DEFAULT 0"))
        assertTrue("lastSeenAt: $joined", joined.contains("LASTSEENAT INTEGER NOT NULL DEFAULT 0"))
        assertTrue("healthScore: $joined", joined.contains("HEALTHSCORE INTEGER NOT NULL DEFAULT 0"))
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
            // Recreating (CREATE TABLE) a tracked table would lose its data.
            // ALTER TABLE ... ADD COLUMN is additive and explicitly allowed on
            // the server table (phase 1 adds three columns to VPNGateItem).
            assertFalse("recreating a table would lose data: $sql", upper.contains("CREATE TABLE"))
        }
    }

    // ---------------------------------------------------------------------
    // Registration invariants — the database must actually be at 8
    // ---------------------------------------------------------------------

    @Test
    fun `the database exposes the vpn gate item dao accessor`() {
        val methods = AppDatabase::class.java.declaredMethods.map { it.name }
        assertTrue(methods.contains("vpnGateItemDao"))
    }

    @Test
    fun `the generated version 8 schema matches the migration DDL`() {
        // KSP exports the schema from the entity definition before the unit
        // tests run, so the export is the runtime-observable proof that the
        // version MIGRATION_7_8 produces is the version Room validates when it
        // opens the database — a mismatch here is exactly what makes Room throw
        // "Migration didn't properly handle" on a real upgrade.
        val schemaFile = File("schemas/vn.unlimit.vpngate.db.AppDatabase/8.json")
        assertTrue(
            "expected the exported schema at ${schemaFile.absolutePath}",
            schemaFile.isFile,
        )

        val schema = schemaFile.readText().filterNot { it.isWhitespace() }
        assertTrue(schema.contains("\"version\":8"))
        // The schema createSql carries backticks around column names.
        assertTrue("exported schema missing firstSeenAt", schema.contains("`firstSeenAt`INTEGERNOTNULLDEFAULT0"))
        assertTrue("exported schema missing lastSeenAt", schema.contains("`lastSeenAt`INTEGERNOTNULLDEFAULT0"))
        assertTrue("exported schema missing healthScore", schema.contains("`healthScore`INTEGERNOTNULLDEFAULT0"))

        // The migration itself writes the same columns, without backticks.
        val ddl = migratedStatements().joinToString(" ").filterNot { it.isWhitespace() }
        assertTrue("migration DDL missing firstSeenAt", ddl.contains("firstSeenAtINTEGERNOTNULLDEFAULT0"))
        assertTrue("migration DDL missing lastSeenAt", ddl.contains("lastSeenAtINTEGERNOTNULLDEFAULT0"))
        assertTrue("migration DDL missing healthScore", ddl.contains("healthScoreINTEGERNOTNULLDEFAULT0"))
    }
}
