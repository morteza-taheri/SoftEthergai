package vn.unlimit.vpngate.automode

/**
 * Stage 2F-F.28 Fix C — the ownership rule used when an attempt's wait expires
 * but a VPN may in fact be up.
 *
 * ## Why ownership must be checked
 *
 * `awaitTunnel()` is a *notification* wait, not proof that the tunnel is down.
 * It can expire after `Builder.establish()` already succeeded if the `CONNECTED`
 * notification was late or lost. Stage 2F-F.26-ALT reproduced exactly that: a
 * real, traffic-carrying VPN existed while Auto Connect classified the attempt
 * as "Protocol failed" and walked on to the next server.
 *
 * Adopting *any* live VPN as this app's success would be wrong, so the check is
 * scoped by the system's own VPN owner UID. A third-party VPN has a different
 * owner UID and is therefore never mistaken for this application's tunnel.
 *
 * Extracted as a pure function so the rule — including the API-level guard — is
 * unit testable without a `ConnectivityManager`.
 */
internal object OwnedVpnDecision {

    /**
     * `NetworkCapabilities.getOwnerUid()` is API 29+, while the app's `minSdk`
     * is 24. Below that level the owner UID is unavailable, so ownership cannot
     * be established and the conservative answer is `false`: Auto Connect keeps
     * its previous behaviour rather than adopting a VPN it cannot attribute.
     */
    const val MIN_SDK_FOR_OWNER_UID = 29

    /**
     * @param hasVpnTransport whether the network has `TRANSPORT_VPN`.
     * @param ownerUid the network's VPN owner UID, per `getOwnerUid()`.
     * @param sdkInt the device API level.
     * @param myUid this application's UID.
     */
    fun isOwnedByThisApp(
        hasVpnTransport: Boolean,
        ownerUid: Int,
        sdkInt: Int,
        myUid: Int,
    ): Boolean {
        if (!hasVpnTransport) return false
        if (sdkInt < MIN_SDK_FOR_OWNER_UID) return false
        return ownerUid == myUid
    }
}