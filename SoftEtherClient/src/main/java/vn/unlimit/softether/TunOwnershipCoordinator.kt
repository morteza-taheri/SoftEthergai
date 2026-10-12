package vn.unlimit.softether

/**
 * Stage 2F-F.28J — session-owned TUN establishment.
 *
 * ## Why this exists
 *
 * Stage 2F-F.28I traced an A-level ownership defect. `VpnService.Builder.establish()`
 * is called from [vn.unlimit.softether.controller.ConnectionController], but the
 * descriptor it produced was written into a *service-wide* field on
 * SoftEtherVpnService. Nothing tied that write to the session that performed it, and
 * `attemptReconnect()` checked for cancellation only *after* installing:
 *
 * ```
 * controller:1014   client.doDhcp(nativeHandle)      // blocking, not cancellable
 * controller:1023   vpnInterface = service.establishVpnInterface(...)   // INSTALL
 * controller:1032   if (isCancelled || isDestroyed || ...) { ... }     // CHECK
 * ```
 *
 * `destroyResources()` only *requests* cancellation (`reconnectJob?.cancel()`,
 * `scope.cancel()`), and coroutine cancellation cannot interrupt a blocking native
 * call. So an obsolete session could reach line 1023 after teardown had completed and:
 *
 *  1. replace the live system VPN interface (establish() is unconditional once called);
 *  2. overwrite the service's reference to a newer session's descriptor, leaking it;
 *  3. close its own copy while the service kept a reference to the now-closed
 *     descriptor — and because the start gate treats any non-null descriptor as a live
 *     resource, that stale reference would refuse every later connection.
 *
 * The initial-connect path did not have this hole: it checks at controller:463
 * *before* installing at 483/488.
 *
 * ## Two independent permissions
 *
 * A session can be *permitted to establish* ([installer]) without being *responsible
 * for the registered descriptor* ([descriptorOwner]), and the two are deliberately not
 * the same field. Teardown must revoke establishment permission the instant it starts,
 * while the descriptor that session already installed must stay releasable by that same
 * session until cleanup actually runs. Stage 2F-F.28K showed that collapsing the two
 * into one `owner` field leaves a window — between logical invalidation and
 * `release()` — in which a logically torn-down session can still establish. [invalidate]
 * closes that window without taking away the ability to clean up.
 *
 * ## The invariant
 *
 * A descriptor may only be created by the session currently permitted to establish, and
 * permission may only be revoked while no establishment is in flight. Both properties
 * together remove the check-then-act window: validation and establishment happen inside
 * one critical section, so an obsolete installer cannot validate, be suspended, be
 * replaced, and then establish.
 *
 * Ownership is keyed on a session id **captured by the controller when it is
 * created**, never on a generation read at install time. An obsolete controller keeps
 * its own stale id and is therefore refused; it cannot look itself up as current.
 *
 * ## Why the lock is held across the installation
 *
 * `establish()` itself is the damaging operation — it replaces the OS-level VPN
 * interface — so validating without excluding invalidation cannot be made safe.
 * Establishment and invalidation must therefore be mutually exclusive. The lock is
 * held across `establish()` and across descriptor `close()`; both are short
 * synchronous calls (a binder call and a `close(2)` syscall respectively).
 *
 * The lock is never held across a blocking native connect or DHCP: those all happen
 * in the caller *before* [install] is invoked, so an in-flight connect never blocks a
 * teardown that wants the lock. Nothing inside a critical section waits on a
 * coroutine, a coroutine dispatcher, or the main looper, so there is no lock-order
 * cycle with the service's main-thread lifecycle callbacks.
 *
 * ## Ownership of the descriptor
 *
 * [install] returns the descriptor to the caller, which uses it for data forwarding.
 * The coordinator keeps the same reference so it can (a) answer the start gate and
 * (b) close it on teardown. `ParcelFileDescriptor.close()` is idempotent, so the
 * existing double-close paths remain safe; what this class adds is that the service
 * can never be left *registering* a descriptor it has already closed.
 *
 * Not Android-specific and performs no I/O: [establish] is injected, which is what
 * makes the protocol unit-testable on a plain JVM.
 */
