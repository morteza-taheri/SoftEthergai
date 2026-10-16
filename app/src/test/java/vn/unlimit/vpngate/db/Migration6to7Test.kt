package vn.unlimit.vpngate.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.remote.ManualServerEntity
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Z1-IMPL-D: the 6 -> 7 migration, and the registration invariants around
 * it.
 *
 * Same pure-JVM pattern as `Migration5to6Test` and `SstpMigration4To5Test`:
 * the migration runs against a recording `SupportSQLiteDatabase` proxy, so
 * no Android runtime is needed. A real device upgrade stays unverified until
 * a device reconnects.
 */
class Migration6to7Test {

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
        RecordingDatabase().also { AppDatabase.MIGRATION_6_7.migrate(it.asDatabase()) }.statements

    // ---------------------------------------------------------------------
    // Bounded migration identity
    // ---------------------------------------------------------------------

    @Test
    fun `the migration is bounded to 6 to 7`() {
        assertEquals(6, AppDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, AppDatabase.MIGRATION_6_7.endVersion)
    }

    // ---------------------------------------------------------------------
    // The new table
    // ---------------------------------------------------------------------

    @Test
    fun `the migration creates the manual server table with the expected columns`() {
        val create = migratedStatements().single { it.startsWith("CREATE TABLE") }

        assertTrue(
            "the statement must be a CREATE TABLE, was: $create",
            create.trimStart().startsWith("CREATE TABLE", ignoreCase = true),
        )
        assertTrue("id: $create", create.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue("host: $create", create.contains("`host` TEXT NOT NULL"))
        assertTrue("port: $create", create.contains("`port` INTEGER NOT NULL"))
        assertTrue("protocol: $create", create.contains("`protocol` TEXT NOT NULL"))
        assertTrue("label: $create", create.contains("`label` TEXT"))
        assertTrue("notes: $create", create.contains("`notes` TEXT"))
        assertTrue("enabled: $create", create.contains("`enabled` INTEGER NOT NULL DEFAULT 1"))
        assertTrue("createdAt: $create", create.contains("`createdAt` INTEGER NOT NULL"))
        assertTrue("updatedAt: $create", create.contains("`updatedAt` INTEGER NOT NULL"))
    }

    @Test
    fun `the migration creates the unique index on host port protocol`() {
        val index = migratedStatements().single { it.startsWith("CREATE UNIQUE INDEX") }

        assertTrue(
            "the statement must be a CREATE INDEX, was: $index",
            index.trimStart().startsWith("CREATE UNIQUE INDEX", ignoreCase = true),
        )
        assertTrue("index name: $index", index.contains("`index_manual_server_host_port_protocol`"))
        assertTrue("indexed table: $index", index.contains("ON `manual_server`"))
        assertTrue("columns: $index", index.contains("(`host`, `port`, `protocol`)"))

        // Exactly two statements: the table and the index that makes
        // (host, port, protocol) a set of user decisions rather than a log of
        // taps. A third would be touching tables this migration must not
        // know about.
        assertEquals(2, migratedStatements().size)
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
            // The emergency table arrived in 6 and must be left alone.
            assertFalse("recreating the emergency table would lose data: $sql", upper.contains("EMERGENCY_SOURCE_CONFIG"))
        }
    }

    // ---------------------------------------------------------------------
    // Registration invariants — the database must actually be at 7
    //
    // Every Room annotation (@Database, @Entity, @PrimaryKey, @ColumnInfo) is
    // CLASS-retention — verified with javap on room-common-jvm-2.7.1 — so
    // none of them can be read at test runtime. The observable artifacts are
    // the compiled accessor methods and the KSP-generated schema export,
    // which is what these checks use instead of getAnnotation.
    // ---------------------------------------------------------------------

    @Test
    fun `the database exposes the manual server dao accessor`() {
        val methods = AppDatabase::class.java.declaredMethods.map { it.name }

        assertTrue(methods.contains("manualServerDao"))
        assertTrue(methods.contains("vpnGateItemDao"))
        assertTrue(methods.contains("excludedAppDao"))
        assertTrue(methods.contains("emergencySourceConfigDao"))
    }

    @Test
    fun `the generated version 7 schema matches the migration DDL`() {
        // KSP exports the schema from the entity definition before the unit
        // tests run, so the export is the runtime-observable proof that the
        // version MIGRATION_6_7 produces is the version Room validates when
        // it opens the database — a mismatch here is exactly what makes Room
        // throw "Migration didn't properly handle" on a real upgrade.
        val schemaFile = File("schemas/vn.unlimit.vpngate.db.AppDatabase/7.json")
        assertTrue(
            "expected the exported schema at ${schemaFile.absolutePath}",
            schemaFile.isFile,
        )

        val fragments = listOf(
            "`id`INTEGERPRIMARYKEYAUTOINCREMENTNOTNULL",
            "`host`TEXTNOTNULL",
            "`port`INTEGERNOTNULL",
            "`protocol`TEXTNOTNULL",
            "`label`TEXT",
            "`notes`TEXT",
            "`enabled`INTEGERNOTNULLDEFAULT1",
            "`createdAt`INTEGERNOTNULL",
            "`updatedAt`INTEGERNOTNULL",
        )
        val schema = schemaFile.readText().filterNot { it.isWhitespace() }
        assertTrue(schema.contains("\"version\":7"))
        assertTrue(schema.contains("\"tableName\":\"manual_server\""))
        // The index is part of the schema, not an implementation detail of
        // this migration: Room validates unique indexes too.
        assertTrue(schema.contains("\"name\":\"index_manual_server_host_port_protocol\""))
        assertTrue(schema.contains("\"unique\":true"))
        for (fragment in fragments) {
            assertTrue("exported schema is missing $fragment", schema.contains(fragment))
        }

        // The migration itself must write the very same fragments, with the
        // index folded in as its second statement.
        val ddl = migratedStatements().joinToString(" ").filterNot { it.isWhitespace() }
        for (fragment in fragments) {
            assertTrue("migration DDL is missing $fragment", ddl.contains(fragment))
        }
        assertTrue(
            "migration DDL is missing the unique index",
            ddl.contains("CREATEUNIQUEINDEXIFNOTEXISTS`index_manual_server_host_port_protocol`"),
        )
    }

    @Test
    fun `a freshly entered manual server is enabled and unlabelled by default`() {
        val fresh = ManualServerEntity(
            host = "203.0.113.7",
            port = 443,
            protocol = "sstp",
            createdAt = 1L,
            updatedAt = 1L,
        )

        assertTrue("a new manual server must default to enabled", fresh.enabled)
        assertNull(fresh.label)
        assertNull(fresh.notes)
        assertEquals("the row id is assigned by the database, not the caller", 0L, fresh.id)
    }
}
