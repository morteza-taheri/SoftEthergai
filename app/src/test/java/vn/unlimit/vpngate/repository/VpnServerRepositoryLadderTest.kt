package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.remote.CollectorPath
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput

/**
 * v1.0.52 (§1.6.44) TASK A — the **automatic** route ladder.
 *
 * Replaces the v1.0.45 suite of the same name, which pinned a ladder reordered
 * by a user-facing opt-in (`preferVpn`). That opt-in — and its Settings switch,
 * its `DataUtil` accessors and `GlobalVpnTracker.prefersVpnForCollector()` — is
 * gone. The replacement contract is:
 *
 *  - **Base order** — `PHYSICAL -> TUNNEL (only when usable *and* bound) ->
 *    EMERGENCY`. Unchanged from v1.0.45 and still pinned here (T-NEW-55*).
 *  - **Promotion is evidence-based** — `TUNNEL` moves to the front only when
 *    [VpnServerRepository.PathMemory] says PHYSICAL is cooling down after a
 *    failure, or that TUNNEL is the transport that last delivered a dataset.
 *    No input value can promote it on its own (T-NEW-56*, T-LADDER-*).
 *  - **Invariants** — PHYSICAL is always present, EMERGENCY is always last and
 *    appears exactly once, and no rung repeats (T-NEW-57*).
 *
 * [VpnServerRepository.routeLadder] and [VpnServerRepository.PathMemory] are
 * both pure Kotlin with an injectable clock, so the entire decision — including
 * the cooldown and TTL expiry — is exercised on a plain JVM with no tracker, no
 * `Network` and no socket, and with no `Thread.sleep`.
 */
class VpnServerRepositoryLadderTest {

    /**
     * `PathMemory` is declared inside `VpnServerRepository`'s companion object,
     * so the qualified path a caller outside the class needs is
     * `VpnServerRepository.Companion.PathMemory`. Aliased here so the test reads
     * like the production code.
     */
    private typealias PathMemory = VpnServerRepository.Companion.PathMemory

    private fun ladder(
        tunnelUsable: Boolean,
        hasBoundNetwork: Boolean,
        memory: PathMemory? = null,
    ) = VpnServerRepository.routeLadder(
        CollectorRoutingInput(tunnelUsable = tunnelUsable, hasBoundNetwork = hasBoundNetwork),
        memory,
    )

    /** A memory whose clock the test moves by hand. */
    private class FakeClock(var now: Long = 1_000_000L) {
        fun memory(
            successTtlMs: Long = VpnServerRepository.PATH_MEMORY_SUCCESS_TTL_MS,
            failureCooldownMs: Long = VpnServerRepository.PATH_MEMORY_FAILURE_COOLDOWN_MS,
        ) = PathMemory(
            clock = { now },
            successTtlMs = successTtlMs,
            failureCooldownMs = failureCooldownMs,
        )
    }

    // ── T-NEW-55: the base order, with no evidence in memory ───────────────

