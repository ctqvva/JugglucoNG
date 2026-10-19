package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration-style JVM harness for the N1 startup subset. Unlike the predicate
 * tests, this compiles the actual Notify foreground-startup/restore/fornotify
 * method bodies (extracted from Notify.java) against recording stub Android
 * types, with a controllable worker queue and blockable resolver/render fakes.
 *
 * Covered production wiring: placeholder-first promotion before any blocked
 * history query, ticket invalidation by any genuine fornotify publication,
 * superseded epochs, stop/destroy invalidation, Wear synchronous path, pinned
 * snapshot time passed to the renderer, honest stale/waiting/no-sensor statuses,
 * history-failure propagation, and header-timestamp application.
 */
class NotificationStartupRestoreTests {
    private fun harness(): Any {
        val h = compiled.getConstructor().newInstance()
        setStaticOwner("NotificationHistorySource", h)
        setStaticOwner("GlucoseUpdateBroadcaster", h)
        setStaticOwner("OngoingNotificationAccess", h)
        setStaticOwner("Service", h)
        setStaticOwner("Log", h)
        call(h, "resetStatics")
        call(h, "openGate", "resolver")
        call(h, "openGate", "render")
        return h
    }

    private fun setStaticOwner(nested: String, h: Any) {
        val cls = compiled.declaredClasses.first { it.simpleName == nested }
        val f = cls.getField("owner")
        f.isAccessible = true
        f.set(null, h)
    }

    private fun get(h: Any, name: String): Any? {
        val f = h.javaClass.getField(name)
        return f.get(h)
    }

    private fun set(h: Any, name: String, value: Any?) {
        h.javaClass.getField(name).set(h, value)
    }

    private fun call(h: Any, name: String, vararg args: Any?): Any? {
        val m = h.javaClass.methods.first { it.name == name && it.parameterCount == args.size }
        return m.invoke(h, *args)
    }

    private fun staticCall(name: String, vararg args: Any?): Any? {
        val m = compiled.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return m.invoke(null, *args)
    }

    private fun nested(name: String): Class<*> =
        compiled.declaredClasses.first { it.simpleName == name }

    private fun nestNew(nested: String, vararg args: Pair<Class<*>?, Any?>): Any {
        val cls = nested(nested)
        return nestNewIn(cls, *args)
    }

    private fun nestNewIn(cls: Class<*>, vararg args: Pair<Class<*>?, Any?>): Any {
        val ctor = cls.getDeclaredConstructor(*args.map { it.first!! }.toTypedArray())
        ctor.isAccessible = true
        return ctor.newInstance(*args.map { it.second }.toTypedArray())
    }

    private fun nestField(obj: Any, field: String): java.lang.reflect.Field {
        val f = obj.javaClass.getField(field)
        f.isAccessible = true
        return f
    }

    private fun newService(h: Any): Any = nestNew("Service")

    private fun serviceCalls(svc: Any): List<*> {
        @Suppress("UNCHECKED_CAST")
        return nestField(svc, "calls").get(svc) as List<*>
    }

    private fun events(h: Any): List<*> {
        @Suppress("UNCHECKED_CAST")
        return get(h, "events") as List<*>
    }

    private fun queued(h: Any): Int = (get(h, "glucoseRefreshHandler") as Any).let {
        val m = it.javaClass.getDeclaredMethod("queued")
        m.isAccessible = true
        m.invoke(it) as Int
    }

    private fun drainOne(h: Any, timeoutMs: Long = 5000): Boolean {
        val handler = get(h, "glucoseRefreshHandler")!!
        val take = handler.javaClass.getDeclaredMethod("takeNext", Long::class.javaPrimitiveType)
        take.isAccessible = true
        val r = take.invoke(handler, timeoutMs) as Runnable?
        if (r == null) return false
        r.run()
        return true
    }

    private fun poll(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    private fun snapshotAt(time: Long, value: Float = 100f): Any {
        val cls = nested("CurrentDisplaySource").declaredClasses.first { it.simpleName == "Snapshot" }
        return nestNewIn(cls,
            Long::class.javaPrimitiveType to time,
            Float::class.javaPrimitiveType to value)
    }

    private fun pointAt(time: Long): Any =
        nestNew("GlucosePoint", Long::class.javaPrimitiveType to time)

    private fun foregroundno(h: Any, svc: Any) {
        call(h, "setCurrentService", svc)
        h.javaClass.getMethod("foregroundno", nested("Service")).invoke(h, svc)
    }

    private fun fornotify(h: Any, notif: Any) {
        h.javaClass.getMethod("fornotify", nested("Notification")).invoke(h, notif)
    }

    private fun newNotification(h: Any): Any = nestNew("Notification")

    private fun lastServiceNotification(svc: Any): Any? =
        nestField(svc, "last").get(svc)

    private fun titleOf(notif: Any?): String? =
        if (notif == null) null else nestField(notif, "title").get(notif) as String?

    private fun boolOf(notif: Any?, field: String): Boolean =
        nestField(notif!!, field).getBoolean(notif)

    private fun whenOf(notif: Any?): Long =
        nestField(notif!!, "when").getLong(notif)

    @Test fun promotionPrecedesBlockedHistoryQuery() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedSnapshot", snapshotAt(now - 60_000L))
        call(h, "closeGate", "resolver")
        val svc = newService(h)
        foregroundno(h, svc)
        // Placeholder published synchronously with truthful loading state.
        assertEquals(listOf("fg:81431"), serviceCalls(svc))
        val placeholder = lastServiceNotification(svc)
        assertEquals("Loading...", titleOf(placeholder))
        assertFalse(boolOf(placeholder, "showWhen"))
        assertTrue(boolOf(placeholder, "ongoing"))
        assertEquals(1, queued(h))
        // The restore is stuck inside the blocked resolver: no second publish.
        val w = thread { drainOne(h) }
        poll(5000) { get(h, "resolverEntered") as Boolean }
        Thread.sleep(100)
        assertEquals("no publish while history query is blocked", 1, serviceCalls(svc).size)
        // Ordering: promotion event strictly before resolver entry.
        val order = events(h)
        assertTrue(order.indexOf("fg:81431") < order.indexOf("resolve"))
        call(h, "openGate", "resolver")
        w.join(5000)
        assertFalse(w.isAlive)
        // Restore rendered the resolved reading through the quiet visual path.
        assertEquals(listOf("fg:81431", "fg:81431"), serviceCalls(svc))
        assertEquals(listOf("fg:81431", "resolve", "render", "fg:81431"), events(h))
        assertEquals(0, get(h, "broadcastSends") as Int)
    }

