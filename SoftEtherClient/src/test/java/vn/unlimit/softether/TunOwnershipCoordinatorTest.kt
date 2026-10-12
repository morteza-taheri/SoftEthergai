package vn.unlimit.softether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Stage 2F-F.28J — behavioural tests for session-owned TUN establishment.
 *
 * ## What these tests exercise
 *
 * [TunOwnershipCoordinator] in isolation, with a fake installation operation standing in
 * for `VpnService.Builder.establish()`. The coordinator is the production mechanism that
 * decides whether a session may create a descriptor at all, so these tests drive the
 * real protocol rather than a re-implementation of it.
 *
 * ## What they deliberately do NOT prove
 *
 * `Builder.establish()` cannot run on a plain JVM, so three things remain unverified
 * here and are NOT claimed to be covered:
 *
 *  1. Android runtime wiring — that the descriptor returned by the real
 *     `establish()` behaves like the fake (in particular that closing it releases the
 *     TUN). Only device testing can show that.
 *  2. That [vn.unlimit.softether.SoftEtherVpnService] passes the right session id from
 *     every call site, and that `ConnectionController` captures it at construction.
 *     Those are source-level facts, not behavioural ones.
 *  3. Real `ParcelFileDescriptor` idempotence of `close()`. The tests use a fake with a
 *     close counter, which is stricter about double-close than the real type.
 *
 * A unit test cannot demonstrate physical VPN behaviour, and nothing here should be
 * read as evidence about an on-device tunnel.
 */
class TunOwnershipCoordinatorTest {

    /** Stand-in for a `ParcelFileDescriptor`, with observable close behaviour. */
    private class FakeTun(val id: String) {
        val closeCount = AtomicInteger(0)
        val closed = AtomicBoolean(false)
        fun close() {
            closeCount.incrementAndGet()
            closed.set(true)
        }
    }

    private fun coordinator(): TunOwnershipCoordinator<FakeTun> =
        TunOwnershipCoordinator { descriptor -> descriptor.close() }

    private fun installed(result: TunInstallResult<FakeTun>): FakeTun {
        assertTrue("expected an installed descriptor but was $result", result is TunInstallResult.Installed)
        return (result as TunInstallResult.Installed).handle
    }

    // ------------------------------------------------------------ ownership basics

    @Test
    fun `TEST J1 - the owning session may install its descriptor`() {
        val tun = coordinator()
        tun.claim(session = 7)

        val handle = installed(tun.install(session = 7) { FakeTun("s7") })

        assertEquals("s7", handle.id)
        assertTrue("a freshly installed descriptor is a live resource", tun.isHeld())
        assertEquals(7, tun.ownerSession())
        assertEquals("installing must not close the descriptor", 0, handle.closeCount.get())
    }

    @Test
    fun `TEST J2 - a session that never claimed cannot install`() {
        val tun = coordinator()
        val establishCalls = AtomicInteger(0)

        val result = tun.install(session = 7) { FakeTun("s7").also { establishCalls.incrementAndGet() } }

        assertEquals(TunInstallResult.RefusedOwnership, result)
        assertEquals("a refused session must not create a descriptor", 0, establishCalls.get())
        assertFalse(tun.isHeld())
    }

    @Test
    fun `TEST J3 - an obsolete session is refused and cannot replace the live descriptor`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val current = installed(tun.install(session = 7) { FakeTun("s7") })

        // Session 7 is replaced by session 8, which installs its own descriptor.
        tun.claim(session = 8)
        val live = installed(tun.install(session = 8) { FakeTun("s8") })

        // Session 7's reconnect wakes up and tries to establish.
        val staleEstablishCalls = AtomicInteger(0)
        val result = tun.install(session = 7) {
            FakeTun("stale").also { staleEstablishCalls.incrementAndGet() }
        }

