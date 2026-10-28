package tk.glucodata.drivers.ottai

import android.content.Context
import android.os.Handler
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OttaiRemovalTestApplication::class, shadows = [OttaiRemovalNatives::class])
class OttaiFirmwareShutdownTests {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val id = "AABBCCDDEEFF"
    private val address = "AA:BB:CC:DD:EE:FF"
    private val recoveryFile get() = File(context.filesDir, "ottai-ota/$id.bin")
    private val image get() = OttaiFirmwareImage("V1.7.0", "V1.8.0", OttaiOtaPackage.parse(testGbl()),
        OttaiFirmwareMode.STARTED_SENSOR)

    private fun session(): Pair<OttaiFirmwareSession, ExecutorService> {
        // Construct the real session without starting BLE or making any cloud requests.
        val constructor = OttaiFirmwareSession::class.java.getDeclaredConstructor(
            Context::class.java, String::class.java, String::class.java,
            OttaiRegistry.DeviceMaterials::class.java, OttaiFirmwareMode::class.java,
            kotlin.jvm.functions.Function1::class.java,
        ).apply { isAccessible = true }
        val publish: (OttaiFirmwareState) -> Unit = {}
        val session = constructor.newInstance(RuntimeEnvironment.getApplication(), "AABBCCDDEEFF",
            "AA:BB:CC:DD:EE:FF", OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.7.0", 1),
            OttaiFirmwareMode.STARTED_SENSOR, publish) as OttaiFirmwareSession
        val worker = OttaiFirmwareSession::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(session) as ExecutorService
        return session to worker
    }

    private fun saveJournal() {
        recoveryFile.parentFile!!.mkdirs()
        DataOutputStream(recoveryFile.outputStream()).use {
            OttaiFirmwareJournal.write(it, id, address, image)
        }
    }

    @Test fun disconnectAheadOfAnAcceptedDiscardStillCompletesItExactlyOnce() {
        val (session, _) = session()
        val handler = OttaiFirmwareSession::class.java.getDeclaredField("handler").apply { isAccessible = true }
            .get(session) as Handler
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        var callbacks = 0
        var result: Result<Unit>? = null
        saveJournal()
        try {
            assertTrue(handler.post {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                session.close(completed = false) // The disconnect callback queued ahead of discard.
                retired.countDown()
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.discardRecovery { callbacks++; result = it }
            release.countDown()
            assertTrue(retired.await(2, TimeUnit.SECONDS))
            ShadowLooper.idleMainLooper()
            assertEquals(1, callbacks)
            assertTrue(requireNotNull(result).isSuccess)
            assertFalse(OttaiFirmwareSession.hasPendingTransfer(context, id))
            session.close(completed = false)
            ShadowLooper.idleMainLooper()
            assertEquals(1, callbacks)
        } finally {
            release.countDown()
            session.close(completed = false)
            OttaiFirmwareSession.discardPendingTransfer(context, id)
        }
    }

    @Test fun discardAfterRetirementStillCompletes() {
        val (session, _) = session()
        saveJournal()
        session.close(completed = false)
        var callbacks = 0
        session.discardRecovery { assertTrue(it.isSuccess); callbacks++ }
        ShadowLooper.idleMainLooper()
        assertEquals(1, callbacks)
        assertFalse(OttaiFirmwareSession.hasPendingTransfer(context, id))
    }

    @Test fun discardThatRetiresItsOwnSessionCompletesOnlyOnce() {
        val (session, _) = session()
        val thread = OttaiFirmwareSession::class.java.getDeclaredField("thread").apply { isAccessible = true }
            .get(session) as Thread
        saveJournal()
        try {
            var callbacks = 0
            session.discardRecovery { assertTrue(it.isSuccess); callbacks++ }
            thread.join(2_000)
            assertFalse(thread.isAlive)
            ShadowLooper.idleMainLooper()
            assertEquals(1, callbacks)
            assertFalse(OttaiFirmwareSession.hasPendingTransfer(context, id))
        } finally {
            session.close(completed = false)
            OttaiFirmwareSession.discardPendingTransfer(context, id)
        }
    }

    @Test fun invalidOrWrongAddressJournalCannotBecomeANewPackage() {
        val (session, _) = session()
        try {
            assertNull(session.savedImage())
            saveJournal()
            assertEquals(image.image.sha256, session.savedImage()!!.image.sha256)
            val savedBytes = recoveryFile.readBytes()
            assertThrows(IllegalArgumentException::class.java) {
                session.markTransferStarted(image.copy(target = "V1.9.0"))
            }
            assertArrayEquals(savedBytes, recoveryFile.readBytes())
            DataOutputStream(recoveryFile.outputStream()).use {
                OttaiFirmwareJournal.write(it, id, "00:11:22:33:44:55", image)
            }
            val wrongAddressBytes = recoveryFile.readBytes()
            assertThrows(IllegalStateException::class.java) { session.savedImage() }
            assertThrows(IllegalStateException::class.java) { session.markTransferStarted(image) }
            assertArrayEquals(wrongAddressBytes, recoveryFile.readBytes())
            recoveryFile.writeBytes(byteArrayOf(1))
            assertThrows(IllegalStateException::class.java) { session.savedImage() }
            assertThrows(IllegalStateException::class.java) { session.markTransferStarted(image) }
            assertArrayEquals(byteArrayOf(1), recoveryFile.readBytes())
        } finally {
            session.close(completed = false)
            OttaiFirmwareSession.discardPendingTransfer(context, id)
        }
    }

    @Test fun successfulCloseDrainsAQueuedReportWithoutBlockingTheCaller() {
        val (session, worker) = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            worker.submit { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val reported = AtomicBoolean(false)
            val report = worker.submit { reported.set(true) }
            session.close(completed = true)
            assertTrue(worker.isShutdown)
            assertFalse(reported.get())
            // Disposal after completion must not turn graceful shutdown into interruption.
            session.close(completed = false)
            release.countDown()
            report.get(2, TimeUnit.SECONDS)
            assertTrue(reported.get())
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            session.close(completed = false)
            worker.shutdownNow()
        }
    }

    @Test fun successfulCloseDoesNotInterruptARunningReport() {
        val (session, worker) = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val reported = AtomicBoolean(false)
            val report = worker.submit { entered.countDown(); release.await(); reported.set(true) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.close(completed = true)
            release.countDown()
            report.get(2, TimeUnit.SECONDS)
            assertTrue(reported.get())
        } finally {
            release.countDown()
            session.close(completed = false)
            worker.shutdownNow()
        }
    }

    @Test fun cancelledCloseStillInterruptsWorkAndDiscardsQueuedRequests() {
        val (session, worker) = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val reported = AtomicBoolean(false)
        try {
            worker.submit {
                entered.countDown()
                try { release.await() } catch (_: InterruptedException) { interrupted.set(true) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.submit { reported.set(true) }
            session.close(completed = false)
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(interrupted.get())
            assertFalse(reported.get())
        } finally {
            release.countDown()
            session.close(completed = false)
            worker.shutdownNow()
        }
    }
}
