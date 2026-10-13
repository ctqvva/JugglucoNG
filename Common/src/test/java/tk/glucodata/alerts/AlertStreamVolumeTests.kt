package tk.glucodata.alerts

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class)
class AlertStreamVolumeTests {
    private class Volumes : AlertStreamVolume.VolumeAccess {
        val values = mutableMapOf(4 to 3, 3 to 2, 5 to 2)
        val journal = mutableMapOf<Int, IntArray>()
        var permitted = true
        var failWrite = false
        override fun allowed(stream: Int) = permitted
        override fun read(stream: Int) = values.getValue(stream)
        override fun minimum(stream: Int) = 0
        override fun maximum(stream: Int) = 15
        override fun write(stream: Int, index: Int) {
            if (failWrite) throw SecurityException("Denied")
            values[stream] = index
        }
        override fun save(stream: Int, original: Int, written: Int) { journal[stream] = intArrayOf(original, written) }
        override fun clear(stream: Int) { journal.remove(stream) }
        override fun interrupted(): Map<Int, IntArray> = journal.toMap()
    }

    @Before fun resetAndroidController() {
        AlertStreamVolume::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(null, null)
        RuntimeEnvironment.getApplication().getSharedPreferences("alert_stream_volume", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultAndMaximumControlTheActualStreamThenRestoreIt() {
        val volume = Volumes()
        val owner = AlertStreamVolume.Controller(volume)
        owner.acquire(4, 60)!!.closeAfter {
            assertEquals(9, volume.values[4])
            assertArrayEquals(intArrayOf(3, 9), volume.journal[4])
        }
        assertEquals(3, volume.values[4])
        owner.acquire(4, 100)!!.closeAfter { assertEquals(15, volume.values[4]) }
        assertEquals(3, volume.values[4])
        assertTrue(volume.journal.isEmpty())
    }

    @Test fun overlappingPreviewCannotRestoreVolumeUnderAnActiveAlert() {
        val volume = Volumes()
        val owner = AlertStreamVolume.Controller(volume)
        val alert = owner.acquire(4, 100)!!
        val preview = owner.acquire(4, 60)!!
        assertEquals(15, volume.values[4])
        preview.close()
        assertEquals(15, volume.values[4])
        alert.close()
        assertEquals(3, volume.values[4])
        val next = owner.acquire(4, 60)!!
        alert.close() // An old stop callback must not touch the next owner.
        assertEquals(9, volume.values[4])
        next.close()
        assertEquals(3, volume.values[4])
    }

    @Test fun releasingTheLoudestOwnerReturnsToTheRemainingOwnersLevel() {
        val volume = Volumes()
        val owner = AlertStreamVolume.Controller(volume)
        val preview = owner.acquire(4, 60)!!
        val alert = owner.acquire(4, 100)!!
        alert.close()
        assertEquals(9, volume.values[4])
        preview.close()
        assertEquals(3, volume.values[4])
    }

    @Test fun alarmAndMediaRestorationAreIndependent() {
        val volume = Volumes()
        val owner = AlertStreamVolume.Controller(volume)
        val alarm = owner.acquire(4, 100)!!
        val media = owner.acquire(3, 60)!!
        alarm.close()
        assertEquals(3, volume.values[4])
        assertEquals(9, volume.values[3])
        media.close()
        assertEquals(2, volume.values[3])
    }

    @Test fun hardwareVolumeChangeIsNotOverwrittenByAnotherPreviewOrStop() {
        val volume = Volumes()
        val owner = AlertStreamVolume.Controller(volume)
        val alert = owner.acquire(4, 100)!!
        volume.values[4] = 5
        val preview = owner.acquire(4, 60)!!
        assertEquals(5, volume.values[4])
        preview.close()
        alert.close()
        assertEquals(5, volume.values[4])
        assertTrue(volume.journal.isEmpty())
    }

    @Test fun muteOrDndTransitionRelinquishesOwnership() {
        val volume = Volumes()
        val owner = AlertStreamVolume.Controller(volume)
        val alert = owner.acquire(4, 60)!!
        volume.permitted = false
        alert.close()
        assertEquals(9, volume.values[4])
        assertTrue(volume.journal.isEmpty())
        assertNull(owner.acquire(4, 100))
    }

    @Test fun deniedWriteDoesNotBlockPlaybackOrStrandOwnership() {
        val volume = Volumes()
        val owner = AlertStreamVolume.Controller(volume)
        volume.failWrite = true
        val lease = owner.acquire(4, 100)!!
        assertEquals(3, volume.values[4])
        volume.failWrite = false
        lease.close()
        owner.acquire(4, 60)!!.closeAfter { assertEquals(9, volume.values[4]) }
        assertEquals(3, volume.values[4])
    }

    @Test fun processRestartRestoresOnlyTheStillOwnedInterruptedSetting() {
        val volume = Volumes()
        AlertStreamVolume.Controller(volume).acquire(4, 100)
        AlertStreamVolume.Controller(volume).recover()
        assertEquals(3, volume.values[4])
        assertTrue(volume.journal.isEmpty())
        AlertStreamVolume.Controller(volume).acquire(4, 100)
        volume.values[4] = 7
        AlertStreamVolume.Controller(volume).recover()
        assertEquals(7, volume.values[4])
        assertTrue(volume.journal.isEmpty())
    }

    @Test fun androidAdapterUsesResolvedAttributesAndRestoresOnClose() {
        val app = RuntimeEnvironment.getApplication()
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        for ((usage, stream) in listOf(AudioAttributes.USAGE_ALARM to AudioManager.STREAM_ALARM,
            AudioAttributes.USAGE_MEDIA to AudioManager.STREAM_MUSIC,
            AudioAttributes.USAGE_NOTIFICATION to AudioManager.STREAM_NOTIFICATION,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION to AudioManager.STREAM_SYSTEM)) {
            val attributes = AudioAttributes.Builder().setUsage(usage).build()
            assertEquals(stream, attributes.volumeControlStream)
            audio.setStreamVolume(stream, 2, 0)
            val original = audio.getStreamVolume(stream)
            val lease = AlertStreamVolume.acquire(app, attributes, 100)
            assertNotNull(lease)
            assertEquals(audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream))
            lease!!.close()
            assertEquals(original, audio.getStreamVolume(stream))
        }
    }

    @Test fun androidAdapterDoesNotRaiseSilentNotificationVolumeOrBypassDnd() {
        val app = RuntimeEnvironment.getApplication()
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertNull(AlertStreamVolume.acquire(app, notification, 100))
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertNull(AlertStreamVolume.acquire(app, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(), 100))
    }

    private inline fun AlertStreamVolume.Lease.closeAfter(check: () -> Unit) {
        try { check() } finally { close() }
    }
}
