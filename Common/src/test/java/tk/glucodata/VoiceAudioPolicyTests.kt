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
        val anyNameOnly = VoiceAudioPolicy.ActiveMatch(true, "any_bt:name:car stereo", "Car Stereo", viaCarMode = true)
        val anyMacRefined = VoiceAudioPolicy.ActiveMatch(true, "any_bt:mac:AA:BB:CC:DD:EE:01", "Car Stereo", viaCarMode = true)
        val anyOtherDevice = VoiceAudioPolicy.ActiveMatch(true, "any_bt:mac:11:22:33:44:55:66", "Earbuds", viaCarMode = false)

        assertTrue("Headphones -> Car is a distinct session", VoiceAudioPolicy.isDistinctSessionSwitch(buds, carA))
        assertTrue("Car A -> Car B is a distinct session", VoiceAudioPolicy.isDistinctSessionSwitch(carA, carB))
        assertFalse("Same device stays same session", VoiceAudioPolicy.isDistinctSessionSwitch(carA, carA))
        assertFalse("Android Auto <-> Car BT handoff stays same in-car session", VoiceAudioPolicy.isDistinctSessionSwitch(carMode, carA))
        assertFalse("Car BT <-> Android Auto handoff stays same in-car session", VoiceAudioPolicy.isDistinctSessionSwitch(carA, carMode))
        assertFalse(
            "ANY_BLUETOOTH_AUDIO name -> MAC refinement for same device must keep session pause",
            VoiceAudioPolicy.isDistinctSessionSwitch(anyNameOnly, anyMacRefined),
        )
        assertFalse(
            "ANY_BLUETOOTH_AUDIO MAC -> name refinement for same device must keep session pause",
            VoiceAudioPolicy.isDistinctSessionSwitch(anyMacRefined, anyNameOnly),
        )
        assertTrue(
            "ANY_BLUETOOTH_AUDIO switch to a different device must reset session pause",
            VoiceAudioPolicy.isDistinctSessionSwitch(anyNameOnly, anyOtherDevice),
        )
    }

    @Test
    fun resolveEffective_everyReadingPresetUsesZeroSeparation() {
        val config = VoiceAudioPolicy.Config(
            enabled = true,
            intervalSeconds = VoiceAudioPolicy.INTERVAL_EVERY_READING,
        )
        val active = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:AA:BB:CC:DD:EE:FF", "My Car", viaCarMode = true)
        val decision = VoiceAudioPolicy.resolveEffective(
            manualSpeakGlucose = false,
            withinSchedule = true,
            manualSpeakAlarms = false,
            defaultIntervalSeconds = 60,
            config = config,
            activeMatch = active,
            sessionMuted = false,
        )
        assertEquals(0, decision.effectiveIntervalSeconds)
    }

    @Test
    fun looksLikeCarName_usesWholeWordsAndNeverMatchesAudioOrOscar() {
        assertFalse("Audio-Technica must not match 'audi'", VoiceAudioPolicy.looksLikeCarName("Audio-Technica ATH-M50xBT"))
        assertFalse("Bluetooth Audio must not match 'audi'", VoiceAudioPolicy.looksLikeCarName("Bluetooth Audio"))
        assertFalse("Oscar's AirPods must not match 'car'", VoiceAudioPolicy.looksLikeCarName("Oscar's AirPods"))
        assertFalse("Galaxy Watch6 must not match car", VoiceAudioPolicy.looksLikeCarName("Galaxy Watch6"))
        assertTrue("Audi MMI is a car", VoiceAudioPolicy.looksLikeCarName("Audi MMI 3G"))
        assertTrue("My Car is a car", VoiceAudioPolicy.looksLikeCarName("My Car"))
        assertTrue("Car Multimedia is a car", VoiceAudioPolicy.looksLikeCarName("Car Multimedia"))
        assertTrue("Mazda Connect is a car", VoiceAudioPolicy.looksLikeCarName("Mazda Connect"))
        assertTrue("Android Auto is a car", VoiceAudioPolicy.looksLikeCarName("Android Auto"))
    }

    @Test
    fun resolveEffective_onlyBypassesScheduleWhileDriving() {
        val config = VoiceAudioPolicy.Config(enabled = true)
        val headphones = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:11:22:33:44:55:66", "AirPods", viaCarMode = false)
        val car = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:AA:BB:CC:DD:EE:FF", "My Car", viaCarMode = true)

        val nightHeadphones = VoiceAudioPolicy.resolveEffective(
            manualSpeakGlucose = false,
            withinSchedule = false,
            manualSpeakAlarms = false,
            defaultIntervalSeconds = 60,
            config = config,
            activeMatch = headphones,
            sessionMuted = false,
        )
        assertFalse("Headphones must respect Speak schedule", nightHeadphones.bypassSchedule)
        assertFalse("Headphones must not speak readings outside Speak schedule", nightHeadphones.shouldSpeakReading)

        val nightCar = VoiceAudioPolicy.resolveEffective(
            manualSpeakGlucose = false,
            withinSchedule = false,
            manualSpeakAlarms = false,
            defaultIntervalSeconds = 60,
            config = config,
            activeMatch = car,
            sessionMuted = false,
        )
        assertTrue("Driving session should bypass Speak schedule", nightCar.bypassSchedule)
        assertTrue("Driving session should speak readings outside Speak schedule", nightCar.shouldSpeakReading)
    }

    @Test
    fun evaluateMatch_anyModeKeepsCurrentDeviceStickyRegardlessOfOrder() {
        val config = VoiceAudioPolicy.Config(enabled = true, scope = VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        val devA = VoiceAudioPolicy.ConnectedDevice("11:22:33:44:55:01", "Pixel Buds", isBluetoothAudio = true)
        val devB = VoiceAudioPolicy.ConnectedDevice("11:22:33:44:55:02", "Speaker", isBluetoothAudio = true)

        val initial = VoiceAudioPolicy.evaluateMatch(config, listOf(devA, devB), inCarMode = false)
        assertEquals("any_bt:mac:11:22:33:44:55:01", initial.triggerKey)

        // AudioManager reverses device order: sticky match must stay on devA without switching session.
        val reversed = VoiceAudioPolicy.evaluateMatch(config, listOf(devB, devA), inCarMode = false, previousMatch = initial)
        assertEquals("any_bt:mac:11:22:33:44:55:01", reversed.triggerKey)
        assertFalse(VoiceAudioPolicy.isDistinctSessionSwitch(initial, reversed))
    }

    @Test
    fun shouldPreservePauseOnReconnect_keepsPauseAcrossShortDropoutOfSameDevice() {
        val car = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:AA:BB:CC:DD:EE:FF", "My Car", viaCarMode = true)
        val buds = VoiceAudioPolicy.ActiveMatch(true, "dev:mac:11:22:33:44:55:66", "Pixel Buds", viaCarMode = false)

        assertTrue(
            "50ms dropout of the same car must preserve pause",
            VoiceAudioPolicy.shouldPreservePauseOnReconnect(car, car, nowMs = 10_050L, pausedDisconnectedAtMs = 10_000L),
        )
        assertFalse(
            "Reconnect after cooldown must start unmuted",
            VoiceAudioPolicy.shouldPreservePauseOnReconnect(car, car, nowMs = 30_000L, pausedDisconnectedAtMs = 10_000L),
        )
        assertFalse(
            "Short reconnect of a different device must start unmuted",
            VoiceAudioPolicy.shouldPreservePauseOnReconnect(car, buds, nowMs = 10_050L, pausedDisconnectedAtMs = 10_000L),
        )
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

    @Test
    fun dualModeEarbuds_collapseIntoSingleDeviceAndMatchAcrossTransports() {
        val classicBud = VoiceAudioPolicy.ConnectedDevice(
            address = "AA:BB:CC:11:22:01",
            name = "Galaxy Buds2 Pro",
            isBluetoothAudio = true,
            isCarAudio = false,
            transport = VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL,
        )
        val leBud = VoiceAudioPolicy.ConnectedDevice(
            address = "44:55:66:77:88:02",
            name = "Galaxy Buds2 Pro",
            isBluetoothAudio = true,
            isCarAudio = false,
            transport = VoiceAudioPolicy.Transport.LE_ONLY,
        )

        val deduped = VoiceAudioPolicy.deduplicateConnectedDevices(listOf(leBud, classicBud))
        assertEquals("Classic + LE Audio twins for the same Buds must collapse to 1 row", 1, deduped.size)
        assertEquals("AA:BB:CC:11:22:01", deduped[0].address)
        assertEquals(listOf("44:55:66:77:88:02"), deduped[0].alternateAddresses)

        // Saving the collapsed entry matches whether the Buds later connect over Classic OR LE Audio.
        val saved = VoiceAudioPolicy.SavedDevice(
            id = "mac:AA:BB:CC:11:22:01",
            address = "AA:BB:CC:11:22:01",
            name = "Galaxy Buds2 Pro",
            alternateAddresses = listOf("44:55:66:77:88:02"),
        )
        assertTrue(VoiceAudioPolicy.devicesMatch(saved, classicBud))
        assertTrue(VoiceAudioPolicy.devicesMatch(saved, leBud))

        // Meanwhile two distinct Classic cars sharing "Car Multimedia" MUST stay separate!
        val carA = VoiceAudioPolicy.ConnectedDevice(
            address = "AA:BB:CC:DD:EE:01",
            name = "Car Multimedia",
            isBluetoothAudio = true,
            isCarAudio = true,
            transport = VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL,
        )
        val carB = VoiceAudioPolicy.ConnectedDevice(
            address = "11:22:33:44:55:02",
            name = "Car Multimedia",
            isBluetoothAudio = true,
            isCarAudio = true,
            transport = VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL,
        )
        assertEquals("Two distinct Classic cars must NOT collapse", 2, VoiceAudioPolicy.deduplicateConnectedDevices(listOf(carA, carB)).size)
    }

    @Test
    fun unselectedHeadsetWithSameNameAndVendorPrefix_neverMatchesSelectedHeadset() {
        val selected = VoiceAudioPolicy.SavedDevice(
            id = "mac:AA:BB:CC:11:22:01",
            address = "AA:BB:CC:11:22:01",
            name = "Pixel Buds",
        )
        val bonded = listOf(
            VoiceAudioPolicy.BondedHint("AA:BB:CC:11:22:01", "Pixel Buds", isCar = false, transport = VoiceAudioPolicy.Transport.CLASSIC_OR_DUAL),
            VoiceAudioPolicy.BondedHint("AA:BB:CC:11:22:02", "Pixel Buds", isCar = false, transport = VoiceAudioPolicy.Transport.LE_ONLY),
        )
        // Connecting the unselected second headset must NOT pull the selected headset's MAC from bondedHints.
        val connectedUnselected = VoiceAudioPolicy.enrichConnectedAudioOutput(
            rawAddress = "AA:BB:CC:11:22:02",
            rawName = "Pixel Buds",
            profileConnected = emptyList(),
            bondedHints = bonded,
            looksLikeCarName = VoiceAudioPolicy::looksLikeCarName,
            transport = VoiceAudioPolicy.Transport.LE_ONLY,
        )!!
        assertEquals("AA:BB:CC:11:22:02", connectedUnselected.address)
        assertTrue("Connected device must not inherit MACs from unrelated bonded entries", connectedUnselected.alternateAddresses.isEmpty())
        assertFalse("Unselected headset must NEVER match selected headset", VoiceAudioPolicy.devicesMatch(selected, connectedUnselected))
    }

    @Test
    fun sanitizeAddressAndTriggerKey_neverExposeFullMacAddresses() {
        assertEquals("**EE:FF", VoiceAudioPolicy.sanitizeAddress("aa:bb:cc:dd:ee:ff"))
        assertEquals("placeholder", VoiceAudioPolicy.sanitizeAddress("02:00:00:00:00:00"))
        assertEquals("none", VoiceAudioPolicy.sanitizeAddress(null))
        assertEquals("none", VoiceAudioPolicy.sanitizeAddress("   "))
        assertEquals("invalid", VoiceAudioPolicy.sanitizeAddress("not-a-mac"))

        val sanitizedKey = VoiceAudioPolicy.sanitizeTriggerKey("dev:mac:AA:BB:CC:DD:EE:FF")
        assertEquals("dev:mac:**EE:FF", sanitizedKey)
        assertFalse(sanitizedKey.contains("AA:BB:CC"))
        assertEquals("car_mode", VoiceAudioPolicy.sanitizeTriggerKey("car_mode"))
        assertEquals("any_bt:name:my car", VoiceAudioPolicy.sanitizeTriggerKey("any_bt:name:my car"))
    }

    @Test
    fun explainDeviceMatch_reportsExplicitMatchAndRejectionReasons() {
        val saved = VoiceAudioPolicy.SavedDevice(
            id = "mac:AA:BB:CC:DD:EE:01",
            address = "AA:BB:CC:DD:EE:01",
            name = "Car Audio",
            isCar = true,
            alternateAddresses = listOf("AA:BB:CC:DD:EE:02"),
        )
        assertEquals(
            VoiceAudioPolicy.DeviceMatchDetail(true, "mac_exact"),
            VoiceAudioPolicy.explainDeviceMatch(
                saved,
                VoiceAudioPolicy.ConnectedDevice(address = "AA:BB:CC:DD:EE:01", name = "Car Audio"),
            ),
        )
        assertEquals(
            VoiceAudioPolicy.DeviceMatchDetail(true, "mac_alt"),
            VoiceAudioPolicy.explainDeviceMatch(
                saved,
                VoiceAudioPolicy.ConnectedDevice(address = "AA:BB:CC:DD:EE:02", name = "Car Audio"),
            ),
        )
        assertEquals(
            VoiceAudioPolicy.DeviceMatchDetail(false, "mac_mismatch"),
            VoiceAudioPolicy.explainDeviceMatch(
                saved,
                VoiceAudioPolicy.ConnectedDevice(address = "AA:BB:CC:DD:EE:99", name = "Car Audio"),
            ),
        )
        assertEquals(
            VoiceAudioPolicy.DeviceMatchDetail(false, "ambiguous_name"),
            VoiceAudioPolicy.explainDeviceMatch(
                saved,
                VoiceAudioPolicy.ConnectedDevice(address = null, name = "Car Audio", ambiguousName = true),
            ),
        )
        assertEquals(
            VoiceAudioPolicy.DeviceMatchDetail(true, "name_fallback"),
            VoiceAudioPolicy.explainDeviceMatch(
                saved,
                VoiceAudioPolicy.ConnectedDevice(address = null, name = "Car Audio", ambiguousName = false),
            ),
        )
        assertEquals(
            VoiceAudioPolicy.DeviceMatchDetail(false, "name_mismatch"),
            VoiceAudioPolicy.explainDeviceMatch(
                saved,
                VoiceAudioPolicy.ConnectedDevice(address = null, name = "Other Headset", ambiguousName = false),
            ),
        )
    }
}
