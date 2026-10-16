package vn.unlimit.vpngate.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigEntity
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Z1-IMPL-B: the 5 -> 6 migration, and the registration invariants around
 * it.
 *
 * Follows the established pure-JVM pattern of `SstpMigration4To5Test`: the
 * migration is exercised against a recording `SupportSQLiteDatabase`
 * proxy, so no Android runtime is needed. Device-level migration remains
 * unverified until a device reconnects.
 */
class Migration5to6Test {

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
        RecordingDatabase().also { AppDatabase.MIGRATION_5_6.migrate(it.asDatabase()) }.statements

    // ---------------------------------------------------------------------
    // Bounded migration identity
    // ---------------------------------------------------------------------

    @Test
    fun `the migration is bounded to 5 to 6`() {
        assertEquals(5, AppDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, AppDatabase.MIGRATION_5_6.endVersion)
    }

    // ---------------------------------------------------------------------
    // The new table
    // ---------------------------------------------------------------------

    @Test
    fun `the migration creates the emergency source table with the expected columns`() {
        val create = migratedStatements().single { it.contains("emergency_source_config") }

        assertTrue(
            "the statement must be a CREATE TABLE, was: $create",
            create.trimStart().startsWith("CREATE TABLE", ignoreCase = true),
        )
        assertTrue("id: $create", create.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue("url: $create", create.contains("`url` TEXT NOT NULL"))
        assertTrue("label: $create", create.contains("`label` TEXT"))
        assertTrue("enabled: $create", create.contains("`enabled` INTEGER NOT NULL DEFAULT 1"))
        assertTrue("createdAt: $create", create.contains("`createdAt` INTEGER NOT NULL"))
        assertTrue("updatedAt: $create", create.contains("`updatedAt` INTEGER NOT NULL"))
    }

    @Test
    fun `the migration is a single additive statement`() {
        // A fully new table needs exactly one statement; anything more is
        // touching tables this migration must not know about.
        assertEquals(1, migratedStatements().size)
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
            assertFalse("recreating the server table would lose data: $sql", upper.contains("VPNGATEITEM"))
            assertFalse("recreating the exclusion table would lose data: $sql", upper.contains("EXCLUDED_APPS"))
        }
    }

    // ---------------------------------------------------------------------
    // Registration invariants — the database must actually be at 6
    //
    // Every Room annotation (@Database, @Entity, @PrimaryKey, @ColumnInfo) is
    // CLASS-retention — verified with javap on room-common-jvm-2.7.1 — so
    // none of them can be read at test runtime. The observable artifacts are
    // the compiled accessor methods and the KSP-generated schema export,
    // which is what these checks use instead of getAnnotation.
    // ---------------------------------------------------------------------

    @Test
    fun `the database exposes the emergency source dao accessor`() {
        val methods = AppDatabase::class.java.declaredMethods.map { it.name }

        assertTrue(methods.contains("emergencySourceConfigDao"))
        assertTrue(methods.contains("vpnGateItemDao"))
        assertTrue(methods.contains("excludedAppDao"))
    }

    @Test
    fun `the generated version 6 schema matches the migration DDL`() {
        // KSP exports the schema from the entity definition before the unit
        // tests run, so the export is the runtime-observable proof that the
        // version MIGRATION_5_6 produces is the version Room validates when
        // it opens the database — a mismatch here is exactly what makes Room
        // throw "Migration didn't properly handle" on a real upgrade.
        val schemaFile = File("schemas/vn.unlimit.vpngate.db.AppDatabase/6.json")
        assertTrue(
            "expected the exported schema at ${schemaFile.absolutePath}",
            schemaFile.isFile,
        )

        val fragments = listOf(
            "`id`INTEGERPRIMARYKEYAUTOINCREMENTNOTNULL",
            "`url`TEXTNOTNULL",
            "`label`TEXT",
            "`enabled`INTEGERNOTNULLDEFAULT1",
            "`createdAt`INTEGERNOTNULL",
            "`updatedAt`INTEGERNOTNULL",
        )
        val schema = schemaFile.readText().filterNot { it.isWhitespace() }
        assertTrue(schema.contains("\"version\":6"))
        assertTrue(schema.contains("\"tableName\":\"emergency_source_config\""))
        for (fragment in fragments) {
            assertTrue("exported schema is missing $fragment", schema.contains(fragment))
        }

        // The migration itself must write the very same fragments.
        val ddl = migratedStatements().single().filterNot { it.isWhitespace() }
        for (fragment in fragments) {
            assertTrue("migration DDL is missing $fragment", ddl.contains(fragment))
        }
    }

    @Test
    fun `a freshly configured source is enabled and unlabelled by default`() {
        val fresh = EmergencySourceConfigEntity(
            url = "https://emergency.example.com/servers.csv",
            createdAt = 1L,
            updatedAt = 1L,
        )

        assertTrue("a new emergency source must default to enabled", fresh.enabled)
        assertNull(fresh.label)
        assertEquals("the row id is assigned by the database, not the caller", 0, fresh.id)
    }
}
