package vn.unlimit.vpngate.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Room 11 -> 12: additive per-server OpenVPN download identity columns. */
class Migration11to12Test {
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

    @Test
    fun `migration is exactly 11 to 12`() {
        assertEquals(11, AppDatabase.MIGRATION_11_12.startVersion)
        assertEquals(12, AppDatabase.MIGRATION_11_12.endVersion)
    }

    @Test
    fun `migration adds only the three nullable descriptor columns`() {
        val db = RecordingDatabase()
        AppDatabase.MIGRATION_11_12.migrate(db.asDatabase())
        assertEquals(3, db.statements.size)
        val joined = db.statements.joinToString(" ").filterNot { it.isWhitespace() }
        for (column in listOf("openVpnDownloadSid", "openVpnDownloadHid", "openVpnDownloadHost")) {
            assertTrue(joined.contains("ADDCOLUMN`$column`TEXT"))
        }
        assertTrue(db.statements.all { it.contains("ALTER TABLE `VPNGateItem`") })
        assertTrue(db.statements.none { it.contains("NOT NULL") })
        assertTrue(db.statements.none { it.contains("DROP", ignoreCase = true) })
        assertTrue(db.statements.none { it.contains("DELETE", ignoreCase = true) })
    }
}
