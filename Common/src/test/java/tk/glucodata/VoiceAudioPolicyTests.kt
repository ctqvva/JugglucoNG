package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioPolicyTests {

    @Test
    fun normalizeAddress_acceptsValidMacsAndRejectsPlaceholders() {
        assertEquals("AA:BB:CC:DD:EE:FF", VoiceAudioPolicy.normalizeAddress(" aa:bb:cc:dd:ee:ff "))
        assertEquals("01:23:45:67:89:AB", VoiceAudioPolicy.normalizeAddress("01:23:45:67:89:ab"))
        assertNull(VoiceAudioPolicy.normalizeAddress(null))
        assertNull(VoiceAudioPolicy.normalizeAddress(""))
        assertNull(VoiceAudioPolicy.normalizeAddress("00:00:00:00:00:00"))
        assertNull(VoiceAudioPolicy.normalizeAddress("02:00:00:00:00:00"))
        assertNull(VoiceAudioPolicy.normalizeAddress("FF:FF:FF:FF:FF:FF"))
        assertNull(VoiceAudioPolicy.normalizeAddress("not-a-mac"))
    }

    @Test
    fun devicesMatch_prefersMacWhenBothPresentAndPreventsSameNameCollision() {
        val savedCarA = VoiceAudioPolicy.SavedDevice(
            id = "mac:AA:BB:CC:DD:EE:01",
            address = "AA:BB:CC:DD:EE:01",
            name = "Car Multimedia",
            isCar = true,
        )
        val connectedCarA = VoiceAudioPolicy.ConnectedDevice(
            address = "aa:bb:cc:dd:ee:01",
            name = "Renamed Car",
            isBluetoothAudio = true,
            isCarAudio = true,
        )
        val connectedCarB = VoiceAudioPolicy.ConnectedDevice(
            address = "AA:BB:CC:DD:EE:02",
            name = "Car Multimedia",
            isBluetoothAudio = true,
            isCarAudio = true,
        )

        assertTrue("Same MAC should match even if renamed", VoiceAudioPolicy.devicesMatch(savedCarA, connectedCarA))
        assertFalse("Different valid MACs must not collide on generic name", VoiceAudioPolicy.devicesMatch(savedCarA, connectedCarB))
    }

    @Test
    fun devicesMatch_fallsBackToNormalizedNameWhenOemOmitsAddress() {
        val saved = VoiceAudioPolicy.SavedDevice(
            id = "mac:11:22:33:44:55:66",
            address = "11:22:33:44:55:66",
            name = "Mazda  Connect",
            isCar = true,
        )
        val connectedWithoutMac = VoiceAudioPolicy.ConnectedDevice(
            address = "02:00:00:00:00:00",
            name = "  mazda connect ",
            isBluetoothAudio = true,
            isCarAudio = false,
        )
        assertTrue(VoiceAudioPolicy.devicesMatch(saved, connectedWithoutMac))
    }

    @Test
    fun deduplicateConnectedDevices_mergesA2dpAndScoForSameDevice() {
        val devices = listOf(
            VoiceAudioPolicy.ConnectedDevice("AA:BB:CC:DD:EE:FF", "BMW 530i", isBluetoothAudio = true, isCarAudio = false),
            VoiceAudioPolicy.ConnectedDevice("aa:bb:cc:dd:ee:ff", "", isBluetoothAudio = true, isCarAudio = true),
            VoiceAudioPolicy.ConnectedDevice(null, "bmw 530i", isBluetoothAudio = true, isCarAudio = false),
            VoiceAudioPolicy.ConnectedDevice("11:22:33:44:55:66", "Pixel Buds", isBluetoothAudio = true, isCarAudio = false),
        )
        val merged = VoiceAudioPolicy.deduplicateConnectedDevices(devices)
        assertEquals(2, merged.size)
        assertEquals("AA:BB:CC:DD:EE:FF", merged[0].address)
        assertEquals("BMW 530i", merged[0].name)
        assertTrue(merged[0].isCarAudio)
        assertEquals("Pixel Buds", merged[1].name)
    }

    @Test
    fun evaluateMatch_disabledConfigNeverMatches() {
        val config = VoiceAudioPolicy.Config(
            enabled = false,
            scope = VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO,
            includeCarMode = true,
        )
        val match = VoiceAudioPolicy.evaluateMatch(
            config = config,
            connectedDevices = listOf(VoiceAudioPolicy.ConnectedDevice("AA:BB:CC:DD:EE:FF", "My Car", true, true)),
            inCarMode = true,
        )
        assertFalse(match.matched)
    }

    @Test
    fun evaluateMatch_selectedDevicesMatchesOnlyChosenAndCarModeFallback() {
        val myCar = VoiceAudioPolicy.SavedDevice("mac:AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF", "My Car", isCar = true)
        val config = VoiceAudioPolicy.Config(
            enabled = true,
            scope = VoiceAudioPolicy.Scope.SELECTED_DEVICES,
            includeCarMode = true,
            savedDevices = listOf(myCar),
        )

        val otherBuds = listOf(VoiceAudioPolicy.ConnectedDevice("11:22:33:44:55:66", "Other Buds", true, false))
        assertFalse(VoiceAudioPolicy.evaluateMatch(config, otherBuds, inCarMode = false).matched)

        val carModeOnly = VoiceAudioPolicy.evaluateMatch(config, otherBuds, inCarMode = true)
        assertTrue(carModeOnly.matched)
        assertEquals("car_mode", carModeOnly.triggerKey)
        assertTrue(carModeOnly.viaCarMode)

        val withMyCar = otherBuds + VoiceAudioPolicy.ConnectedDevice("AA:BB:CC:DD:EE:FF", "My Car", true, true)
        val carMatch = VoiceAudioPolicy.evaluateMatch(config, withMyCar, inCarMode = false)
        assertTrue(carMatch.matched)
        assertEquals("My Car", carMatch.displayLabel)
        assertTrue(carMatch.viaCarMode)
    }

    @Test
    fun evaluateMatch_anyBluetoothAudioPrefersCarAudioDevice() {
        val config = VoiceAudioPolicy.Config(
            enabled = true,
            scope = VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO,
            includeCarMode = false,
        )
        val connected = listOf(
            VoiceAudioPolicy.ConnectedDevice("11:22:33:44:55:66", "Watch Speaker", true, false),
            VoiceAudioPolicy.ConnectedDevice("AA:BB:CC:DD:EE:FF", "Subaru BT", true, true),
        )
        val match = VoiceAudioPolicy.evaluateMatch(config, connected, inCarMode = false)
        assertTrue(match.matched)
        assertEquals("Subaru BT", match.displayLabel)
        assertTrue(match.viaCarMode)
    }

    @Test
    fun resolveEffective_autoTriggerOverridesScheduleIntervalStreamAndAlarmsWithoutMutatingManual() {
        val config = VoiceAudioPolicy.Config(
            enabled = true,
            intervalSeconds = 300,
            speakAlarms = true,
            useMediaStream = true,
        )
        val active = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:AA:BB:CC:DD:EE:FF", "My Car", viaCarMode = true)

        val decision = VoiceAudioPolicy.resolveEffective(
            manualSpeakGlucose = false,
            withinSchedule = false,
            manualSpeakAlarms = false,
            defaultIntervalSeconds = 60,
            config = config,
            activeMatch = active,
            sessionMuted = false,
        )
        assertTrue(decision.autoActive)
        assertTrue(decision.shouldSpeakReading)
        assertTrue(decision.shouldSpeakAlarm)
        assertEquals(300, decision.effectiveIntervalSeconds)
        assertTrue(decision.useMediaStream)
        assertTrue(decision.bypassSchedule)

        // When muted for this session, manual-off state takes over immediately.
        val muted = VoiceAudioPolicy.resolveEffective(
            manualSpeakGlucose = false,
            withinSchedule = false,
            manualSpeakAlarms = false,
            defaultIntervalSeconds = 60,
            config = config,
            activeMatch = active,
            sessionMuted = true,
        )
        assertFalse(muted.autoActive)
        assertFalse(muted.shouldSpeakReading)
        assertFalse(muted.shouldSpeakAlarm)
        assertEquals(60, muted.effectiveIntervalSeconds)
        assertFalse(muted.useMediaStream)
    }

    @Test
    fun resolveEffective_zeroCarIntervalUsesDefaultVoiceInterval() {
        val config = VoiceAudioPolicy.Config(enabled = true, intervalSeconds = 0)
        val active = VoiceAudioPolicy.ActiveMatch(true, "car_mode", null, viaCarMode = true)
        val decision = VoiceAudioPolicy.resolveEffective(
            manualSpeakGlucose = false,
            withinSchedule = true,
            manualSpeakAlarms = false,
            defaultIntervalSeconds = 180,
            config = config,
            activeMatch = active,
            sessionMuted = false,
        )
        assertEquals(180, decision.effectiveIntervalSeconds)
    }

    @Test
    fun shouldScheduleConnectAnnouncement_respectsCooldownsAndTransitions() {
        val config = VoiceAudioPolicy.Config(enabled = true, announceOnConnect = true)
        val now = 100_000L

        assertTrue(
            VoiceAudioPolicy.shouldScheduleConnectAnnouncement(
                wasMatched = false,
                nowMatched = true,
                config = config,
                sessionMuted = false,
                nowMs = now,
                lastDisconnectedAtMs = 0L,
                lastSpokenAtMs = 0L,
            )
        )
        // Already matched -> do not re-announce.
        assertFalse(
            VoiceAudioPolicy.shouldScheduleConnectAnnouncement(
                wasMatched = true,
                nowMatched = true,
                config = config,
                sessionMuted = false,
                nowMs = now,
                lastDisconnectedAtMs = 0L,
                lastSpokenAtMs = 0L,
            )
        )
        // Brief BT glitch (< 15s disconnect) -> suppress duplicate greeting.
        assertFalse(
            VoiceAudioPolicy.shouldScheduleConnectAnnouncement(
                wasMatched = false,
                nowMatched = true,
                config = config,
                sessionMuted = false,
                nowMs = now,
                lastDisconnectedAtMs = now - 5_000L,
                lastSpokenAtMs = 0L,
            )
        )
        // Already spoke a reading 3s ago -> suppress duplicate greeting.
        assertFalse(
            VoiceAudioPolicy.shouldScheduleConnectAnnouncement(
                wasMatched = false,
                nowMatched = true,
                config = config,
                sessionMuted = false,
                nowMs = now,
                lastDisconnectedAtMs = now - 60_000L,
                lastSpokenAtMs = now - 3_000L,
            )
        )
    }

    @Test
    fun isReadingFreshForConnectAnnouncement_rejectsStaleHistoryFallbackReadings() {
        val now = 1_700_000_000_000L
        val timeout = 330_000L // 5.5 min (Notify.glucosetimeout)

        assertTrue(VoiceAudioPolicy.isReadingFreshForConnectAnnouncement(now - 60_000L, now, timeout))
        assertTrue(VoiceAudioPolicy.isReadingFreshForConnectAnnouncement(now - timeout, now, timeout))
        assertFalse(
            "20-minute-old history fallback reading must not be announced as current",
            VoiceAudioPolicy.isReadingFreshForConnectAnnouncement(now - 20 * 60_000L, now, timeout),
        )
        assertFalse(VoiceAudioPolicy.isReadingFreshForConnectAnnouncement(0L, now, timeout))
        assertFalse(VoiceAudioPolicy.isReadingFreshForConnectAnnouncement(now + 120_000L, now, timeout))
    }

    @Test
    fun enrichConnectedAudioOutput_doesNotGuessMacForAmbiguousPairedNamesAndPrefersProfileCache() {
        val bonded = listOf(
            VoiceAudioPolicy.BondedHint("AA:BB:CC:DD:EE:01", "Car Multimedia", isCar = true),
            VoiceAudioPolicy.BondedHint("AA:BB:CC:DD:EE:02", "Car Multimedia", isCar = true),
            VoiceAudioPolicy.BondedHint("11:22:33:44:55:66", "Unique Car", isCar = true),
        )
        val savedFirstCar = VoiceAudioPolicy.SavedDevice(
            id = "mac:AA:BB:CC:DD:EE:01",
            address = "AA:BB:CC:DD:EE:01",
            name = "Car Multimedia",
            isCar = true,
        )
        val savedSecondCar = VoiceAudioPolicy.SavedDevice(
            id = "mac:AA:BB:CC:DD:EE:02",
            address = "AA:BB:CC:DD:EE:02",
            name = "Car Multimedia",
            isCar = true,
        )

        // Two paired devices share "Car Multimedia" and AudioManager omits MAC + no profile cache:
        // must keep address null, mark ambiguousName = true, and fail closed for MAC-identified cars
        // so Car B never falsely activates Car A's selection.
        val ambiguous = VoiceAudioPolicy.enrichConnectedAudioOutput(
            rawAddress = null,
            rawName = "Car Multimedia",
            profileConnected = emptyList(),
            bondedHints = bonded,
            looksLikeCarName = { false },
        )!!
        assertNull("Ambiguous paired name must not invent an arbitrary MAC", ambiguous.address)
        assertTrue(ambiguous.ambiguousName)
        assertTrue(ambiguous.isCarAudio)
        assertFalse("MAC-identified saved device must fail closed on ambiguous sibling name", VoiceAudioPolicy.devicesMatch(savedFirstCar, ambiguous))
        assertFalse(VoiceAudioPolicy.devicesMatch(savedSecondCar, ambiguous))

        // When the actual connected profile broadcast identifies MAC 02, prefer it over bonded list:
        // Car B matches and Car A does not.
        val fromProfile = VoiceAudioPolicy.enrichConnectedAudioOutput(
            rawAddress = null,
            rawName = "Car Multimedia",
            profileConnected = listOf(
                VoiceAudioPolicy.ConnectedDevice("AA:BB:CC:DD:EE:02", "Car Multimedia", true, true)
            ),
            bondedHints = bonded,
            looksLikeCarName = { false },
        )!!
        assertEquals("AA:BB:CC:DD:EE:02", fromProfile.address)
        assertFalse(fromProfile.ambiguousName)
        assertTrue(VoiceAudioPolicy.devicesMatch(savedSecondCar, fromProfile))
        assertFalse(VoiceAudioPolicy.devicesMatch(savedFirstCar, fromProfile))

        // Unambiguous single paired device still enriches its MAC cleanly.
        val unique = VoiceAudioPolicy.enrichConnectedAudioOutput(
            rawAddress = null,
            rawName = "Unique Car",
            profileConnected = emptyList(),
            bondedHints = bonded,
            looksLikeCarName = { false },
        )!!
        assertEquals("11:22:33:44:55:66", unique.address)
        assertFalse(unique.ambiguousName)
    }

    @Test
    fun isDistinctSessionSwitch_resetsAcrossDifferentDevicesButKeepsSameCarProjectionHandoff() {
        val buds = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:11:22:33:44:55:66", "Earbuds", viaCarMode = false)
        val carA = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:AA:BB:CC:DD:EE:01", "Car A", viaCarMode = true)
        val carB = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:AA:BB:CC:DD:EE:02", "Car B", viaCarMode = true)
        val carMode = VoiceAudioPolicy.ActiveMatch(true, "car_mode", null, viaCarMode = true)

        assertTrue("Headphones -> Car is a distinct session", VoiceAudioPolicy.isDistinctSessionSwitch(buds, carA))
        assertTrue("Car A -> Car B is a distinct session", VoiceAudioPolicy.isDistinctSessionSwitch(carA, carB))
        assertFalse("Same device stays same session", VoiceAudioPolicy.isDistinctSessionSwitch(carA, carA))
        assertFalse("Android Auto <-> Car BT handoff stays same in-car session", VoiceAudioPolicy.isDistinctSessionSwitch(carMode, carA))
        assertFalse("Car BT <-> Android Auto handoff stays same in-car session", VoiceAudioPolicy.isDistinctSessionSwitch(carA, carMode))
    }

    @Test
    fun resolveEffective_sessionPauseSuppressesManualReadingSpeechWithoutChangingManualFlag() {
        val config = VoiceAudioPolicy.Config(enabled = true, speakAlarms = true)
        val active = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:AA:BB:CC:DD:EE:FF", "My Car", viaCarMode = true)

        val paused = VoiceAudioPolicy.resolveEffective(
            manualSpeakGlucose = true,
            withinSchedule = true,
            manualSpeakAlarms = true,
            defaultIntervalSeconds = 60,
            config = config,
            activeMatch = active,
            sessionMuted = true,
        )
        assertFalse(paused.autoActive)
        assertFalse("Pausing a car session must silence readings even when manual speech is on", paused.shouldSpeakReading)
        assertTrue("Manual alarm speech stays untouched when readings are paused", paused.shouldSpeakAlarm)
    }
}