        assertEquals(TunInstallResult.RefusedOwnership, result)
        assertEquals("the obsolete session must never reach establish()", 0, staleEstablishCalls.get())
        assertFalse("the live descriptor must not be closed by a stale attempt", live.closed.get())
        assertEquals("the live descriptor must not be closed by a stale attempt", 0, live.closeCount.get())
        assertEquals("the live descriptor must still be the registered one", "s8", (live).id)
    }

    @Test
    fun `TEST J4 - a platform failure is distinguished from an ownership refusal`() {
        val tun = coordinator()
        tun.claim(session = 7)

        val result = tun.install(session = 7) { null }

        assertEquals(TunInstallResult.EstablishFailed, result)
        assertFalse("a failed establishment must register nothing", tun.isHeld())
        assertEquals("ownership is retained so the session may retry", 7, tun.ownerSession())
    }

    // ------------------------------------------------- release / teardown ownership

    @Test
    fun `TEST J5 - release closes and unregisters, so no closed descriptor stays live`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val handle = installed(tun.install(session = 7) { FakeTun("s7") })

        val released = tun.release(session = 7)

        assertTrue("the owning session must be able to release", released)
        assertEquals("release must close exactly once", 1, handle.closeCount.get())
        assertFalse("a closed descriptor must not remain a live service resource", tun.isHeld())
    }

    @Test
    fun `TEST J6 - an obsolete teardown cannot release the new session's descriptor`() {
        val tun = coordinator()
        tun.claim(session = 7)
        installed(tun.install(session = 7) { FakeTun("s7") })
        tun.claim(session = 8)
        val live = installed(tun.install(session = 8) { FakeTun("s8") })

        // Session 7's teardown arrives after session 8 took over.
        val released = tun.release(session = 7)

        assertFalse("an obsolete session must not release the live descriptor", released)
        assertEquals("the live descriptor must be untouched", 0, live.closeCount.get())
        assertFalse(live.closed.get())
        assertTrue("the live descriptor must remain a live resource", tun.isHeld())
        assertEquals(8, tun.ownerSession())
    }

    @Test
    fun `TEST J7 - a descriptor closed by its controller is still unblocked by release`() {
        // Reproduces the F.28I latch: the controller closed its own reference, but the
        // service kept registering the now-closed descriptor, so the start gate read a
        // live resource forever. Releasing through the owner clears the record.
        val tun = coordinator()
        tun.claim(session = 7)
        val handle = installed(tun.install(session = 7) { FakeTun("s7") })

        // The controller closes its own copy first, exactly as destroyResources() does.
        handle.close()
        assertTrue("precondition: the service still registers the descriptor", tun.isHeld())

        assertTrue(tun.release(session = 7))

        assertFalse(
            "after release the closed descriptor must not remain registered, " +
                    "otherwise the start gate refuses every later connection",
            tun.isHeld(),
        )
    }

    @Test
    fun `TEST J8 - a stale attempt cannot clear a descriptor belonging to a newer session`() {
        val tun = coordinator()
        tun.claim(session = 7)
        installed(tun.install(session = 7) { FakeTun("s7") })
        tun.claim(session = 8)
        val live = installed(tun.install(session = 8) { FakeTun("s8") })

        // A stale session fails during establishment and "cleans up" by releasing.
        tun.release(session = 7)
        // And a stale failure must not be able to reach releaseAll either.
        assertTrue("session 8 still owns the descriptor", tun.isHeld())
        assertEquals(0, live.closeCount.get())

        // Only real destruction releases unconditionally.
        tun.releaseAll()
        assertEquals(1, live.closeCount.get())
        assertFalse(tun.isHeld())
    }

    // -------------------------------------------------------------- claim semantics

    @Test
    fun `TEST J9 - claiming a session discards a descriptor inherited from the previous owner`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val inherited = installed(tun.install(session = 7) { FakeTun("s7") })

        // A new session starts while the previous owner's descriptor is somehow still
        // registered (the gate read it just before the stale installer published it).
        tun.claim(session = 8)

        assertEquals("the inherited descriptor must be closed", 1, inherited.closeCount.get())
        assertFalse("an inherited descriptor must not stay live", tun.isHeld())
        assertEquals(8, tun.ownerSession())
    }

    @Test
    fun `TEST J10 - a discarded stale attempt does not permanently refuse later connections`() {
        val tun = coordinator()
        tun.claim(session = 7)
        installed(tun.install(session = 7) { FakeTun("s7") })

        // Teardown, then a stale reconnect attempt, then a new session.
        tun.release(session = 7)
        assertEquals(TunInstallResult.RefusedOwnership, tun.install(session = 7) { FakeTun("stale") })

        tun.claim(session = 8)
        val recovered = installed(tun.install(session = 8) { FakeTun("s8") })

        assertEquals("s8", recovered.id)
        assertTrue("the gate must not be latched by the discarded attempt", tun.isHeld())
        assertEquals(0, recovered.closeCount.get())
    }

    // ------------------------------------------------------- the check-then-act race

    @Test
    fun `TEST J11 - installation is atomic with ownership change, so a stale installer cannot establish after replacement`() {
        val tun = coordinator()
        tun.claim(session = 7)

        val insideEstablish = CountDownLatch(1)
        val mayFinishEstablish = CountDownLatch(1)
        val staleDescriptor = AtomicReference<FakeTun>()

        val installer = Thread {
            tun.install(session = 7) {
                insideEstablish.countDown()
                // Hold the critical section open across the establishment, which is the
                // window a plain pre-check would leave unprotected.
                mayFinishEstablish.await(5, TimeUnit.SECONDS)
                FakeTun("s7-late").also { staleDescriptor.set(it) }
            }
        }
        installer.start()
        assertTrue("installer must reach establish()", insideEstablish.await(5, TimeUnit.SECONDS))

        // Session 7 is replaced while session 7's establishment is still in flight.
        val claimFinished = AtomicBoolean(false)
        val claimer = Thread {
            tun.claim(session = 8)
            claimFinished.set(true)
        }
        claimer.start()
        Thread.sleep(250)

        assertFalse(
            "ownership must not change while an establishment is in flight; " +
                    "otherwise a stale installer could establish after being replaced",
            claimFinished.get(),
        )

        mayFinishEstablish.countDown()
        installer.join(5_000)
        claimer.join(5_000)

        // The stale descriptor was created, but the replacement discarded it in the same
        // protocol, so it can never remain registered or replace the new session's.
        val created = staleDescriptor.get()
        assertTrue("the in-flight establishment did complete", created != null)
        assertEquals("the replacement must close what it inherited", 1, created!!.closeCount.get())
        assertFalse("no stale descriptor may remain live", tun.isHeld())
        assertEquals(8, tun.ownerSession())

        val live = installed(tun.install(session = 8) { FakeTun("s8") })
        assertEquals("s8", live.id)
    }

    @Test
    fun `TEST J12 - a teardown racing an installation cannot leak the descriptor`() {
        val tun = coordinator()
        tun.claim(session = 7)

        val insideEstablish = CountDownLatch(1)
        val mayFinishEstablish = CountDownLatch(1)
        val created = AtomicReference<FakeTun>()

        val installer = Thread {
            tun.install(session = 7) {
                insideEstablish.countDown()
                mayFinishEstablish.await(5, TimeUnit.SECONDS)
                FakeTun("s7").also { created.set(it) }
            }
        }
        installer.start()
        assertTrue(insideEstablish.await(5, TimeUnit.SECONDS))

        // Teardown arrives mid-establishment and blocks until the establishment ends.
        val releaser = Thread { tun.release(session = 7) }
        releaser.start()
        Thread.sleep(250)
        assertFalse("teardown must not interleave inside the critical section", releaser.isAlive.not())

        mayFinishEstablish.countDown()
        installer.join(5_000)
        releaser.join(5_000)

        val handle = created.get()
        assertTrue(handle != null)
        assertEquals("the descriptor must be closed exactly once", 1, handle!!.closeCount.get())
        assertFalse("nothing may remain registered as live", tun.isHeld())
    }

    // ------------------------------------------------ shared invariant for both paths

    @Test
    fun `TEST J13 - initial-connect and reconnect attempts share one ownership invariant`() {
        // Both ConnectionController paths funnel through the same coordinator call, so a
        // stale session is refused identically no matter which path issued the attempt.
        repeat(2) { path ->
            val tun = coordinator()
            tun.claim(session = 7)
            installed(tun.install(session = 7) { FakeTun("s7") })

            // Session 7 is torn down and session 8 takes over.
            tun.release(session = 7)
            tun.claim(session = 8)
            val live = installed(tun.install(session = 8) { FakeTun("s8") })

            val calls = AtomicInteger(0)
            val result = tun.install(session = 7) { FakeTun("stale-$path").also { calls.incrementAndGet() } }

            assertEquals("path $path must be refused identically", TunInstallResult.RefusedOwnership, result)
            assertEquals("path $path must never establish", 0, calls.get())
            assertEquals("path $path must not disturb the live descriptor", 0, live.closeCount.get())
            assertTrue("path $path must leave the live descriptor registered", tun.isHeld())
        }
    }

    @Test
    fun `TEST J14 - the fake session id is never accepted as an owner`() {
        val tun = coordinator()
        // A controller must present a real session id; NO_SESSION means "nobody owns
        // establishment" and must not be usable to install.
        assertEquals(TunInstallResult.RefusedOwnership, tun.install(TunOwnershipCoordinator.NO_SESSION) { FakeTun("x") })
        assertFalse(tun.isHeld())
    }

    @Test
    fun `TEST J15 - releaseAll is idempotent and safe with nothing held`() {
        val tun = coordinator()
        tun.releaseAll()
        assertFalse(tun.isHeld())

        tun.claim(session = 7)
        val handle = installed(tun.install(session = 7) { FakeTun("s7") })
        tun.releaseAll()
        tun.releaseAll()

        assertEquals("repeated destruction must close exactly once", 1, handle.closeCount.get())
        assertFalse(tun.isHeld())
    }

    // -------------------------------------- Stage 2F-F.28L: atomic invalidation

    /*
     * F.28K found that logical invalidation and installation authorization were two
     * independent state machines: stopVpn() called beginTeardown() and only much later
     * release(), so a reconnect unblocked inside native connect or DHCP during that
     * interval could still establish a TUN. invalidate() is the new linearization point.
     *
     * The property under test is specifically: revoking installation permission must
     * NOT take away the session's ability to release a descriptor it already installed.
     * That is why the coordinator carries installer and descriptorOwner separately.
     */

    @Test
    fun `TEST J16 - installation is refused after invalidation and establish is never invoked`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val calls = AtomicInteger(0)

        tun.invalidate(session = 7)

        val result = tun.install(session = 7) { FakeTun("s7").also { calls.incrementAndGet() } }

        assertEquals(TunInstallResult.RefusedOwnership, result)
        assertEquals(
            "an invalidated session must never reach establish(); this was the F.28K window",
            0,
            calls.get(),
        )
        assertFalse("nothing may be registered by a refused attempt", tun.isHeld())
        assertEquals(TunOwnershipCoordinator.NO_SESSION, tun.installerSession())
    }

    @Test
    fun `TEST J17 - invalidation retains cleanup ownership of an already installed descriptor`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val handle = installed(tun.install(session = 7) { FakeTun("s7") })

        tun.invalidate(session = 7)

        assertEquals(
            "invalidation must not give up the descriptor claim, or teardown could not clean up",
            7,
            tun.ownerSession(),
        )
        assertEquals("invalidation must not close anything by itself", 0, handle.closeCount.get())
        assertTrue("the descriptor must remain a live resource until released", tun.isHeld())

        assertTrue("teardown must still be able to release it", tun.release(session = 7))
        assertEquals(1, handle.closeCount.get())
        assertFalse(tun.isHeld())
    }

    @Test
    fun `TEST J18 - invalidation racing installation admits only two outcomes`() {
        // Case A: installation holds the monitor first -> it completes, publication
        //         happens, then invalidation revokes further permission.
        run {
            val tun = coordinator()
            tun.claim(session = 7)
            val insideEstablish = CountDownLatch(1)
            val mayFinish = CountDownLatch(1)
            val created = AtomicReference<FakeTun>()
            val invalidateFinished = AtomicBoolean(false)

            val installer = Thread {
                tun.install(session = 7) {
                    insideEstablish.countDown()
                    mayFinish.await(5, TimeUnit.SECONDS)
                    FakeTun("s7").also { created.set(it) }
                }
            }
            installer.start()
            assertTrue(insideEstablish.await(5, TimeUnit.SECONDS))

            val invalidator = Thread { tun.invalidate(session = 7); invalidateFinished.set(true) }
            invalidator.start()
            Thread.sleep(250)
            assertFalse(
                "invalidation must not interleave inside the establishment critical section",
                invalidateFinished.get(),
            )

            mayFinish.countDown()
            installer.join(5_000)
            invalidator.join(5_000)

            val handle = created.get()
            assertTrue("case A: the in-flight establishment must have completed", handle != null)
            assertEquals("case A: the descriptor must be registered", 0, handle!!.closeCount.get())
            assertTrue("case A: publication completed before invalidation linearized", tun.isHeld())

            // Invalidation linearized after that publication, so no further install
            // from session 7 is accepted.
            val lateCalls = AtomicInteger(0)
            val late = tun.install(session = 7) { FakeTun("late").also { lateCalls.incrementAndGet() } }
            assertEquals(TunInstallResult.RefusedOwnership, late)
            assertEquals("case A: no further install is permitted after invalidation", 0, lateCalls.get())

            // Teardown still cleans up exactly once.
            assertTrue(tun.release(session = 7))
            assertEquals(1, handle.closeCount.get())
            assertFalse(tun.isHeld())
        }

        // Case B: invalidation wins the monitor -> install is refused, establish is
        //         never invoked, and a later session can still claim and install.
        run {
            val tun = coordinator()
            tun.claim(session = 7)

            tun.invalidate(session = 7)

            val calls = AtomicInteger(0)
            val result = tun.install(session = 7) { FakeTun("s7").also { calls.incrementAndGet() } }

            assertEquals(TunInstallResult.RefusedOwnership, result)
            assertEquals("case B: establish must not have been invoked", 0, calls.get())
            assertFalse("case B: nothing may be registered", tun.isHeld())

            tun.claim(session = 8)
            val recovered = installed(tun.install(session = 8) { FakeTun("s8") })
            assertEquals("case B: a legitimate session must still be able to install", "s8", recovered.id)
        }
    }

    @Test
    fun `TEST J19 - invalidation is idempotent`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val handle = installed(tun.install(session = 7) { FakeTun("s7") })

        tun.invalidate(session = 7)
        tun.invalidate(session = 7)
        tun.invalidate(session = 7)

        assertEquals("repeated invalidation must not close anything", 0, handle.closeCount.get())
        assertEquals(7, tun.ownerSession())
        assertTrue(tun.isHeld())
        assertTrue("cleanup must still be possible after repeated invalidation", tun.release(session = 7))
        assertEquals(1, handle.closeCount.get())
    }

    @Test
    fun `TEST J20 - an invalidated session cannot release a newer session's descriptor`() {
        val tun = coordinator()
        tun.claim(session = 7)
        installed(tun.install(session = 7) { FakeTun("s7") })
        tun.invalidate(session = 7)
        tun.release(session = 7)

        // A newer session takes over and installs.
        tun.claim(session = 8)
        val live = installed(tun.install(session = 8) { FakeTun("s8") })

        // Session 7's teardown runs again after session 8 owns everything.
        tun.invalidate(session = 7)
        val released = tun.release(session = 7)

        assertFalse("an invalidated/obsolete session must not release the live descriptor", released)
        assertEquals("the live descriptor must be untouched", 0, live.closeCount.get())
        assertFalse(live.closed.get())
        assertTrue("the live descriptor must remain registered", tun.isHeld())
        assertEquals(8, tun.installerSession())
        assertEquals(8, tun.ownerSession())
    }

    @Test
    fun `TEST J21 - invalidating an unknown or already replaced session cannot revoke the live permission`() {
        val tun = coordinator()
        tun.claim(session = 7)
        installed(tun.install(session = 7) { FakeTun("s7") })
        tun.release(session = 7)

        tun.claim(session = 8)

        // A stale teardown for the previous session must be inert.
        tun.invalidate(session = 7)
        tun.invalidate(session = TunOwnershipCoordinator.NO_SESSION)
        tun.invalidate(session = 99)

        assertEquals(
            "only the owning session's invalidation may revoke installation permission",
            8,
            tun.installerSession(),
        )
        val live = installed(tun.install(session = 8) { FakeTun("s8") })
        assertEquals("s8", live.id)
    }

    @Test
    fun `TEST J22 - a full invalidate then release cycle leaves no latch and permits recovery`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val first = installed(tun.install(session = 7) { FakeTun("s7") })

        // Teardown of session 7: invalidate, then release.
        tun.invalidate(session = 7)
        assertTrue(tun.release(session = 7))
        assertEquals(1, first.closeCount.get())
        assertFalse(
            "after teardown the start gate must not read a live resource",
            tun.isHeld(),
        )

        // A reconnect attempt from the torn-down session is refused.
        val staleCalls = AtomicInteger(0)
        assertEquals(
            TunInstallResult.RefusedOwnership,
            tun.install(session = 7) { FakeTun("stale").also { staleCalls.incrementAndGet() } },
        )
        assertEquals(0, staleCalls.get())

        // The next legitimate session works normally.
        tun.claim(session = 8)
        val second = installed(tun.install(session = 8) { FakeTun("s8") })
        assertEquals("s8", second.id)
        assertTrue(tun.isHeld())
        assertEquals(0, second.closeCount.get())
    }

    @Test
    fun `TEST J23 - releaseAll still cleans up exactly once after invalidation`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val handle = installed(tun.install(session = 7) { FakeTun("s7") })
        tun.invalidate(session = 7)

        tun.releaseAll()
        tun.releaseAll()
        tun.invalidate(session = 7)

        assertEquals("destruction must close exactly once", 1, handle.closeCount.get())
        assertFalse(tun.isHeld())
        assertEquals(TunOwnershipCoordinator.NO_SESSION, tun.installerSession())
        assertEquals(TunOwnershipCoordinator.NO_SESSION, tun.ownerSession())
    }

    @Test
    fun `TEST J24 - a descriptor closed by its owner is not left registered after release`() {
        val tun = coordinator()
        tun.claim(session = 7)
        val handle = installed(tun.install(session = 7) { FakeTun("s7") })

        // The controller closes its own copy first, as destroyResources() does.
        handle.close()
        tun.invalidate(session = 7)
        assertTrue("precondition: still registered before release", tun.isHeld())

        assertTrue(tun.release(session = 7))
        assertFalse(
            "a closed descriptor must not remain registered as a live resource, " +
                    "otherwise the start gate refuses every later connection",
            tun.isHeld(),
        )
    }

    @Test
    fun `TEST J25 - a same-session re-install closes the descriptor it replaces`() {
        // install() upholds this invariant itself rather than trusting callers, so a
        // future install-without-close path cannot leak a descriptor.
        val tun = coordinator()
        tun.claim(session = 7)
        val first = installed(tun.install(session = 7) { FakeTun("s7-first") })

        val second = installed(tun.install(session = 7) { FakeTun("s7-second") })

        assertEquals("the replaced descriptor must be closed", 1, first.closeCount.get())
        assertEquals("the live descriptor must be the new one", 0, second.closeCount.get())
        assertTrue(tun.isHeld())
        assertEquals(7, tun.ownerSession())
    }
}