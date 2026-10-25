package vn.unlimit.vpngate.history

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * F.29 §7 — which **physical** network carried a connection attempt.
 *
 * ## The rule
 *
 * Only Wi-Fi and mobile data are ever claimed. Everything else — Ethernet, a
 * hotspot we cannot name, a captive portal, an unreadable `ConnectivityManager`,
 * or a state where both Wi-Fi and cellular are up and nothing proves which one
 * carried the traffic — becomes [NetworkType.UNKNOWN]. `UNKNOWN` is a real
 * bucket with its own counters, not a failure and not a fallback to "Wi-Fi".
 *
 * ## What this deliberately does not do
 *
 * - It never reads the SSID and never asks for location permission. An SSID is a
 *   personal identifier and the app does not need one.
 * - It never treats the VPN transport as the physical network. When this app's
 *   own tunnel is the active network, the classifier looks *through* it at the
 *   remaining physical networks, and answers `UNKNOWN` when that is ambiguous.
 * - It never binds, protects, or reroutes anything. It is a read-only probe.
 * - It never infers the network type from an IP address, a country, an endpoint,
 *   or a connection result.
 *
 * ## Why the pure core exists
 *
 * [classify] takes plain transport sets and returns an enum, so the mapping —
 * the part that can actually be wrong — is covered by JVM tests with no Android
 * framework and no device. [current] is the thin Android read.
 */
enum class TransportKind { WIFI, CELLULAR, OTHER, VPN }

object NetworkTypeClassifier {

    /**
     * Pure classification.
     *
     * @param activeNetworkTransports transports of `ConnectivityManager.activeNetwork`,
     *   or null when there is none.
     * @param physicalNetworkTransports transports of every network that is **not**
     *   a VPN, used only when the active network is a VPN (or absent).
     */
    fun classify(
        activeNetworkTransports: Set<TransportKind>?,
        physicalNetworkTransports: List<Set<TransportKind>>,
    ): NetworkType {
        // The active network is the default route: when it is not a VPN, it is the
        // network the traffic actually used, and that is proof, not a preference.
        if (activeNetworkTransports != null && TransportKind.VPN !in activeNetworkTransports) {
            return ofTransports(activeNetworkTransports)
        }

        // A VPN is active (or there is no active network). Look at the physical
        // networks that remain, and only answer when exactly one kind exists —
        // with both Wi-Fi and cellular up and no way to prove which one carries
        // the tunnel, guessing would be worse than saying UNKNOWN.
        val kinds = physicalNetworkTransports
            .map { transports -> ofTransports(transports) }
            .filter { it != NetworkType.UNKNOWN }
            .toSet()

        return if (kinds.size == 1) kinds.first() else NetworkType.UNKNOWN
    }

    private fun ofTransports(transports: Set<TransportKind>): NetworkType = when {
        TransportKind.WIFI in transports -> NetworkType.WIFI
        TransportKind.CELLULAR in transports -> NetworkType.CELLULAR
        else -> NetworkType.UNKNOWN
    }

    /**
     * The Android read. Never throws: an unavailable service, a revoked
     * permission, or a platform that answers with something unexpected all yield
     * [NetworkType.UNKNOWN].
     *
     * `allNetworks` is deprecated on API 31+ but is the only way to see the
     * physical networks underneath an active VPN without a location permission or
     * a `VpnService` binding. `GlobalVpnTracker.currentPhysicalNetwork()` already
     * uses it for the same reason.
     */
    @Suppress("DEPRECATION")
    fun current(context: Context?): NetworkType {
        val ctx = context ?: return NetworkType.UNKNOWN
        val manager = try {
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (t: Throwable) {
            null
        } ?: return NetworkType.UNKNOWN

        return try {
            val active = manager.activeNetwork
                ?.let { manager.getNetworkCapabilities(it) }
                ?.toTransportKinds()

            val physical = manager.allNetworks.mapNotNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val kinds = caps.toTransportKinds()
                kinds.takeIf { TransportKind.VPN !in it }
            }

            classify(active, physical)
        } catch (t: Throwable) {
            NetworkType.UNKNOWN
        }
    }

    private fun NetworkCapabilities.toTransportKinds(): Set<TransportKind> {
        val kinds = mutableSetOf<TransportKind>()
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) kinds += TransportKind.WIFI
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) kinds += TransportKind.CELLULAR
        if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) kinds += TransportKind.VPN
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        ) {
            kinds += TransportKind.OTHER
        }
        return kinds
    }
}