    @Test fun freshRestoreRendersPinnedSnapshotTime() {
        val h = harness()
        val now = System.currentTimeMillis()
        val readingTime = now - 45_000L
        set(h, "cannedSnapshot", snapshotAt(readingTime, 132f))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        val render = get(h, "lastRender")!!
        val rc = render.javaClass
        fun rf(name: String) = rc.getField(name).also { it.isAccessible = true }
        assertEquals(132f, rf("value").getFloat(render))
        assertEquals(readingTime, rf("glucoseTime").getLong(render))
        assertEquals(true, rf("once").getBoolean(render))
        assertEquals("glucoseNotification", rf("type").get(render))
        assertEquals(2, serviceCalls(svc).size)
    }

    @Test fun genuinePublicationDuringRenderInvalidatesPendingRestore() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedSnapshot", snapshotAt(now - 30_000L))
        call(h, "closeGate", "render")
        val svc = newService(h)
        foregroundno(h, svc)
        val w = thread { drainOne(h) }
        poll(5000) { get(h, "renderEntered") as Boolean }
        // A genuine foreground publication lands while the restore renders.
        val genuine = newNotification(h)
        fornotify(h, genuine)
        call(h, "openGate", "render")
        w.join(5000)
        assertFalse(w.isAlive)
        // Placeholder plus genuine post; the pending restore must not post a third time.
        assertEquals(2, serviceCalls(svc).size)
        assertSame(genuine, lastServiceNotification(svc))
        assertEquals("genuine path fans out, restore path does not", 1, get(h, "broadcastSends") as Int)
    }

    @Test fun repeatedStartupSupersedesQueuedRestore() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedSnapshot", snapshotAt(now - 20_000L))
        call(h, "closeGate", "resolver")
        val svc = newService(h)
        foregroundno(h, svc)
        val w = thread { drainOne(h) }
        poll(5000) { get(h, "resolverEntered") as Boolean }
        // Second foreground entry while the first restore is still resolving.
        foregroundno(h, svc)
        assertEquals(listOf("fg:81431", "fg:81431"), serviceCalls(svc))
        call(h, "openGate", "resolver")
        w.join(5000)
        assertFalse(w.isAlive)
        // The superseded restore resolved but never rendered nor published.
        assertEquals(0, get(h, "renderCalls") as Int)
        assertEquals(2, serviceCalls(svc).size)
        assertTrue("second epoch restores", drainOne(h))
        assertEquals(1, get(h, "renderCalls") as Int)
        assertEquals(3, serviceCalls(svc).size)
    }

    @Test fun queuedRestoreDoesNotAdoptNewerEpoch() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedSnapshot", snapshotAt(now - 20_000L))
        val svc = newService(h)
        foregroundno(h, svc)
        foregroundno(h, svc)
        assertEquals(listOf("fg:81431", "fg:81431"), serviceCalls(svc))
        // The first queued restore carries the superseded ticket: it must drop
        // before resolving, never adopt the newer epoch and never publish.
        assertTrue(drainOne(h))
        assertEquals(0, get(h, "resolverCalls") as Int)
        assertEquals(2, serviceCalls(svc).size)
        assertTrue(drainOne(h))
        assertEquals(1, get(h, "resolverCalls") as Int)
        assertEquals(1, get(h, "renderCalls") as Int)
        assertEquals(3, serviceCalls(svc).size)
    }

    @Test fun stopInvalidationDropsPendingRestore() {
        val h = harness()
        set(h, "cannedSnapshot", snapshotAt(System.currentTimeMillis() - 20_000L))
        call(h, "closeGate", "resolver")
        val svc = newService(h)
        foregroundno(h, svc)
        val w = thread { drainOne(h) }
        poll(5000) { get(h, "resolverEntered") as Boolean }
        // Exactly what keeprunning.stopper/onDestroy call, under the same gate.
        staticCall("invalidateStartupRestore", svc)
        call(h, "openGate", "resolver")
        w.join(5000)
        assertFalse(w.isAlive)
        assertEquals(0, get(h, "renderCalls") as Int)
        assertEquals(1, serviceCalls(svc).size)
    }

    @Test fun sameReadingRestoresOnFreshEpochAfterGenuineTraffic() {
        val h = harness()
        val now = System.currentTimeMillis()
        // Genuine traffic first (invalidates nothing pending, fans out once).
        fornotify(h, newNotification(h))
        assertEquals(1, get(h, "broadcastSends") as Int)
        // A new service epoch may restore the same reading again.
        set(h, "cannedSnapshot", snapshotAt(now - 90_000L))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(2, serviceCalls(svc).size)
        assertEquals(1, get(h, "renderCalls") as Int)
    }

    @Test fun destroyingOldServiceDoesNotCancelReplacementRestore() {
        val h = harness()
        set(h, "cannedSnapshot", snapshotAt(System.currentTimeMillis() - 20_000L))
        val old = newService(h)
        val replacement = newService(h)
        foregroundno(h, old)
        foregroundno(h, replacement)
        staticCall("invalidateStartupRestore", old)
        assertTrue(drainOne(h))
        assertTrue(drainOne(h))
        assertEquals(1, serviceCalls(old).size)
        assertEquals(2, serviceCalls(replacement).size)
    }

    @Test fun placeholderRestoreAndGenuinePostsSharePublicationGate() {
        val h = harness()
        set(h, "cannedSnapshot", snapshotAt(System.currentTimeMillis() - 20_000L))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        call(h, "setCurrentService", svc)
        fornotify(h, newNotification(h))
        assertEquals(listOf(true, true, true), nestField(svc, "publicationLocks").get(svc))
        call(h, "setCurrentService", null)
        fornotify(h, newNotification(h))
        val manager = get(h, "notificationManager")!!
        assertTrue(nestField(manager, "publicationHeldLock").getBoolean(manager))
    }

    @Test fun startupRendererUsesClassifiedSnapshotWithoutResolvingAgain() {
        val h = harness()
        val readingTime = System.currentTimeMillis() - 20_000L
        set(h, "cannedSnapshot", snapshotAt(readingTime, 110f))
        set(h, "subsequentSnapshot", snapshotAt(readingTime - 600_000L, 180f))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals("the startup renderer must use the classified snapshot", 1, get(h, "resolverCalls"))
        assertEquals(readingTime, get(h, "renderedSnapshotTime"))
    }

    @Test fun wearKeepsSynchronousPathWithoutPlaceholderOrQueue() {
        val h = harness()
        set(h, "isWearable", true)
        val svc = newService(h)
        foregroundno(h, svc)
        assertEquals(1, get(h, "attachCalls") as Int)
        assertEquals(listOf("fg:81431"), serviceCalls(svc))
        assertEquals(true, boolOf(lastServiceNotification(svc), "startup"))
        assertEquals(0, queued(h))
        assertEquals(false, get(h, "resolverEntered"))
    }

    @Test fun staleSnapshotYieldsTimestampedStatusWithoutValueRender() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedSnapshot", snapshotAt(now - 400_000L, 150f))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals("stale must not render a value", 0, get(h, "renderCalls") as Int)
        assertEquals(2, serviceCalls(svc).size)
        val status = lastServiceNotification(svc)
        val title = titleOf(status)
        assertTrue("status carries actual time: $title", title!!.startsWith("No new value since "))
        assertTrue(title.length > "No new value since ".length + 3)
        assertTrue(boolOf(status, "showWhen"))
        assertEquals(now - 400_000L, whenOf(status))
    }

    @Test fun nullSnapshotWithStaleHistoryYieldsTimestampedStatus() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedSnapshot", null)
        set(h, "history", arrayListOf(pointAt(now - 600_000L)))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(0, get(h, "renderCalls") as Int)
        assertTrue(titleOf(lastServiceNotification(svc))!!.startsWith("No new value since "))
    }

    @Test fun nullSnapshotWithFreshHistoryWaitsWithoutInventedValue() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedSnapshot", null)
        set(h, "history", arrayListOf(pointAt(now - 60_000L)))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(0, get(h, "renderCalls") as Int)
        assertEquals("Loading...", titleOf(lastServiceNotification(svc)))
    }

    @Test fun absentSensorReportsNoSensor() {
        val h = harness()
        set(h, "cannedSnapshot", null)
        set(h, "sensorSerial", null)
        set(h, "history", arrayListOf<Any>())
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals("No sensors connected", titleOf(lastServiceNotification(svc)))
    }

    @Test fun cachedReadingWithoutSensorIdentityReportsNoSensor() {
        val h = harness()
        set(h, "cannedSnapshot", snapshotAt(System.currentTimeMillis() - 60_000L, 150f))
        set(h, "sensorSerial", null)
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(0, get(h, "renderCalls") as Int)
        assertEquals("No sensors connected", titleOf(lastServiceNotification(svc)))
    }

    @Test fun historyFailurePropagatesLoggedWithPlaceholderKept() {
        val h = harness()
        set(h, "cannedSnapshot", null)
        set(h, "historyThrow", true)
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertTrue("failure must be logged, not swallowed", (get(h, "stackCalls") as Int) > 0)
        assertEquals("no status or reading post on failure", 1, serviceCalls(svc).size)
    }

    private fun visualGeneration(): Long {
        val f = compiled.getDeclaredField("foregroundVisualGeneration")
        f.isAccessible = true
        return f.getLong(null)
    }

    private fun deadlineDelays(h: Any): List<Long> {
        val handler = get(h, "glucoseRefreshHandler")!!
        val f = handler.javaClass.getField("delays")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(handler) as List<Long>
    }

    @Test fun freshRestoreArmsSingleDeadlineOnDisplayedReading() {
        val h = harness()
        val now = System.currentTimeMillis()
        val readingTime = now - 20_000L
        set(h, "cannedSnapshot", snapshotAt(readingTime, 132f))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        // One deadline callback armed on the actual displayed reading, not a poll loop.
        val delays = deadlineDelays(h)
        assertEquals(1, delays.size)
        val delay = delays[0]
        assertTrue("deadline delay must be positive, was $delay", delay > 0L)
        assertTrue("deadline must be timeout+1ms past the reading, was $delay",
            delay <= 330_000L + 1L && delay >= 300_000L)
        // The displayed snapshot is retained for later staleness fallback.
        val retained = get(h, "lastDisplayedGlucoseSnapshot")!!
        val time = getNested(retained, "time") as Long
        assertEquals(readingTime, time)
    }

    @Test fun genuinePublicationBumpsVisualGeneration() {
        val h = harness()
        set(h, "cannedSnapshot", snapshotAt(System.currentTimeMillis() - 20_000L))
        val svc = newService(h)
        foregroundno(h, svc)
        val afterStartup = visualGeneration()
        fornotify(h, newNotification(h))
        assertTrue("genuine fornotify must invalidate older visual renders",
            visualGeneration() > afterStartup)
    }

    @Test fun headerTimestampApplicationOnRealBuilderCode() {
        val h = harness()
        val builderClass = nested("Notification").declaredClasses.first { it.simpleName == "Builder" }
        val apply = compiled.declaredMethods.first { it.name == "applyReadingHeaderTimestamp" }
        apply.isAccessible = true
        fun newBuilder(): Any {
            val ctor = builderClass.getDeclaredConstructor(Object::class.java, String::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(get(h, "app"), "glucoseNotification")
        }
        fun buildOf(builder: Any): Any {
            val m = builderClass.getDeclaredMethod("build")
            m.isAccessible = true
            return m.invoke(builder)
        }
        val readingTime = 1_700_000_000_123L
        val builder = newBuilder()
        apply.invoke(null, builder, readingTime)
        val built = buildOf(builder)
        assertEquals(readingTime, whenOf(built))
        assertTrue(boolOf(built, "showWhen"))
        val emptyBuilt = buildOf(newBuilder().also { apply.invoke(null, it, 0L) })
        assertFalse("no reading hides the header timestamp", boolOf(emptyBuilt, "showWhen"))
        assertEquals(0L, whenOf(emptyBuilt))
    }

    private fun invokePrivate(h: Any, name: String, vararg args: Any?): Any? {
        val method = compiled.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(h, *args)
    }

    @Test fun visualPublicationInvalidatesPendingStartupRestore() {
        val h = harness()
        set(h, "cannedSnapshot", snapshotAt(System.currentTimeMillis() - 1000L))
        val service = newService(h)
        foregroundno(h, service)
        val visual = newNotification(h)
        invokePrivate(h, "publishVisualNotification", visualGeneration(), service, visual, null)
        assertTrue(drainOne(h))
        assertSame("restore cannot overwrite the newer visual", visual, getNested(service, "last"))
        assertEquals("visual update never rebroadcasts a reading", 0, get(h, "broadcastSends"))
    }

    private fun getNested(value: Any, name: String): Any? = value.javaClass.getField(name).also { it.isAccessible = true }.get(value)

    @Test fun startupPublicationInvalidatesAlreadyRenderedVisual() {
        val h = harness()
        val service = newService(h)
        foregroundno(h, service)
        val oldGeneration = visualGeneration()
        assertTrue(drainOne(h))
        val restored = getNested(service, "last")
        invokePrivate(h, "publishVisualNotification", oldGeneration, service, newNotification(h), null)
        assertSame(restored, getNested(service, "last"))
    }

    @Test fun cancellationBelongsToCurrentServiceAndInvalidatesRunningRender() {
        val h = harness()
        val old = newService(h)
        val replacement = newService(h)
        call(h, "setCurrentService", replacement)
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        val generation = visualGeneration()
        invokePrivate(h, "cancelOngoingNotificationRefreshes", old)
        assertEquals(generation, visualGeneration())
        invokePrivate(h, "cancelOngoingNotificationRefreshes", replacement)
        assertTrue(visualGeneration() > generation)
        invokePrivate(h, "publishVisualNotification", generation, replacement, newNotification(h), null)
        assertNull(getNested(replacement, "last"))
        assertEquals(0, call(h, "queuedCount"))
    }

    @Test fun overdueDataAndStatusCallbacksKeepTheirQueueSlots() {
        val h = harness()
        invokePrivate(h, "scheduleDataChangedNotificationRefresh")
        invokePrivate(h, "scheduleStatusChangedNotificationRefresh")
        set(h, "uptime", 50_000L)
        invokePrivate(h, "scheduleDataChangedNotificationRefresh")
        invokePrivate(h, "scheduleStatusChangedNotificationRefresh")
        assertEquals("overdue callbacks are still owned", 2, call(h, "queuedCount"))
    }

    @Test fun failedVisualPublicationRetriesOnceWithoutMarkingSnapshotDisplayed() {
        val h = harness()
        val snapshot = snapshotAt(System.currentTimeMillis() - 1000L)
        set(h, "cannedSnapshot", snapshot)
        set(h, "failPublication", true)
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        assertTrue(drainOne(h))
        assertNull(get(h, "lastDisplayedGlucoseSnapshot"))
        assertEquals(1, call(h, "queuedCount"))
        assertTrue(drainOne(h))
        assertEquals("persistent failure cannot spin", 0, call(h, "queuedCount"))
        assertNull(get(h, "lastDisplayedGlucoseSnapshot"))
    }

    @Test fun missingSensorOrChangedUnitsCannotReuseRetainedReading() {
        val h = harness()
        val snapshot = snapshotAt(System.currentTimeMillis() - 1000L)
        set(h, "lastDisplayedGlucoseSnapshot", snapshot)
        assertNull(invokePrivate(h, "effectiveDisplaySnapshot", snapshot, null))
        assertSame(snapshot, invokePrivate(h, "effectiveDisplaySnapshot", null, "sensor-1"))
        call(h, "setMmol", true)
        assertNull(invokePrivate(h, "effectiveDisplaySnapshot", null, "sensor-1"))
        call(h, "setMmol", false)
        set(h, "modeFailure", true)
        assertNull(invokePrivate(h, "effectiveDisplaySnapshot", null, "sensor-1"))
    }

    @Test fun genuinePublicationCommitsReadingOnlyAfterSuccessAndArmsSilentExpiry() {
        val h = harness()
        val time = System.currentTimeMillis() - 1000L
        val reading = nestNew("notGlucose", Long::class.javaPrimitiveType to time)
        set(h, "cannedSnapshot", snapshotAt(time, 123f))
        set(h, "failPublication", true)
        try {
            invokePrivate(h, "postForegroundGlucoseNotification", -1, 123f, "123", reading, false)
            fail("expected publisher failure")
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            assertTrue(failure.cause is IllegalStateException)
        }
        assertEquals(0L, get(h, "lastForegroundGlucoseTimeMs"))
        assertNull(get(h, "lastDisplayedGlucoseSnapshot"))
        set(h, "failPublication", false)
        assertTrue("quiet retry runs without another reading event", drainOne(h))
        assertEquals(1, call(h, "queuedCount"))
        assertNotNull(get(h, "lastDisplayedGlucoseSnapshot"))
        assertEquals("recovery is visual-only", 0, get(h, "broadcastSends"))
    }

    @Test fun noInputDeadlineReplacesCurrentPresentationWithStaleReading() {
        val h = harness()
        val snapshot = snapshotAt(System.currentTimeMillis() - 1000L)
        set(h, "cannedSnapshot", snapshot)
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        assertTrue(drainOne(h))
        assertEquals(1, call(h, "queuedCount"))
        nestField(snapshot, "time").setLong(snapshot, System.currentTimeMillis() - 331_000L)
        // Run the existing deadline callback after time has advanced; no new reading event.
        assertTrue(drainOne(h))
        assertTrue(drainOne(h))
        val manager = get(h, "notificationManager")!!
        val stale = getNested(manager, "last")!!
        assertTrue((getNested(stale, "title") as String).startsWith("No new value"))
        assertEquals(getNested(snapshot, "time"), getNested(stale, "when"))
        assertEquals(0, get(h, "broadcastSends"))
        assertEquals(0, call(h, "queuedCount"))
    }

    @Test fun dequeuedCallbacksCannotRescheduleAfterCurrentServiceCancellation() {
        for (method in listOf("scheduleDataChangedNotificationRefresh", "scheduleStatusChangedNotificationRefresh", "scheduleVisualNotificationRefresh")) {
            val h = harness()
            val service = newService(h)
            call(h, "setCurrentService", service)
            invokePrivate(h, method)
            val handler = get(h, "glucoseRefreshHandler")!!
            val take = handler.javaClass.getDeclaredMethod("takeNext", Long::class.javaPrimitiveType).also { it.isAccessible = true }
            val dequeued = take.invoke(handler, 1L) as Runnable
            invokePrivate(h, "cancelOngoingNotificationRefreshes", service)
            call(h, "setCurrentService", null)
            val generation = visualGeneration()
            dequeued.run()
            assertEquals(generation, visualGeneration())
            assertEquals(0, call(h, "queuedCount"))
        }
    }

    @Test fun wearStoredReadingKeepsAlarmIdAndOngoingAdapter() {
        val h = harness()
        set(h, "isWearable", true)
        val time = System.currentTimeMillis() - 1000L
        val reading = nestNew("notGlucose", Long::class.javaPrimitiveType to time)
        invokePrivate(h, "postForegroundGlucoseNotification", -1, 123f, "123", reading, true)
        val manager = get(h, "notificationManager")!!
        assertEquals(listOf("notify:81432"), getNested(manager, "calls"))
        assertEquals(1, get(h, "updateCalls"))
        assertEquals(0, call(h, "queuedCount"))
    }

    @Test fun startupPublicationFailureGetsQuietRetryWithoutNewInput() {
        val h = harness()
        val service = newService(h)
        set(h, "cannedSnapshot", snapshotAt(System.currentTimeMillis() - 1000L))
        foregroundno(h, service)
        set(h, "failPublication", true)
        assertTrue(drainOne(h))
        assertNull(get(h, "lastDisplayedGlucoseSnapshot"))
        set(h, "failPublication", false)
        assertTrue(drainOne(h))
        assertNotNull(get(h, "lastDisplayedGlucoseSnapshot"))
        assertEquals(0, get(h, "broadcastSends"))
    }

    @Test fun noSensorWithoutServiceCancelsInsteadOfPublishingPlaceholder() {
        val h = harness()
        set(h, "sensorSerial", null)
        set(h, "cannedSnapshot", snapshotAt(System.currentTimeMillis() - 1000L))
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        assertTrue(drainOne(h))
        val manager = get(h, "notificationManager")!!
        assertEquals(listOf("cancel:81431"), getNested(manager, "calls"))
        assertNull(getNested(manager, "last"))
        assertEquals(0, call(h, "queuedCount"))
    }

    companion object {
        private val compiled: Class<*> by lazy {
            val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            val regionStart = source.indexOf("    // N1-STARTUP-REGION-BEGIN")
            check(regionStart >= 0) { "startup region begin marker missing" }
            val regionEnd = source.indexOf("    // N1-STARTUP-REGION-END")
            check(regionEnd > regionStart) { "startup region end marker missing" }
            val region = source.substring(regionStart, regionEnd)
            val refreshPolicy = File(root, "Common/src/main/java/tk/glucodata/NotificationRefreshPolicy.java")
                .readText().substringAfter("package tk.glucodata;")
                .replace("final class NotificationRefreshPolicy", "static final class NotificationRefreshPolicy")
            val lifecycle = source.substring(
                source.indexOf("    private final Runnable dataChangedGlucoseRefreshRunnable"),
                source.indexOf("    private static final int MIN_ALERT_DURATION_SECONDS"))
                .replace("final Notify noti", "final NotifyStartupHarness noti")
            val fnSig = "    void fornotify(Notification notif) {"
            val fnStart = source.indexOf(fnSig)
            check(fnStart >= 0) { "fornotify not found" }
            val fnEndAnchor = source.indexOf(
                "    // static final long glucosetimeout=1000*60*3;", fnStart)
            check(fnEndAnchor > fnStart) { "fornotify end anchor missing" }
            val fnClose = source.lastIndexOf("\n    }", fnEndAnchor)
            check(fnClose > fnStart) { "fornotify close brace missing" }
            val fornotifyBody = source.substring(fnStart + fnSig.length, fnClose)
            val displayStart = source.indexOf("final CurrentDisplaySource.Snapshot resolvedDisplay =")
            check(displayStart >= 0)
            val displaySelection = source.substring(displayStart, source.indexOf(';', displayStart) + 1)
            val dir = java.nio.file.Files.createTempDirectory("notify-startup-restore-test").toFile()
            val file = File(dir, "NotifyStartupHarness.java")
            file.writeText("""
                package tk.glucodata;
                import static java.lang.String.format;
                import java.util.*;
                public class NotifyStartupHarness {
                    public static boolean isWearable = false;
                    public boolean doLog = false;
                    public static String LOG_ID = "test";
                    public static java.util.Locale usedlocale = java.util.Locale.ROOT;
                    public static String glucoseformat = "%.0f";
                    public static java.text.DateFormat timef =
                        new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT);
                    public static int FOREGROUND_GLUCOSE_NOTIFICATION_KIND = -1;
                    public static String GLUCOSENOTIFICATION = "glucoseNotification";
                    public static long glucosetimeout = 330000L;
                    public static int glucosenotificationid = 81431;
                    public static int glucosealarmid = 81432;
                    public static final int FLAG_ONGOING_EVENT = 2;
                    public static int VISIBILITY_PUBLIC = 1;
                    public TestApp app = new TestApp();
                    public static FakeHandler glucoseRefreshHandler = new FakeHandler();
                    public FakeManager notificationManager = new FakeManager();
                    public int broadcastSends = 0;
                    public int attachCalls = 0;
                    public int updateCalls = 0;
                    public int stackCalls = 0;
                    public List<String> events = Collections.synchronizedList(new ArrayList<>());
                    public String sensorSerial = "sensor-1";
                    public int primaryCalls = 0;
                    public boolean resolverEntered = false;
                    public volatile boolean resolverGateOpen = true;
                    public int resolverCalls = 0;
                    public boolean resolverThrow = false;
                    public CurrentDisplaySource.Snapshot cannedSnapshot = null;
                    public CurrentDisplaySource.Snapshot subsequentSnapshot = null;
                    public long renderedSnapshotTime;
                    public List<GlucosePoint> history = new ArrayList<>();
                    public int historyCalls = 0;
                    public boolean historyThrow = false;
                    public boolean renderEntered = false;
                    public volatile boolean renderGateOpen = true;
                    public int renderCalls = 0;
                    public RenderCall lastRender = null;
                    public boolean startupContentCalled = false;
                    public CurrentDisplaySource.Snapshot lastDisplayedGlucoseSnapshot = null;
                    public int deadlineFires = 0;
                    public static NotifyStartupHarness onenot;
                    public static boolean showalways = true, alertwatch = false;
                    public boolean hasvalue;
                    public long lastForegroundGlucoseTimeMs, pendingDataRefreshAtUptimeMs, pendingStatusRefreshAtUptimeMs;
                    public float lastForegroundGlucoseValue, lastForegroundGlucoseRate;
                    public boolean visualRefreshPending;
                    public long pendingVisualGeneration, publicationRetryGeneration;
                    public Service pendingVisualService, publicationRetryService, freshnessDeadlineService;
                    public long freshnessDeadlineGeneration;
                    public boolean interactiveRefreshPending;
                    public static final long DATA_CHANGED_NOTIFICATION_REFRESH_DELAY_MS = 1000L;
                    public static final long FAILED_NOTIFICATION_RETRY_DELAY_MS = 5000L;
                    public static final long INTERACTIVE_NOTIFICATION_REFRESH_DELAY_MS = 750L;
                    public Runnable glucoseRefreshRunnable = () -> {};
                    public static Runnable storedGlucoseRefresh = () -> {};
                    public boolean failPublication, modeFailure;
                    public long uptime = 10_000L;
                    public static class android { public static class os { public static class SystemClock {
                        public static long uptimeMillis() { return onenot.uptime; }
                    } } }
                    public int queuedCount() { return glucoseRefreshHandler.queued(); }
                    public void setMmol(boolean value) { Applic.unit = value ? 1 : 0; }
                    private boolean isScreenInteractive() { return true; }
                    private String resolveNotificationSensorSerial() { return sensorSerial; }
                    private String resolveNotificationStatusText(String serial, String fallback) { return fallback; }
                    private CurrentDisplaySource.Snapshot resolveNotificationCurrentSnapshot() {
                        return resolveNotificationCurrentSnapshot(sensorSerial);
                    }
                    $refreshPolicy
                    $lifecycle
                    private final Object resolverLock = new Object();
                    private final Object renderLock = new Object();
                    static class R {
                        static class string {
                            static final int loading_data = 1;
                            static final int no_sensors_connected = 2;
                            static final int nonewvalue = 3;
                        }
                        static class drawable { static final int novalue = 10; }
                    }
                    static class Build {
                        static class VERSION { static int SDK_INT = 34; }
                        static class VERSION_CODES { static final int O = 26, LOLLIPOP = 21; }
                    }
                    static class TestApp {
                        String getString(int id) {
                            if (id == R.string.loading_data) return "Loading...";
                            if (id == R.string.no_sensors_connected) return "No sensors connected";
                            return "No new value since ";
                        }
                    }
                    static class Applic {
                        static int unit = 0;
                        static TestApp app = new TestApp();
                        static TestApp getContext() { return app; }
                    }
                    static class Log {
                        public static NotifyStartupHarness owner;
                        static void i(String a, String b) {}
                        static void e(String a, String b) {}
                        static void stack(String a, String b, Throwable t) { owner.stackCalls++; }
                    }
                    static class Service {
                        public static NotifyStartupHarness owner;
                        public List<String> calls = new ArrayList<>();
                        public Notification last;
                        public List<Boolean> publicationLocks = new ArrayList<>();
                        public void startForeground(int id, Notification n) {
                            if (owner.failPublication) throw new IllegalStateException("publisher down");
                            publicationLocks.add(Thread.holdsLock(foregroundPublicationLock));
                            calls.add("fg:" + id);
                            owner.events.add("fg:" + id);
                            last = n;
                        }
                        public void startForeground(int id, Notification n, int type) {
                            calls.add("fg:" + id + ":" + type);
                            owner.events.add("fg:" + id);
                            last = n;
                        }
                    }
                    static class Notification {
                        static final String CATEGORY_SERVICE = "service";
                        public String title;
                        public boolean showWhen = true;
                        public boolean ongoing = false;
                        public boolean startup = false;
                        public boolean rendered = false;
                        public String category;
                        public long when = 123L;
                        public int flags = 0;
                        static class Builder {
                            Notification n = new Notification();
                            Builder(Object ctx, String type) {}
                            Builder(Object ctx) {}
                            Builder setSmallIcon(int d) { return this; }
                            Builder setOnlyAlertOnce(boolean b) { return this; }
                            Builder setContentTitle(String t) { n.title = t; return this; }
                            Builder setShowWhen(boolean b) { n.showWhen = b; return this; }
                            Builder setOngoing(boolean b) { n.ongoing = b; return this; }
                            Builder setVisibility(int v) { return this; }
                            Builder setCategory(String c) { n.category = c; return this; }
                            Builder setWhen(long w) { n.when = w; return this; }
                            Notification build() { return n; }
                        }
                    }
                    static class FakeHandler {
                        List<Runnable> queue = new ArrayList<>();
                        public List<Long> delays = new ArrayList<>();
                        synchronized void post(Runnable r) { queue.add(r); notifyAll(); }
                        synchronized void postDelayed(Runnable r, long delayMs) {
                            delays.add(delayMs);
                            queue.add(r);
                            notifyAll();
                        }
                        synchronized void removeCallbacks(Runnable r) { queue.remove(r); }
                        synchronized int queued() { return queue.size(); }
                        public synchronized Runnable takeNext(long timeoutMs) throws InterruptedException {
                            long end = System.currentTimeMillis() + timeoutMs;
                            while (queue.isEmpty()) {
                                long w = end - System.currentTimeMillis();
                                if (w <= 0) return null;
                                wait(w);
                            }
                            return queue.remove(0);
                        }
                    }
                    static class FakeManager {
                        public List<String> calls = new ArrayList<>();
                        public Notification last;
                        public boolean publicationHeldLock;
                        void cancel(int id) { calls.add("cancel:" + id); last = null; }
                        void notify(int id, Notification n) {
                            if (onenot.failPublication) throw new IllegalStateException("publisher down");
                            publicationHeldLock = Thread.holdsLock(foregroundPublicationLock);
                            calls.add("notify:" + id); last = n;
                        }
                    }
                    static class GlucoseUpdateBroadcaster {
                        public static NotifyStartupHarness owner;
                        static void send(Object app) { owner.broadcastSends++; }
                    }
                    static class OngoingNotificationAccess {
                        public static NotifyStartupHarness owner;
                        static OngoingNotificationAccess shared = new OngoingNotificationAccess();
                        static OngoingNotificationAccess get() { return shared; }
                        Notification attach(Service s, Notification n, int id) {
                            owner.attachCalls++;
                            return n;
                        }
                        void updateStatus(Object app, int id) { owner.updateCalls++; }
                    }
                    static class keeprunning {
                        static Service theservice = null;
                        static boolean started = false;
                    }
                    static class CurrentDisplaySource {
                        static int resolveViewModeForSensor(String serial) {
                            if (onenot.modeFailure) throw new IllegalStateException("mode unavailable");
                            return 0;
                        }
                        static class Snapshot {
                            public long time;
                            public float value;
                            public Snapshot(long t, float v) { time = t; value = v; }
                            public long getTimeMillis() { return time; }
                            public float getPrimaryValue() { return value; }
                            public float getRate() { return 0f; }
                            public boolean isMmol() { return false; }
                            public String getSensorId() { return "sensor-1"; }
                            public int getViewMode() { return 0; }
                        }
                    }
                    static class GlucosePoint {
                        public long timestamp;
                        public GlucosePoint(long t) { timestamp = t; }
                    }
                    static class DisplayTrendSource { static final long TREND_WINDOW_MS = 1500000L; }
                    static class NotificationHistorySource {
                        public static NotifyStartupHarness owner;
                        static String resolveSensorSerial(String s) { return owner.sensorSerial; }
                        static List<GlucosePoint> getDisplayHistory(long start, boolean mmol, String serial) {
                            owner.historyCalls++;
                            if (owner.historyThrow) throw new RuntimeException("room down");
                            return owner.history;
                        }
                    }
                    static class notGlucose {
                        public long time;
                        public float rate;
                        public notGlucose(long t) { time = t; }
                    }
                    static class RenderCall {
                        public int kind; public float value; public String message;
                        public long glucoseTime; public String type; public boolean once;
                    }
                    public void resetStatics() {
                        onenot = this;
                        glucoseRefreshHandler = new FakeHandler();
                        showalways = true; alertwatch = false; isWearable = false; Applic.unit = 0;
                        startupPendingTicket = 0L;
                        startupPendingService = null;
                        startupTicketCounter = 0L;
                        foregroundVisualGeneration = 0L;
                        lastDisplayedGlucoseSnapshot = null;
                        deadlineFires = 0;
                        broadcastSends = 0;
                        stackCalls = 0;
                        keeprunning.theservice = null;
                        keeprunning.started = false;
                        Build.VERSION.SDK_INT = 34;
                    }
                    public void setCurrentService(Service service) { keeprunning.theservice = service; }
                    public void openGate(String which) { setGate(which, true); }
                    public void closeGate(String which) { setGate(which, false); }
                    void setGate(String which, boolean open) {
                        Object lock = which.equals("resolver") ? resolverLock : renderLock;
                        synchronized (lock) {
                            if (which.equals("resolver")) resolverGateOpen = open;
                            else renderGateOpen = open;
                            lock.notifyAll();
                        }
                    }
                    void awaitGate(String which) {
                        Object lock = which.equals("resolver") ? resolverLock : renderLock;
                        synchronized (lock) {
                            long end = System.currentTimeMillis() + 8000;
                            while (!(which.equals("resolver") ? resolverGateOpen : renderGateOpen)) {
                                long w = end - System.currentTimeMillis();
                                if (w <= 0) throw new AssertionError("gate " + which + " never opened");
                                try { lock.wait(w); }
                                catch (InterruptedException e) { throw new AssertionError(e); }
                            }
                        }
                    }
                    String resolvePrimarySensorName() { primaryCalls++; return sensorSerial; }
                    CurrentDisplaySource.Snapshot resolveNotificationCurrentSnapshot(String serial) {
                        resolverCalls++;
                        resolverEntered = true;
                        events.add("resolve");
                        awaitGate("resolver");
                        if (resolverThrow) throw new RuntimeException("resolver down");
                        return resolverCalls > 1 && subsequentSnapshot != null ? subsequentSnapshot : cannedSnapshot;
                    }
                    long latestNotificationTimestamp(List<GlucosePoint> points) {
                        long latest = 0L;
                        for (GlucosePoint p : points) latest = Math.max(latest, p.timestamp);
                        return latest;
                    }
                    notGlucose toLegacyGlucose(CurrentDisplaySource.Snapshot s) {
                        return s == null ? null : new notGlucose(s.getTimeMillis());
                    }
                    Notification makearrownotification(int kind, float value, String message,
                            notGlucose glucose, String type, boolean once) {
                        return makearrownotification(kind, value, message, glucose, type, once, null);
                    }
                    Notification makearrownotification(int kind, float value, String message,
                            notGlucose glucose, String type, boolean once,
                            CurrentDisplaySource.Snapshot startupSnapshot) {
                        return makearrownotification(kind, value, message, glucose, type, once,
                                startupSnapshot, startupSnapshot != null);
                    }
                    Notification makearrownotification(int kind, float value, String message,
                            notGlucose glucose, String type, boolean once,
                            CurrentDisplaySource.Snapshot startupSnapshot, boolean snapshotAlreadyResolved) {
                        final String activeSensorSerial = sensorSerial;
                        $displaySelection
                        renderedSnapshotTime = resolvedDisplay == null ? 0L : resolvedDisplay.getTimeMillis();
                        renderCalls++;
                        renderEntered = true;
                        events.add("render");
                        RenderCall rc = new RenderCall();
                        rc.kind = kind; rc.value = value; rc.message = message;
                        rc.glucoseTime = glucose == null ? 0L : glucose.time;
                        rc.type = type; rc.once = once;
                        lastRender = rc;
                        awaitGate("render");
                        Notification n = new Notification();
                        n.rendered = true;
                        n.title = message;
                        return n;
                    }
                    static class GlucoseNotificationContent {
                        Notification notification; CurrentDisplaySource.Snapshot snapshot;
                        GlucoseNotificationContent(Notification n, CurrentDisplaySource.Snapshot s) { notification = n; snapshot = s; }
                    }
                    GlucoseNotificationContent renderGlucoseNotification(int kind, float value, String message,
                            notGlucose glucose, String type, boolean once,
                            CurrentDisplaySource.Snapshot snapshot, boolean alreadyResolved) {
                        Notification n = makearrownotification(kind, value, message, glucose, type, once, snapshot, alreadyResolved);
                        CurrentDisplaySource.Snapshot displayed = snapshot != null ? snapshot : new CurrentDisplaySource.Snapshot(glucose.time, value);
                        return new GlucoseNotificationContent(n, displayed);
                    }
                    Notification makeStaleReadingNotification(CurrentDisplaySource.Snapshot snapshot) {
                        Notification n = new Notification();
                        n.title = staleMessage(snapshot.getTimeMillis());
                        n.when = snapshot.getTimeMillis();
                        n.showWhen = true;
                        n.ongoing = true;
                        return n;
                    }
                    Notification getforgroundnotification() {
                        startupContentCalled = true;
                        Notification n = new Notification();
                        n.startup = true;
                        n.title = "startup-content";
                        return n;
                    }
                    void startForegroundService(Service s, int id, Notification n) {
                        s.startForeground(id, n);
                    }
                    Notification.Builder mkbuilder(String type) { return new Notification.Builder(app, type); }
                    $region
                    public void fornotify(Notification notif) {
                        $fornotifyBody
                    }
                }
            """.trimIndent())
            val compiler = ToolProvider.getSystemJavaCompiler()
            check(compiler != null) { "A JDK is required for the production restore harness" }
            val classpath = System.getProperty("java.class.path")
            val output = java.io.ByteArrayOutputStream()
            check(compiler.run(null, output, output, "-classpath", classpath,
                "-d", dir.path, file.path) == 0) { "harness compilation failed:\n$output" }
            URLClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
                .loadClass("tk.glucodata.NotifyStartupHarness")
        }
    }
}