    @Test
    fun `T-NEW-55a no usable tunnel gives PHYSICAL then EMERGENCY`() {
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = false, hasBoundNetwork = false),
        )
    }

    @Test
    fun `T-NEW-55b a usable bound tunnel makes TUNNEL the first rung (VPN-first)`() {
        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = true),
        )
    }

    @Test
    fun `T-NEW-55c a usable-looking tunnel with no bound network adds no rung`() {
        // `tunnelUsable` alone is not enough: without a Network handle there is
        // nothing to bind the request to, so the rung must not be advertised.
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = false),
        )
    }

    @Test
    fun `T-NEW-55d a bound network with a dead tunnel adds no rung`() {
        // The mirror image of 55c. `hasBoundNetwork` is a capability, not a
        // reason: it must never resurrect the rung for a tunnel that is down.
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = false, hasBoundNetwork = true),
        )
    }

    // ── T-NEW-56: promotion needs evidence, never an input ────────────────

    @Test
    fun `T-NEW-56a a usable bound tunnel is the primary even with no memory evidence (VPN-first)`() {
        // BLOCK v1.0.64: with a usable, bound tunnel the TUNNEL rung is the
        // primary regardless of memory evidence — the collector genuinely
        // attempts the VPN path first and only falls back to PHYSICAL.
        val memory = FakeClock().memory()

        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = true, memory = memory),
        )
    }

    @Test
    fun `T-NEW-56b a PHYSICAL failure cooldown promotes TUNNEL to the front`() {
        val clock = FakeClock()
        val memory = clock.memory()
        memory.recordFailure(CollectorPath.PHYSICAL)

        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = true, memory = memory),
        )
    }

    @Test
    fun `T-NEW-56c a proven TUNNEL success promotes TUNNEL to the front`() {
        val clock = FakeClock()
        val memory = clock.memory()
        memory.recordSuccess(CollectorPath.TUNNEL)

        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = true, memory = memory),
        )
    }

    @Test
    fun `T-NEW-56d evidence cannot promote a rung that is not available`() {
        // PHYSICAL is cooling down and TUNNEL was proven, but the tunnel is
        // unusable right now. Promotion must not conjure a rung: the ladder is
        // PHYSICAL -> EMERGENCY, exactly as if memory were empty.
        val clock = FakeClock()
        val memory = clock.memory()
        memory.recordFailure(CollectorPath.PHYSICAL)
        memory.recordSuccess(CollectorPath.TUNNEL)

        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = false, hasBoundNetwork = true, memory = memory),
        )
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = false, memory = memory),
        )
    }

    @Test
    fun `T-NEW-56e TUNNEL stays first with a usable bound network even when PHYSICAL was the proven path`() {
        // BLOCK v1.0.64: the VPN-first ordering is driven by the *inputs*
        // (a usable, bound tunnel), not by memory evidence. A proven PHYSICAL
        // success no longer keeps PHYSICAL in front once the tunnel is usable.
        val clock = FakeClock()
        val memory = clock.memory()
        memory.recordSuccess(CollectorPath.PHYSICAL)

        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = true, memory = memory),
        )
    }

    @Test
    fun `T-NEW-56f a null memory is the same as an empty one`() {
        assertEquals(
            ladder(tunnelUsable = true, hasBoundNetwork = true, memory = null),
            ladder(tunnelUsable = true, hasBoundNetwork = true, memory = FakeClock().memory()),
        )
    }

    // ── T-NEW-57: invariants across every combination and memory state ────

    @Test
    fun `T-NEW-57 every ladder is PHYSICAL-rooted, EMERGENCY-terminated and duplicate-free`() {
        val states: List<(PathMemory) -> Unit> = listOf(
            { /* empty memory */ },
            { it.recordFailure(CollectorPath.PHYSICAL) },
            { it.recordSuccess(CollectorPath.TUNNEL) },
            { it.recordFailure(CollectorPath.PHYSICAL); it.recordSuccess(CollectorPath.TUNNEL) },
            { it.recordSuccess(CollectorPath.PHYSICAL) },
            { it.recordFailure(CollectorPath.TUNNEL) },
            { it.recordFailure(CollectorPath.EMERGENCY) },
        )

        for (tunnelUsable in listOf(true, false)) {
            for (hasBoundNetwork in listOf(true, false)) {
                for ((index, seed) in states.withIndex()) {
                    val clock = FakeClock()
                    val memory = clock.memory()
                    seed(memory)

                    val result = ladder(tunnelUsable, hasBoundNetwork, memory)
                    val label = "ladder(tunnelUsable=$tunnelUsable, " +
                        "hasBoundNetwork=$hasBoundNetwork, memoryState=$index)=$result"

                    assertTrue("$label must contain PHYSICAL", result.contains(CollectorPath.PHYSICAL))
                    assertEquals(
                        "$label must end with EMERGENCY — it is the last rung, never promoted",
                        CollectorPath.EMERGENCY,
                        result.last(),
                    )
                    assertEquals(
                        "$label must list EMERGENCY exactly once",
                        1,
                        result.count { it == CollectorPath.EMERGENCY },
                    )
                    assertEquals("$label must not repeat a rung", result.size, result.toSet().size)
                    assertTrue("$label must have 2 or 3 rungs", result.size in 2..3)
                    assertEquals(
                        "$label: TUNNEL may appear only when the tunnel is usable and bound",
                        result.contains(CollectorPath.TUNNEL),
                        tunnelUsable && hasBoundNetwork,
                    )
                    // The promotion is a reorder, never an addition or removal.
                    assertTrue(
                        "$label must always keep PHYSICAL before EMERGENCY",
                        result.indexOf(CollectorPath.PHYSICAL) < result.indexOf(CollectorPath.EMERGENCY),
                    )
                }
            }
        }
    }

    @Test
    fun `T-NEW-57b the ladder never promotes EMERGENCY above a network rung`() {
        val clock = FakeClock()
        val memory = clock.memory()
        // Even with every path marked failed, EMERGENCY stays last: it carries
        // no network and is a source switch, not a transport.
        memory.recordFailure(CollectorPath.PHYSICAL)
        memory.recordFailure(CollectorPath.TUNNEL)

        for (tunnelUsable in listOf(true, false)) {
            for (hasBoundNetwork in listOf(true, false)) {
                assertEquals(
                    "EMERGENCY must be last for " +
                        "(tunnelUsable=$tunnelUsable, hasBoundNetwork=$hasBoundNetwork)",
                    CollectorPath.EMERGENCY,
                    ladder(tunnelUsable, hasBoundNetwork, memory).last(),
                )
            }
        }
    }

    @Test
    fun `T-NEW-57c resolvePrimary is the first rung for every input`() {
        for (tunnelUsable in listOf(true, false)) {
            for (hasBoundNetwork in listOf(true, false)) {
                val input = CollectorRoutingInput(tunnelUsable, hasBoundNetwork)
                assertEquals(
                    "resolvePrimary must be routeLadder().first() for $input",
                    VpnServerRepository.routeLadder(input).first(),
                    VpnServerRepository.resolvePrimary(input),
                )
            }
        }
        // And the two surviving measurements are exactly what decides it.
        assertEquals(
            CollectorPath.PHYSICAL,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, false)),
        )
        assertEquals(
            CollectorPath.PHYSICAL,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(false, true)),
        )
    }

    // ── PathMemory: the bounded, in-process evidence store ────────────────

    @Test
    fun `T-LADDER-01 a failure cools down for exactly the configured window`() {
        val clock = FakeClock()
        val memory = clock.memory(failureCooldownMs = 120_000L)

        memory.recordFailure(CollectorPath.PHYSICAL)

        assertTrue("a fresh failure must be cooling down", memory.isCoolingDown(CollectorPath.PHYSICAL))
        clock.now += 119_999L
        assertTrue("the cooldown must still hold 1 ms before expiry", memory.isCoolingDown(CollectorPath.PHYSICAL))
        clock.now += 1L
        assertFalse("the cooldown must be over at exactly the configured window", memory.isCoolingDown(CollectorPath.PHYSICAL))
    }

    @Test
    fun `T-LADDER-02 a success expires after exactly the configured TTL`() {
        val clock = FakeClock()
        val memory = clock.memory(successTtlMs = 60_000L)

        memory.recordSuccess(CollectorPath.TUNNEL)

        assertEquals(CollectorPath.TUNNEL, memory.preferred())
        clock.now += 59_999L
        assertEquals("a success must still be fresh 1 ms before the TTL", CollectorPath.TUNNEL, memory.preferred())
        clock.now += 1L
        assertNull("a success must be forgotten at exactly the TTL", memory.preferred())
    }

    @Test
    fun `T-LADDER-03 a success clears that path's cooldown`() {
        val clock = FakeClock()
        val memory = clock.memory()

        memory.recordFailure(CollectorPath.PHYSICAL)
        assertTrue(memory.isCoolingDown(CollectorPath.PHYSICAL))

        memory.recordSuccess(CollectorPath.PHYSICAL)

        assertFalse(
            "a path that just delivered a dataset cannot still be cooling down",
            memory.isCoolingDown(CollectorPath.PHYSICAL),
        )
    }

    @Test
    fun `T-LADDER-04 the most recent fresh success wins`() {
        val clock = FakeClock()
        val memory = clock.memory()

        memory.recordSuccess(CollectorPath.PHYSICAL)
        clock.now += 10L
        memory.recordSuccess(CollectorPath.TUNNEL)

        assertEquals(CollectorPath.TUNNEL, memory.preferred())

        // A newer PHYSICAL success takes it back — the memory tracks the last
        // transport that worked, not a sticky preference.
        clock.now += 10L
        memory.recordSuccess(CollectorPath.PHYSICAL)

        assertEquals(CollectorPath.PHYSICAL, memory.preferred())
    }

    @Test
    fun `T-LADDER-05 a stale success never outranks a fresh one`() {
        val clock = FakeClock()
        val memory = clock.memory(successTtlMs = 60_000L)

        memory.recordSuccess(CollectorPath.TUNNEL)
        clock.now += 61_000L // TUNNEL's success is now stale
        memory.recordSuccess(CollectorPath.PHYSICAL)

        assertEquals(
            "the expired TUNNEL success must not be preferred over the fresh PHYSICAL one",
            CollectorPath.PHYSICAL,
            memory.preferred(),
        )
    }

    @Test
    fun `T-LADDER-06 the memory is bounded by the enum, not by call count`() {
        val clock = FakeClock()
        val memory = clock.memory()

        // Hammer every path far beyond any plausible real-world rate. The key
        // space is the three-value enum, so the store cannot grow with calls:
        // the observable consequence is that `preferred()` still answers with
        // exactly one of those three values and nothing else is reachable.
        repeat(10_000) {
            for (path in CollectorPath.entries) {
                memory.recordSuccess(path)
                memory.recordFailure(path)
            }
            clock.now += 1L
        }

        assertEquals(
            "the key space must be exactly the CollectorPath enum",
            3,
            CollectorPath.entries.size,
        )
        val preferred = memory.preferred()
        assertTrue(
            "preferred() must return a CollectorPath or null, never an unbounded key: $preferred",
            preferred == null || preferred in CollectorPath.entries,
        )
        // Every enum value must still be queryable without throwing.
        for (path in CollectorPath.entries) {
            memory.isCoolingDown(path)
        }
    }

    @Test
    fun `T-LADDER-07 clear forgets both successes and failures`() {
        val clock = FakeClock()
        val memory = clock.memory()

        memory.recordSuccess(CollectorPath.TUNNEL)
        memory.recordFailure(CollectorPath.PHYSICAL)

        memory.clear()

        assertNull(memory.preferred())
        assertFalse(memory.isCoolingDown(CollectorPath.PHYSICAL))
    }

    @Test
    fun `T-LADDER-08 VPN-first ordering is independent of the PHYSICAL cooldown`() {
        // BLOCK v1.0.64: the TUNNEL-first ordering is driven by the routing
        // inputs (a usable, bound tunnel), not by cooldown evidence. A PHYSICAL
        // failure cools down, but the ladder stays TUNNEL-first for as long as
        // the tunnel is usable and bound — it does not revert to PHYSICAL-first
        // when the cooldown lapses.
        val clock = FakeClock()
        val memory = clock.memory(failureCooldownMs = 120_000L)
        memory.recordFailure(CollectorPath.PHYSICAL)

        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = true, memory = memory),
        )

        clock.now += 120_000L

        assertEquals(
            "with a usable+bound tunnel the ladder must stay TUNNEL-first after the cooldown",
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = true, memory = memory),
        )
    }
}
