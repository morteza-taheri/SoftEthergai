package vn.unlimit.vpngate.automode

/**
 * Reads `NetworkCapabilities.getOwnerUid()` **without a direct call site**.
 *
 * `getOwnerUid()` only entered the public SDK in API 30. A compiled call to
 * `caps.ownerUid` therefore fails on Android 10 (API 29) with
 * `java.lang.NoSuchMethodError` — an `Error`, so a `catch (Exception)` never
 * sees it and the foreground process dies. OEM frameworks may also omit the
 * method on newer releases. Reflection turns both cases into a benign `null`
 * instead of a crash.
 *
 * The result is deliberately nullable: when the UID cannot be attributed,
 * [OwnedVpnDecision] must answer `false` rather than adopt a VPN of unknown
 * ownership.
 */
internal object VpnOwnerUidReader {

    private const val METHOD_NAME = "getOwnerUid"

    /**
     * @param candidate a `NetworkCapabilities` instance (typed as [Any] so the
     * reader remains usable in plain JVM tests without an Android framework).
     * @param sdkInt the device API level, supplied by the caller so this object
     * never touches `android.os.Build` in unit tests.
     */
    fun read(candidate: Any, sdkInt: Int): Int? {
        if (sdkInt < OwnedVpnDecision.MIN_SDK_FOR_OWNER_UID) return null
        return runCatching {
            candidate.javaClass.getMethod(METHOD_NAME).invoke(candidate) as? Int
        }.getOrNull()
    }
}