class TunOwnershipCoordinator<T : Any>(
    private val close: (T) -> Unit,
) {

    companion object {
        /** Owner value meaning "no session owns TUN establishment". */
        const val NO_SESSION = 0
    }

    private val lock = Any()

    /**
     * Session currently *permitted to begin* a new installation, or [NO_SESSION].
     * Guarded by [lock].
     *
     * Stage 2F-F.28L. This is deliberately separate from [descriptorOwner]. F.28K
     * established that a single `owner` field could not express both meanings: teardown
     * needs to revoke installation permission immediately, while the descriptor that
     * session already installed must stay releasable by that same session's teardown
     * until cleanup actually runs. Revoking permission must not destroy the ability to
     * clean up.
     */
    private var installer: Int = NO_SESSION

    /**
     * Session that owns the registered [handle] and may therefore release it, or
     * [NO_SESSION]. Guarded by [lock].
     */
    private var descriptorOwner: Int = NO_SESSION

    /** Descriptor currently registered as a live service resource. Guarded by [lock]. */
    private var handle: T? = null

    /**
     * Transfer ownership to [session], discarding any descriptor inherited from a
     * previous owner.
     *
     * Called when a session is admitted. Discarding matters: the start gate reads the
     * descriptor on the main thread while an obsolete installer may publish on a
     * background thread, so a descriptor can appear between the gate's read and this
     * call. Because this runs inside the lock, such an installer either completes
     * first — and is discarded here — or is still in progress and completes only after
     * this call, at which point its session id no longer matches [installer].
     */
    fun claim(session: Int) {
        require(session != NO_SESSION) { "a real session id is required" }
        synchronized(lock) {
            discardLocked()
            descriptorOwner = session
            installer = session
        }
    }

    /**
     * Revoke [session]'s permission to begin a new installation, without giving up its
     * claim on any descriptor it already installed.
     *
     * Stage 2F-F.28L. This is the linearization point for installation invalidation.
     * It is called at the *earliest* point of teardown, before the synchronous
     * broadcast, notification and foreground-service work that precedes [release]. That
     * ordering is the whole point: previously the coordinator's permission survived the
     * whole of that work, so a reconnect unblocked inside native connect or DHCP during
     * the interval could still reach `Builder.establish()` after the session had been
     * logically torn down.
     *
     * Deliberately does **not** touch [handle] or [descriptorOwner]:
     *  - it never closes a descriptor, because closing and unregistering must stay one
     *    coordinated operation performed by [release];
     *  - it leaves the descriptor releasable, so [release] can still identify and close
     *    exactly the descriptor this session installed.
     *
     * Idempotent, and never affects a different session: if a newer session has already
     * claimed, [installer] no longer matches and this call does nothing.
     *
     * An installation already inside the critical section finishes first and is then
     * covered by this revocation — establishment that began before invalidation
     * linearized is allowed to complete, and its descriptor is still releasable.
     */
    fun invalidate(session: Int) {
        synchronized(lock) {
            if (installer == session) installer = NO_SESSION
        }
    }

    /**
     * Establish a descriptor on behalf of [session], but only if [session] is still
     * permitted to establish.
     *
     * [establish] is invoked inside the critical section, so permission cannot be
     * revoked between the permission test and the establishment itself. A refused
     * installer therefore never calls [establish] at all and never creates a
     * descriptor, so there is nothing for it to clean up.
     */
    fun install(session: Int, establish: () -> T?): TunInstallResult<T> = synchronized(lock) {
        if (session == NO_SESSION || session != installer) {
            return@synchronized TunInstallResult.RefusedOwnership
        }
        // A same-session re-install without an intervening release would otherwise drop
        // the previous reference on the floor and leak it. Callers normally close their
        // own descriptor first (the reconnect path does), so this is normally a no-op on
        // an already-closed descriptor; doing it here makes the invariant the
        // coordinator's own responsibility rather than the caller's.
        closeHeldLocked()
        val created = establish() ?: return@synchronized TunInstallResult.EstablishFailed
        handle = created
        descriptorOwner = session
        TunInstallResult.Installed(created)
    }

    /**
     * Close and clear the descriptor, but only if [session] still owns it.
     *
     * Refusing when ownership has moved on is what stops a teardown belonging to an
     * obsolete session from destroying a newer session's descriptor. It stays keyed on
     * [descriptorOwner] rather than [installer], so a session whose installation
     * permission was revoked by [invalidate] can still release what it installed.
     *
     * The caller keeps ownership of whatever it still needs to release on its own.
     *
     * @return true if this call released the descriptor.
     */
    fun release(session: Int): Boolean = synchronized(lock) {
        if (session != descriptorOwner) return@synchronized false
        discardLocked()
        true
    }

    /**
     * Close and clear unconditionally. Used only by service destruction, where no
     * session survives and every descriptor must be released.
     */
    fun releaseAll() {
        synchronized(lock) { discardLocked() }
    }

    /** True while a descriptor is registered as a live service resource. */
    fun isHeld(): Boolean = synchronized(lock) { handle != null }

    /**
     * The session that owns the registered descriptor, or [NO_SESSION].
     *
     * Stage 2F-F.28L: this reports descriptor ownership, which survives [invalidate].
     * Use [installerSession] to ask who may still establish.
     */
    fun ownerSession(): Int = synchronized(lock) { descriptorOwner }

    /** The session currently permitted to begin an installation, or [NO_SESSION]. */
    fun installerSession(): Int = synchronized(lock) { installer }

    /**
     * Close and unregister the held descriptor, leaving ownership and permission
     * untouched. Must be called with [lock] held.
     */
    private fun closeHeldLocked() {
        val discarded = handle
        handle = null
        if (discarded != null) {
            // A descriptor that cannot be closed must not abort the release: the
            // ownership record still has to be cleared, otherwise the start gate would
            // keep refusing forever.
            runCatching { close(discarded) }
        }
    }

    private fun discardLocked() {
        closeHeldLocked()
        descriptorOwner = NO_SESSION
        installer = NO_SESSION
    }
}

/** Outcome of a [TunOwnershipCoordinator.install] attempt. */
sealed interface TunInstallResult<out T> {

    /** The descriptor was created and is now registered as a live resource. */
    data class Installed<T>(val handle: T) : TunInstallResult<T>

    /**
     * The caller's session no longer owns TUN establishment. Nothing was created, so
     * the caller owns nothing to clean up and must not treat this as a service fault.
     */
    object RefusedOwnership : TunInstallResult<Nothing>

    /** The session owned establishment but the platform declined to create a descriptor. */
    object EstablishFailed : TunInstallResult<Nothing>
}