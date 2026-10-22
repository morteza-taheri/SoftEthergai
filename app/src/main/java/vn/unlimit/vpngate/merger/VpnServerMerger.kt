package vn.unlimit.vpngate.merger

import vn.unlimit.vpngate.data.model.OpenVpnRemote
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnProtocolDefaults
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.parser.OpenVpnConfigParser

/**
 * Conflict-aware multi-source merge — mechanical port of the Python
 * oracle (§11 fill gaps without conflict, §16 mirror authority,
 * §17 api > html > mirror, §38 visible conflicts).
 */
object VpnServerMerger {
    /**
     * Lower number == higher authority. api > html > mirror_*.
     *
     * Z1-IMPL-D (Roadmap §12.5.2 item D) adds `manual` as the *lowest*
     * authority of all, which is the frozen policy, not a new one:
     * `SERVER_SOURCE_ARCHITECTURE.md` §3.3 says "Across sources: canonical
     * (api/html) always wins over emergency over manual", §4.2 says manual
     * records "get the lowest merge priority … never promoted above
     * canonical", and spec §11.11 says a manual record must not be
     * *silently* overwritten by a refresh.
     *
     * Read together those three are consistent, and the number 30 is what
     * encodes it. Preservation is not the same as precedence:
     *
     *  - **Preservation is absolute.** A manual record is never deleted.
     *    `mergeRecords` dedupes by identity and merges; it has no path that
     *    drops a record for being low-priority, and the production write
     *    boundary re-emits the user's own rows on every accepted refresh
     *    (`ServerListUpdates`). A manual server the feeds do not mention
     *    survives untouched and keeps every one of its fields.
     *  - **Precedence applies only to a value both sides claim.** When a
     *    manual entry and a collected record describe the same server and
     *    disagree on a scalar, the collected value wins, because the feed is
     *    evidence about the outside world and a typed string is a belief
     *    about it. The user's value is not discarded: [recordConflict]
     *    writes it into the record's `conflicts` list, which §38 requires —
     *    a disagreement is auditable, never silent.
     *
     * The entry for `manual` is inert until a manual record reaches this
     * object; it exists so the priority table is complete and correct
     * rather than defaulting a user's own server to the same authority as
     * an unidentified mirror.
     */
    private val SOURCE_PRIORITY = mapOf("api" to 0, "html" to 10, "manual" to 30)

    fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>()
            for ((k, v) in value) {
                copy[k as String] = deepCopy(v)
            }
            copy
        }
        is List<*> -> value.map { deepCopy(it) }.toMutableList()
        else -> value
    }

    fun sourcePriority(source: String): Int =
        SOURCE_PRIORITY[Provenance.sourceGroup(source)] ?: 20

    private fun truthy(value: Any?): Boolean = when (value) {
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> !VpnUtil.isFalsyScalar(value)
    }

    /**
     * Record a visible disagreement (§38): conflicts are never
     * silently overwritten; each attempt is auditable.
     */
    fun recordConflict(
        target: VpnServerRecord,
        field: String,
        oldValue: Any?,
        newValue: Any?,
        oldOwner: String,
        newOwner: String,
    ) {
        @Suppress("UNCHECKED_CAST")
        val conflicts = target.getOrPut("conflicts") { mutableListOf<Map<String, Any?>>() }
            as MutableList<MutableMap<String, Any?>>

        for (existing in conflicts) {
            @Suppress("UNCHECKED_CAST")
            val values = existing["values"] as? Map<String, Any?> ?: emptyMap()
            if (existing["field"] == field &&
                values[oldOwner] == oldValue &&
                values[newOwner] == newValue
            ) {
                return
            }
        }

        conflicts.add(
            linkedMapOf(
                "field" to field,
                "values" to linkedMapOf(
                    oldOwner to deepCopy(oldValue),
                    newOwner to deepCopy(newValue),
                ),
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun recursiveMerge(
        target: MutableMap<String, Any?>,
        incoming: Map<String, Any?>,
        path: String = "",
        record: Map<String, Any?>? = null,
        rootTarget: MutableMap<String, Any?>? = null,
    ): MutableMap<String, Any?> {
        val rec = record ?: incoming
        val root = rootTarget ?: target

        for ((key, newValue) in incoming) {
            val curPath = if (path.isEmpty()) key else "$path.$key"

            if (key in listOf("sourceCount", "_owners", "schemaVersion")) continue

            if (key == "sources") {
                for (sourceName in (newValue as? List<String>).orEmpty()) {
                    VpnRecords.sourceAdd(target, sourceName)
                }
                continue
            }

            if (key == "conflicts") {
                for (item in (newValue as? List<Map<String, Any?>>).orEmpty()) {
                    val rootConflicts = root.getOrPut("conflicts") {
                        mutableListOf<Map<String, Any?>>()
                    } as MutableList<Map<String, Any?>>

                    val existingConflict = rootConflicts.firstOrNull {
                        it["field"] == item["field"]
                    }

                    if (existingConflict == null) {
                        rootConflicts.add(deepCopy(item) as Map<String, Any?>)
                    } else {
                        val existingValues = (existingConflict as MutableMap<String, Any?>)
                            .getOrPut("values") { LinkedHashMap<String, Any?>() }
                            as MutableMap<String, Any?>

                        for ((owner, ownerValue) in (item["values"] as? Map<String, Any?>)
                            .orEmpty()) {
                            existingValues[owner] = deepCopy(ownerValue)
                        }
                    }
                }
                continue
            }

            val tgtChild = target[key]

            if (newValue is Map<*, *>) {
                val child: MutableMap<String, Any?> = if (tgtChild is MutableMap<*, *>) {
                    tgtChild as MutableMap<String, Any?>
                } else {
                    val created = LinkedHashMap<String, Any?>()
                    target[key] = created
                    created
                }
                recursiveMerge(child, newValue as Map<String, Any?>, curPath, rec, root)
                continue
            }

            if (newValue is List<*>) {
                if (newValue.isNotEmpty()) {
                    val child: MutableList<Any?> = if (tgtChild is MutableList<*>) {
                        tgtChild as MutableList<Any?>
                    } else {
                        val created = mutableListOf<Any?>()
                        target[key] = created
                        created
                    }
                    for (item in newValue) {
                        if (item !in child) {
                            child.add(deepCopy(item))
                        }
                    }
                }
                continue
            }

            // ---- scalar leaf with provenance-aware conflict handling.
            val oldValue = target[key]

            val oldTruthy = when (oldValue) {
                is Map<*, *> -> oldValue.isNotEmpty()
                is List<*> -> oldValue.isNotEmpty()
                else -> !VpnUtil.isFalsyScalar(oldValue)
            }

            val newScalarTruthy = truthy(newValue)

            if (newScalarTruthy && oldTruthy) {
                val differs = try {
                    oldValue != newValue
                } catch (e: Exception) {
                    true
                }

                if (differs) {
                    val fs = root.getOrPut("fieldSources") {
                        LinkedHashMap<String, MutableList<String>>()
                    } as MutableMap<String, MutableList<String>>

                    val oldSources = fs[curPath].orEmpty().filter { it.isNotEmpty() }
                    val incomingSources = ((rec["fieldSources"]
                        as? Map<String, List<String>>)?.get(curPath)).orEmpty()
                        .filter { it.isNotEmpty() }

                    val oldOwner = oldSources.lastOrNull() ?: "previous"
                    val newOwner = incomingSources.lastOrNull() ?: "incoming"

                    if (sourcePriority(newOwner) < sourcePriority(oldOwner)) {
                        target[key] = deepCopy(newValue)
                    }

                    recordConflict(root, curPath, oldValue, newValue, oldOwner, newOwner)
                }
            } else if (newScalarTruthy && !oldTruthy) {
                // Missing on the current side: fill the gap. No
                // conflict is recorded -- nothing was overwritten (§11).
                target[key] = deepCopy(newValue)
            }
            // Falsy incoming values never erase authoritative data.
        }

        // Merge fieldSources separately (§14): provenance lives at
        // the top level of a record, so this runs once per merge root.
        if (path.isEmpty()) {
            val targetFs = target["fieldSources"] as MutableMap<String, MutableList<String>>
            val recordFs = (rec["fieldSources"] as? Map<String, List<String>>).orEmpty()

            for ((srcPath, srcs) in recordFs) {
                val existing = targetFs.getOrPut(srcPath) { mutableListOf() }
                for (sourceName in srcs) {
                    if (sourceName !in existing) {
                        existing.add(sourceName)
                    }
                }
            }

            target["sourceCount"] = (target["sources"] as List<*>).size
        }

        return target
    }

    /** Dedupe by IP→hostname and merge duplicates (§16/§17). */
    fun mergeRecords(records: List<VpnServerRecord>): List<VpnServerRecord> {
        val database = LinkedHashMap<String, VpnServerRecord>()

        for (record in records) {
            val identity = VpnRecords.identity(record)
            val ip = VpnRecords.str(identity["ip"])
            val host = VpnUtil.normalizeHost(identity["hostname"])

            val key = if (VpnUtil.validIp(ip)) ip else host
            if (key.isEmpty()) continue

            val existing = database[key]
            if (existing == null) {
                @Suppress("UNCHECKED_CAST")
                database[key] = deepCopy(record) as VpnServerRecord
            } else {
                recursiveMerge(existing, record)
            }
        }

        return database.values.toList()
    }

    /**
     * v1.0.53 (§1.6.45) TASK B.3: make the embedded config's endpoint authoritative.
     *
     * ## The rule
     *
     * A record can carry an OpenVPN port from three places: the port inside the
     * embedded `.ovpn` profile (`remote <host> <port>` plus `proto`), an
     * `OpenVPN_TCP_Port` / `OpenVPN_UDP_Port` CSV column, and a `do_openvpn` URL
     * parameter on the HTML page. Only the first is an endpoint this application can
     * actually dial — the app builds its profile from the embedded bytes and never
     * fetches the link. So when the config declares a transport, its port wins, and a
     * disagreement is recorded rather than silently resolved (§38).
     *
     * ## Where this is *not* needed
     *
     * Across sources the existing [SOURCE_PRIORITY] already produces this result: the
     * API parser writes the config's port at `api` authority (0), which beats an
     * `html` (10) URL parameter. And `EmergencyCsvParser.applyOpenVpnColumnPort`
     * resolves the within-row case at the source. This function is the invariant that
     * makes both of those guarantees checkable in one place, so a future parser that
     * assembles a record differently cannot reintroduce the split.
     *
     * ## Shapes handled
     *
     * `configs` holds [vn.unlimit.vpngate.data.model.OpenVpnRemote] instances when a
     * record was just parsed, and plain maps when a record was round-tripped through
     * persistence. Both are read here; anything else is ignored, never guessed at.
     */
    private fun enforceConfigAuthoritativePorts(
        server: VpnServerRecord,
        ov: MutableMap<String, Any?>,
    ) {
        val configs = ov["configs"] as? List<*> ?: return
        if (configs.isEmpty()) return

        for (transportName in listOf("tcp", "udp")) {
            val configPort = configs.firstNotNullOfOrNull { remote ->
                val proto = remoteField(remote, "proto").lowercase()
                val port = VpnUtil.toInt(remoteField(remote, "port"))
                val familyMatches = if (transportName == "tcp") {
                    OpenVpnConfigParser.isTcpFamily(proto)
                } else {
                    OpenVpnConfigParser.isUdpFamily(proto)
                }
                if (familyMatches && VpnUtil.validPort(port)) port else null
            } ?: continue

            val transport = VpnRecords.transport(ov, transportName)
            val current = VpnRecords.int(transport["port"])
            if (current == configPort) continue

            if (VpnUtil.validPort(current)) {
                recordConflict(
                    server,
                    "protocols.openvpn.$transportName.port",
                    current,
                    configPort,
                    "column-or-link",
                    "config",
                )
            }
            transport["port"] = configPort
            transport["portSource"] = VpnPortSource.EXPLICIT
            transport["supported"] = true
        }
    }

    /** Read one field from a config remote in either of the two shapes it can take. */
    private fun remoteField(remote: Any?, name: String): String = when (remote) {
        is OpenVpnRemote -> when (name) {
            "proto" -> remote.proto
            "port" -> remote.port?.toString().orEmpty()
            else -> ""
        }
        is Map<*, *> -> VpnRecords.str(remote[name])
        else -> ""
    }

    /** Normalize one record; returns false when it must be dropped. */
    fun normalizeServer(server: VpnServerRecord): Boolean {
        val identity = VpnRecords.identity(server)
        val perf = VpnRecords.performance(server)
        val p = VpnRecords.protocols(server)

        identity["hostname"] = VpnUtil.normalizeHost(identity["hostname"])
        identity["ip"] = VpnUtil.normalizeIp(identity["ip"])

        if (!VpnUtil.validIp(VpnRecords.str(identity["ip"]))) {
            return false
        }

        val se = VpnRecords.map(p["softether"])
        val ov = VpnRecords.map(p["openvpn"])
        val l2 = VpnRecords.map(p["l2tpIpsec"])
        val sstp = VpnRecords.map(p["sstp"])

        // Normalize protocol booleans from their children.
        se["supported"] = VpnRecords.bool(VpnRecords.transport(se, "tcp")["supported"]) ||
            VpnRecords.bool(VpnRecords.transport(se, "udp")["supported"])

        ov["supported"] = VpnRecords.bool(VpnRecords.transport(ov, "tcp")["supported"]) ||
            VpnRecords.bool(VpnRecords.transport(ov, "udp")["supported"]) ||
            VpnRecords.bool(ov["configAvailable"])

        // v1.0.53 (§1.6.45) TASK B.3: the endpoint the embedded config dials is
        // authoritative over any port claimed elsewhere (an `OpenVPN_*_Port` CSV
        // column, a `do_openvpn` URL parameter). Runs before port validation so the
        // authoritative value is the one validated.
        enforceConfigAuthoritativePorts(server, ov)

        // Validate ports. A port that fails validation loses its
        // provenance too: a null port is never "explicit".
        for (protocolName in listOf("softether", "openvpn")) {
            val proto = VpnRecords.map(p[protocolName])
            for (transportName in listOf("tcp", "udp")) {
                val transport = VpnRecords.transport(proto, transportName)
                if (!VpnUtil.validPort(transport["port"])) {
                    transport["port"] = null
                    transport["portSource"] = VpnPortSource.UNKNOWN
                }
            }
        }

        if (!VpnUtil.validPort(l2["port"])) {
            l2["port"] = null
            l2["portSource"] = VpnPortSource.UNKNOWN
        }

        if (!VpnUtil.validPort(sstp["port"])) {
            sstp["port"] = null
            sstp["portSource"] = VpnPortSource.UNKNOWN
        }

        // SSTP may still be supported when hostname is present.
        sstp["supported"] = VpnRecords.bool(sstp["supported"]) ||
            VpnRecords.str(sstp["hostname"]).isNotEmpty()

        // If L2TP supported and port absent, 1701 is the protocol port.
        if (VpnRecords.bool(l2["supported"])) {
            l2["port"] = VpnProtocolDefaults.L2TP_PORT
            l2["portSource"] = VpnPortSource.DEFAULT
        }

        // Confirmed capability with no published SSTP port means the
        // protocol default applies — never an invented cross-protocol
        // port such as the OpenVPN TCP port.
        //
        // Stage 2F-A (L-1): this test used to be "portSource is empty",
        // which was the old UNKNOWN representation. Now that UNKNOWN is a
        // real literal, an empty/absent provenance still has to mean the
        // same thing, so the check is made against the canonical value.
        if (VpnRecords.bool(sstp["supported"]) &&
            VpnPortSource.normalized(sstp["portSource"]) == VpnPortSource.UNKNOWN
        ) {
            sstp["portSource"] = VpnPortSource.DEFAULT
        }

        // Convert uptime to reasonable float.
        if (VpnRecords.num(perf["uptimeDays"]) < 0) {
            perf["uptimeDays"] = 0.0
        }
        if (VpnRecords.num(perf["speedMbps"]) < 0) {
            perf["speedMbps"] = 0.0
        }

        return true
    }

    fun validateServers(servers: List<VpnServerRecord>): List<VpnServerRecord> =
        servers.filter { normalizeServer(it) }
}
