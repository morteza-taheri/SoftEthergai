package vn.unlimit.softether.security

import android.content.Context
import android.util.Log

/**
 * SharedPreferences-backed [ServerFingerprintStore] (SEC-01, TOFU pinning).
 *
 * Persists one entry per endpoint: `key = ServerKey.canonical`, `value = lowercase-hex SHA-256
 * fingerprint`. This is the simplest store the design allows (§2.3 "a small persistent store");
 * a JSON blob or Room are not required and add no value for a handful of 64-char strings.
 *
 * Deliberately plain SharedPreferences (not `EncryptedSharedPreferences`): a certificate
 * fingerprint is *public* data — it is derived from the certificate the server already sends in
 * the clear on every handshake. Encrypting it would add a keystore dependency without protecting
 * any secret. The design document does not require encryption.
 *
 * The TOFU decision itself is delegated to the pure [tofuVerify] rule, so this class is a thin
 * persistence adapter and cannot drift from the tested logic.
 */
class ServerFingerprintStoreAndroid(context: Context) : ServerFingerprintStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lookup(key: ServerKey): String? = prefs.getString(key.canonical, null)

    override fun store(key: ServerKey, fingerprintHex: String) {
        prefs.edit()
            .putString(key.canonical, fingerprintHex.trim().lowercase())
            .apply()
    }

    override fun verify(key: ServerKey, fingerprintHex: String): VerifyResult {
        val result = tofuVerify(lookup(key), fingerprintHex)
        if (result == VerifyResult.FIRST_USE) {
            store(key, fingerprintHex)
            Log.i(TAG, "Stored first-use fingerprint for ${key.canonical}")
        }
        return result
    }

    override fun clear(key: ServerKey) {
        prefs.edit().remove(key.canonical).apply()
        Log.i(TAG, "Cleared fingerprint pin for ${key.canonical}")
    }

    companion object {
        private const val TAG = "ServerFingerprintStore"

        /** SharedPreferences file name (private to the app). */
        const val PREFS_NAME = "tls_server_fingerprints"
    }
}
