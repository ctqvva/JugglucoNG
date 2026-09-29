package tk.glucodata.drivers.aidex.native.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for AiDexBleManager wiring. The manager cannot be built in a JVM test (HandlerThread,
 * natives), so every decision it makes goes through a pure seam that has its own tests; these pins
 * check that the manager really calls those seams, and they hold the actuator invariants of the
 * reset / unpair / bond paths in place — where the 0xF3 debt may be written, which code may set the
 * ACK stamp, which call sites may remove an Android bond.
 */
class AiDexBleManagerSourcePinTests {

    private val manager: String by lazy {
        stripComments(File(repoRoot(), MANAGER).readText())
    }

    @Test
    fun resetSensorPersistsNothingBeforeTheSensorConfirms() {
        val body = member("override fun resetSensor(): Boolean")
        for (forbidden in listOf(
            "markSensorReset(",
            "setActivationDebt(",
            "armPostResetHistoryBarrier(",
            "historyRawNextIndex = 0",
            "historyBriefNextIndex = 0",
            "writeIntPref(",
            "writeBoolPref(",
            "writeLongPref(",
            "clearPendingRoomHistory(",
            "postResetWarmupExtensionActive = true",
            "markPairKeyResetPending(",
            "pairKeyResetPending =",
            "postResetFreshPairConnections",
        )) {
            assertFalse("resetSensor must not contain $forbidden", body.contains(forbidden))
        }
        // The firmware gate decides before anything latches, and only ALLOWED goes on.
        val gate = body.indexOf("when (AiDexRuntimePolicy.lifecycleReset(_firmwareVersion)) {")
        assertTrue(gate >= 0 && gate < body.indexOf("synchronized(unpairLock)"))
        val arms = body.after("when (AiDexRuntimePolicy.lifecycleReset(_firmwareVersion)) {")
            .before("AiDexRuntimePolicy.LifecycleReset.ALLOWED -> Unit }")
        assertEquals(2, count(arms, "return false"))
        // Exactly the two refusing arms before ALLOWED: an extra arm could let UNKNOWN through.
        assertEquals(2, count(arms, "->"))
        assertTrue(arms.contains("AiDexRuntimePolicy.LifecycleReset.REFUSED ->"))
        assertTrue(arms.contains("AiDexRuntimePolicy.LifecycleReset.UNKNOWN ->"))
        // Both refusing arms end in their return, and nothing in them can say yes.
        assertFalse(arms.contains("return true"))
        assertTrue(arms.before("AiDexRuntimePolicy.LifecycleReset.UNKNOWN -> {").endsWith("return false } "))
        // The UNKNOWN arm verbatim: it guards an actuator, so any edit to it has to update this pin.
        // Unpair first (the admission's answer), the DIS read only while streaming (the setup writes
        // bypass the GATT queue), then "still connecting", then the admission's not-connected answer.
        val unknown = arms.after("AiDexRuntimePolicy.LifecycleReset.UNKNOWN -> {")
        assertEquals(
            " Log.w(TAG, \"resetSensor: firmware version '\${_firmwareVersion}' does not decide — not sending CLEAR_STORAGE\")" +
                " if (pendingUnpairDisconnect) { showTransientStatus(\"Unpair in progress — reset refused\", POST_RESET_OUTCOME_STATUS_MS)" +
                " } else if (phase == Phase.STREAMING && mBluetoothGatt != null && servicesReady) { enqueueGattOp(GattOp.Read(CHAR_SOFTWARE_REV, SERVICE_DIS))" +
                " showTransientStatus( \"Reading the sensor's firmware version — press Reset again in a few seconds\", POST_RESET_OUTCOME_STATUS_MS, )" +
                " } else if (mBluetoothGatt != null && servicesReady) {" +
                " showTransientStatus( \"Reset unavailable while the sensor is still connecting — press again once it shows Connected\", POST_RESET_OUTCOME_STATUS_MS, )" +
                " } else { showTransientStatus(\"Reset failed — not connected\", POST_RESET_OUTCOME_STATUS_MS)" +
                " constatstatusstr = \"Reset failed — not connected\" UiRefreshBus.requestStatusRefresh()" +
                " } return false } ",
            unknown,
        )
        // One read, and only there (0x10 leaves "1.8" and nothing re-reads DIS).
        assertEquals(1, count(body, "enqueueGattOp("))
        assertFalse(body.contains("readDeviceInformationService("))
        assertTrue(body.contains("AiDexRuntimePolicy.decideResetAdmission("))
        assertTrue(body.contains("synchronized(resetClaimLock) {"))
        assertTrue(body.contains("pendingResetReconnect = true"))
        assertTrue(body.contains("resetAttemptRequestedAtMs = resetRequestedAt"))
        assertTrue(body.contains("enqueueExclusiveClearStorage(cmd)"))
    }

    @Test
    fun theDebtIsOwedOnlyFromTheConfirmedCommit() {
        assertEquals(1, count(manager, "setActivationDebt(true"))
        // markSensorReset: the confirmed commit and the local 0x20 session, nowhere else.
        assertEquals(2, count(manager, "HistorySyncAccess.markSensorReset("))
        assertTrue(member("private fun commitConfirmedClearStorageIfOwed()").contains("markSensorReset("))
        assertTrue(member("private fun commitSetNewSensorLocalSession()").contains("markSensorReset("))
        // The history cursor zero: the same two sites (the 0x20 rollback restores saved values).
        assertEquals(2, Regex("(?<!var )historyRawNextIndex = 0\\b").findAll(manager).count())
        assertTrue(member("private fun commitConfirmedClearStorageIfOwed()").contains("historyRawNextIndex = 0"))
        assertTrue(member("private fun commitConfirmedClearStorageIfOwed()").contains("setActivationDebt(true"))
        // The only writer of the persisted debt key, and the legacy key is never written.
        assertEquals(1, count(manager, "writeBoolPref(ACTIVATION_DEBT_PREF"))
        assertTrue(member("private fun setActivationDebt(").contains("writeBoolPref(ACTIVATION_DEBT_PREF"))
        assertFalse(manager.contains("writeBoolPref(LEGACY_ACTIVATION_DEBT_PREF"))
        assertFalse(manager.contains("writeBoolPref(\"needsPostResetActivation\""))
        // Barrier: the confirmed commit and the local 0x20 session, nowhere else, whatever the
        // argument spelling. setActivationDebt(true) likewise has exactly one caller.
        assertEquals(2, Regex("(?<!fun )armPostResetHistoryBarrier\\(").findAll(manager).count())
        assertEquals(1, Regex("(?<!fun )setActivationDebt\\((?!false)").findAll(manager).count())
        // "Confirmed" is the owed flag, and only the claim sets it.
        val commit = member("private fun commitConfirmedClearStorageIfOwed()")
        assertTrue(commit.contains("{ if (!clearStorageConfirmOwed) return clearStorageConfirmOwed = false"))
        assertEquals(1, count(manager, "clearStorageConfirmOwed = true"))
        assertTrue(member("private fun claimClearStorageConfirmation(")
            .contains("postResetClearStorageAckAtMs = System.currentTimeMillis() clearStorageConfirmOwed = true"))
        assertTrue(member("private fun commitConfirmedClearStorageIfOwed()").contains("armPostResetHistoryBarrier("))
        assertTrue(member("private fun commitSetNewSensorLocalSession()").contains("armPostResetHistoryBarrier("))
    }

    @Test
    fun aLegacyDebtIsDroppedAtInitAndNeverRestored() {
        val init = flatten(manager.after("private val resetClaimLock = Any()").after("init {").before("\n    private enum class HistoryPhase"))
        assertTrue(
            init.contains(
                "if (readBoolPref(LEGACY_ACTIVATION_DEBT_PREF, false)) { writeLongPref(\"postResetRequestedAtMs\", 0L)"
            )
        )
        val removed = init.indexOf("prefs.edit().remove(prefKey(LEGACY_ACTIVATION_DEBT_PREF))")
        assertTrue(removed >= 0 && removed < init.indexOf("postResetRequestedAtMs = readLongPref("))
        assertFalse(manager.contains("readBoolPref(\"needsPostResetActivation\""))
        assertTrue(init.contains("prefs.edit().remove(prefKey(LEGACY_ACTIVATION_DEBT_PREF)).apply()"))
        assertTrue(init.contains("needsPostResetActivation = readBoolPref(ACTIVATION_DEBT_PREF, false)"))
        // The debt the 0x00-accept builds wrote is dropped the same way, before the restore, and the
        // current key is a different one.
        val pre = init.indexOf("prefs.edit().remove(prefKey(PRE_0X01_ACTIVATION_DEBT_PREF)).apply()")
        assertTrue(pre >= 0 && pre < init.indexOf("needsPostResetActivation = readBoolPref(ACTIVATION_DEBT_PREF, false)"))
        // Its effect is the zeroing, so it reads the key before removing it, and both restores follow.
        val preIf = init.indexOf("if (readBoolPref(PRE_0X01_ACTIVATION_DEBT_PREF, false)) {")
        assertTrue(preIf in 0 until pre)
        assertTrue(pre < init.indexOf("postResetWarmupExtensionActive = readBoolPref("))
        assertTrue(pre < init.indexOf("postResetRequestedAtMs = readLongPref("))
        assertTrue(
            init.contains(
                "if (readBoolPref(PRE_0X01_ACTIVATION_DEBT_PREF, false)) { writeLongPref(\"postResetRequestedAtMs\", 0L) " +
                    "writeBoolPref(\"postResetWarmupExtensionActive\", false)"
            )
        )
        assertEquals(1, count(manager, "readBoolPref(PRE_0X01_ACTIVATION_DEBT_PREF"))
        assertFalse(manager.contains("writeBoolPref(PRE_0X01_ACTIVATION_DEBT_PREF"))
        assertTrue(manager.contains("const val ACTIVATION_DEBT_PREF = \"postResetActivationDebtV2\""))
        assertTrue(manager.contains("const val LEGACY_ACTIVATION_DEBT_PREF = \"needsPostResetActivation\""))
        assertTrue(manager.contains("PRE_0X01_ACTIVATION_DEBT_PREF = \"postResetActivationDebt\""))
        assertEquals(1, count(manager, "readBoolPref(LEGACY_ACTIVATION_DEBT_PREF"))
    }

    @Test
    fun theAckStampIsSetOnlyByTheClaim() {
        assertEquals(1, count(manager, "postResetClearStorageAckAtMs = System.currentTimeMillis()"))
        val claim = member("private fun claimClearStorageConfirmation(")
        assertTrue(claim.contains("synchronized(resetClaimLock)"))
        assertTrue(claim.contains("postResetClearStorageAckAtMs = System.currentTimeMillis()"))
        val response = member("private fun handleClearStorageResponse(")
        assertTrue(response.contains("AiDexRuntimePolicy.clearStorageResponseOutcome(pending, stamped, status)"))
        // The same length rule as the binder peek: a reply longer than a bare ACK is not one.
        assertTrue(response.contains("val status = AiDexRuntimePolicy.clearStorageAckStatus(data)"))
        assertFalse(response.contains("commandAckStatus("))
        assertFalse(response.contains("postResetRequestedAtMs > 0L"))
        assertFalse(Regex("postResetClearStorageAckAtMs =(?!=)").containsMatchIn(response))
        // A re-latch landing after a concurrent release would leave the window with no owner.
        assertFalse(response.contains("clearStorageQuietWindowActive = true"))
        // The binder peek claims before the DISCONNECTED teardown can wipe the posted frame.
        val peek = member("private fun peekCommandAcksOnBinder(")
        assertTrue(peek.contains("AiDexRuntimePolicy.isClearStorageAccepted(plaintext)"))
        assertTrue(peek.contains("claimClearStorageConfirmation("))
        val dispatch = member("private fun dispatchF002Response(")
        assertTrue(dispatch.indexOf("peekCommandAcksOnBinder(") in 0 until dispatch.indexOf("handler.post"))
        // A claim is credited only to an attempt whose own 0xF3 went out.
        assertTrue(claim.contains("postResetClearStorageWriteAtMs == 0L"))
        // ...and drainGattQueue stamps that write before the frame can be on air.
        val drain = member("private fun drainGattQueue()")
        val stamp = drain.indexOf("if (firstResetWrite) postResetClearStorageWriteAtMs = System.currentTimeMillis()")
        val write = drain.indexOf("gatt.writeCharacteristic(characteristic)")
        assertTrue(stamp >= 0 && stamp < write)
        // The stamp means "went out" only because a refused write takes it back.
        assertTrue(drain.indexOf("if (firstResetWrite && !ok) postResetClearStorageWriteAtMs = 0L", write) > write)
    }

    @Test
    fun aBinderClaimIsCommittedEvenWhenTheTeardownWipesThePostedFrame() {
        assertTrue(member("private fun consumeBinderPeeksOnHandler()").contains("commitConfirmedClearStorageIfOwed()"))
        // The post that survives resetConnectionRuntimeState's own wipe...
        val reset = member("private fun resetConnectionRuntimeState(")
        assertTrue(reset.contains("handler.removeCallbacksAndMessages(null) handler.post { consumeBinderPeeksOnHandler()"))
        // ...the teardown tail and the new link's handler block.
        assertTrue(member("private fun teardownOnHandler(").contains("consumeBinderPeeksOnHandler()"))
        assertTrue(member("private fun consumeThenClearSetNewSensorConnectionFlags()").contains("consumeBinderPeeksOnHandler()"))
        // The quiet-window timer treats a claimed 0x01 as dispatched.
        assertTrue(member("private val clearStorageQuietWindowReconnect").contains("ackClaimed = postResetClearStorageAckAtMs > 0L"))
    }

    @Test
    fun abandonNeverTouchesAConfirmedErasesBookkeeping() {
        val abandon = member("private fun abandonPendingReset(")
        // The verdict is taken inside the lock the claim takes, together with its timers.
        assertTrue(abandon.contains("synchronized(resetClaimLock) { if (postResetClearStorageAckAtMs > 0L) return false"))
        assertTrue(abandon.contains("latched } if (wasLatched)"))
        val locked = abandon.after("synchronized(resetClaimLock) {").before("latched }")
        assertTrue(locked.contains("handler.removeCallbacks(postResetDisconnectFallback)"))
        assertTrue(locked.contains("handler.removeCallbacks(clearStorageQuietWindowReconnect)"))
        assertTrue(locked.contains("resetAttemptRequestedAtMs = 0L"))
        assertTrue(locked.contains("pendingResetReconnect = false"))
        // completePostResetReconnect drops the latch and reads the verdict in one critical section.
        val complete = member("private fun completePostResetReconnect(")
        assertTrue(
            complete.contains(
                "if (!pendingResetReconnect) return pendingResetReconnect = false " +
                    "clearStorageQuietWindowActive = false postResetClearStorageAckAtMs > 0L }"
            )
        )
        assertTrue(complete.after("val confirmed = synchronized(resetClaimLock) {").before("resetDiag(")
            .contains("postResetClearStorageAckAtMs > 0L"))
        // resetSensor admits and latches in one step, under both locks.
        val reset = member("override fun resetSensor(): Boolean")
        val admit = reset.after("val admission = synchronized(unpairLock) { synchronized(resetClaimLock) {")
            .before("decision } } when (admission)")
        assertTrue(admit.startsWith(" val decision = AiDexRuntimePolicy.decideResetAdmission("))
        // The latch sits inside the ALLOW-with-a-built-command arm (no nested braces there), and
        // nothing follows the arm inside the lock: a refused or key-less press latches nothing.
        val arm = admit.after("if (decision == AiDexRuntimePolicy.ResetAdmission.ALLOW && cmd != null) {")
        val allow = arm.before("}")
        assertEquals(" ", arm.after("}"))
        assertTrue(allow.contains("pendingResetReconnect = true"))
        // Both latches, and the press time: the quiet window is raised nowhere else either.
        assertTrue(allow.contains("clearStorageQuietWindowActive = true"))
        assertTrue(allow.contains("resetAttemptRequestedAtMs = resetRequestedAt"))
        assertEquals(1, count(manager, "clearStorageQuietWindowActive = true"))
        assertTrue(allow.contains("clearStorageConfirmOwed = false"))
        assertEquals(1, count(reset, "pendingResetReconnect = true"))
        for (forbidden in listOf(
            "setActivationDebt(",
            "needsPostResetActivation",
            "clearPostResetHistoryBarrier(",
            "postResetWarmupExtensionActive",
            "writeBoolPref(",
            "writeLongPref(",
            "markPairKeyResetPending(",
            "pairKeyResetPending",
        )) {
            assertFalse("abandonPendingReset must not contain $forbidden", abandon.contains(forbidden))
        }
    }

    @Test
    fun onlyAnEraseTheSensorAcceptedMarksThePairKeyResetPending() {
        // The declaration and two calls: the confirmed commit and the late accept.
        assertEquals(3, count(manager, "markPairKeyResetPending("))
        assertTrue(member("private fun commitConfirmedClearStorageIfOwed()").contains("markPairKeyResetPending("))
        val response = member("private fun handleClearStorageResponse(")
        assertTrue(
            response.after("ClearStorageResponseOutcome.LATE_ACCEPT ->").before("ClearStorageResponseOutcome.IGNORE ->")
                .contains("markPairKeyResetPending(")
        )
        assertFalse(
            response.after("ClearStorageResponseOutcome.ABANDON ->").before("ClearStorageResponseOutcome.LATE_ACCEPT ->")
                .contains("markPairKeyResetPending(")
        )
        // The flag goes up in one place, and a stored key is required for it.
        assertEquals(1, count(manager, "pairKeyResetPending = true"))
        assertTrue(
            member("private fun markPairKeyResetPending(")
                .contains("val key = persistedPairKey ?: return pairKeyResetPending = true")
        )
    }

    @Test
    fun aPostResetFreshPairSettlesOnlyOnItsOwnSessionAndIsBounded() {
        // Settle: only the fresh pair started by the flag, once its key is persisted.
        val f003 = member("private fun handleF003(")
        assertTrue(
            f003.contains(
                "if (!pairKeyAwaitingLiveValidation) { if (pairKeyResetPending && keyExchangeIsFreshPairAfterReset) { " +
                    "pairKeyResetPending = false"
            )
        )
        // Failures inside the exchange: the fresh pair's own retries end in RESTORE.
        val failure = member("private fun handleKeyExchangeFailure(")
        assertTrue(failure.contains("val freshPairAfterResetSuspect = freshPairAfterReset && phase == Phase.KEY_EXCHANGE"))
        assertTrue(failure.contains("freshPairAfterReset = freshPairAfterResetSuspect,"))
        assertTrue(failure.contains("RESTORE_SAVED_KEY -> {"))
        // The flag all of this reads: set in one place, the post-reset fresh pair's start, and read by
        // the failure handler before its own clear and before the phase changes.
        assertEquals(1, Regex("keyExchangeIsFreshPairAfterReset\\s*=(?!=)\\s*(?=\\S)(?!false\\b)").findAll(manager).count())
        assertEquals(1, count(manager, "keyExchangeIsFreshPairAfterReset = pairKeyResetPending && persistedPairKey != null"))
        assertTrue(
            member("private fun startFreshPairKeyExchange(").contains(
                "keyExchangeUsingSavedPairKey = false keyExchangeIsFreshPairAfterReset = pairKeyResetPending && persistedPairKey != null"
            )
        )
        val snapshot = failure.indexOf("val freshPairAfterReset = keyExchangeIsFreshPairAfterReset")
        assertTrue(snapshot >= 0)
        assertTrue(snapshot < failure.indexOf("keyExchangeIsFreshPairAfterReset = false"))
        assertTrue(snapshot < failure.indexOf("setPhase(Phase.IDLE)"))
        assertTrue(failure.contains("restoreKeptPairKey("))
        // Failures before the exchange (CCCD chain, bonding): bounded per connection instead.
        assertTrue(
            flatten(manager).contains(
                "if (mBluetoothGatt !== gatt) return@post notePostResetFreshPairConnection() " +
                    "val keyStartAction = decidePairKeyStartAction()"
            )
        )
        assertTrue(
            member("private fun notePostResetFreshPairConnection()").contains(
                "if (postResetFreshPairConnections >= KEY_EXCHANGE_MAX_FAILURES) { restoreKeptPairKey("
            )
        )
        assertTrue(member("private fun restoreKeptPairKey(").contains("pairKeyResetPending = false"))
        // The count: guarded by the flag, incremented below the bound, persisted, restored at init
        // from the value read before the flag's setter zeroes it, and cleared by a fresh pair that
        // got a session (the erase did rotate the credential).
        val note = member("private fun notePostResetFreshPairConnection()")
        assertTrue(note.contains("if (!pairKeyResetPending || persistedPairKey == null) return"))
        assertTrue(note.contains("} else { postResetFreshPairConnections += 1 }"))
        assertTrue(
            flatten(manager).contains(
                "private var postResetFreshPairConnections = 0 set(value) { field = value " +
                    "writeIntPref(\"postResetFreshPairConnections\", value) }"
            )
        )
        assertTrue(
            flatten(manager).contains(
                "val freshPairConnections = readIntPref(\"postResetFreshPairConnections\", 0) " +
                    "pairKeyResetPending = readBoolPref(\"pairKeyResetPending\", false) " +
                    "postResetFreshPairConnections = if (pairKeyResetPending) freshPairConnections else 0"
            )
        )
        assertTrue(
            flatten(manager).contains(
                "pairKeyAwaitingLiveValidation = !keyExchangeUsingSavedPairKey " +
                    "if (keyExchangeIsFreshPairAfterReset) { postResetFreshPairConnections = 0 }"
            )
        )
        // The session flag outlives the first frame only while the key awaits validation, and a new
        // mark takes it from the session that was keyed before that erase.
        assertEquals(1, count(f003, "keyExchangeIsFreshPairAfterReset = false"))
        assertTrue(
            f003.contains(
                "Log.i(TAG, \"Post-reset pairing settled by valid live data\") } keyExchangeIsFreshPairAfterReset = false }"
            )
        )
        assertTrue(member("private fun markPairKeyResetPending(").contains("keyExchangeIsFreshPairAfterReset = false"))
        // Every change of the flag starts the count afresh.
        assertTrue(
            flatten(manager).contains(
                "writeBoolPref(\"pairKeyResetPending\", value) postResetFreshPairConnections = 0"
            )
        )
        // The decision reads the flag.
        assertTrue(member("private fun decidePairKeyStartAction()").contains("pairKeyResetPending = pairKeyResetPending,"))
    }

    @Test
    fun androidBondRemovalStaysAtItsThreeSites() {
        // removeBondSafely (post-reset after a confirmed erase, confirmed DELETE_BOND) and the
        // user's own forget. No new automatic removal path.
        assertEquals(2, count(manager, "getMethod(\"removeBond\")"))
        assertEquals(2, Regex("(?<!fun )removeBondSafely\\(").findAll(manager).count())
        val complete = member("private fun completePostResetReconnect(")
        val confirmedArm = complete.after("if (confirmed) {").before("} else {")
        assertTrue(confirmedArm.contains("removeBondSafely("))
        assertTrue(member("private fun handleDeleteBondResponse(").contains("removeBondSafely("))
        // The bond step is decided at completion, right after removeBond(), through the seam; the
        // hold starts before the settle delay.
        assertTrue(complete.contains("AiDexRuntimePolicy.postResetReconnectStep(confirmed"))
    }

    @Test
    fun disconnectOwnershipIsDecidedBeforeTheRecoveryAndAuthExits() {
        val disconnected = manager.after("} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {")
        val owner = disconnected.indexOf("AiDexRuntimePolicy.decideDisconnectOwner(")
        assertTrue(owner > 0)
        assertTrue(owner < disconnected.indexOf("handlePendingInvalidSetupRecovery()"))
        assertTrue(owner < disconnected.indexOf("completeStaleConnectionRecovery(\"disconnect-callback\""))
        assertTrue(owner < disconnected.indexOf("when (status) {"))
        // Each owner arm acts: the unconfirmed unpair through the claim, the confirmed reset
        // through completePostResetReconnect.
        val arms = flatten(disconnected.before("when (status) {"))
        val unpairArm = arms.after("DisconnectOwner.UNCONFIRMED_UNPAIR ->").before("DisconnectOwner.POST_RESET ->")
        assertTrue(unpairArm.contains("abandonUnconfirmedUnpair("))
        // Only a won claim returns; a lost one falls through to the ordinary tail.
        assertTrue(unpairArm.contains(") return } }"))
        assertFalse(unpairArm.contains("} return }"))
        assertTrue(arms.after("DisconnectOwner.POST_RESET ->").before("DisconnectOwner.INVALID_SETUP_RECOVERY ->")
            .contains("completePostResetReconnect("))
        // The old tail branches moved up into the ownership decision.
        assertFalse(disconnected.before("override fun onMtuChanged").contains("if (pendingUnpairDisconnect) {"))
    }

    @Test
    fun theUnpairLatchIsConsumedThroughOneClaim() {
        assertTrue(member("private fun claimUnpairLatch()").contains("synchronized(unpairLock)"))
        val ack = member("private fun handleDeleteBondResponse(")
        assertTrue(ack.indexOf("if (!claimUnpairLatch()) return") in 0 until ack.indexOf("clearAfterConfirmedUnpair("))
        val abandonUnpair = member("private fun abandonUnconfirmedUnpair(")
        assertTrue(abandonUnpair.contains("synchronized(unpairLock) { if (!pendingUnpairDisconnect) return false pendingUnpairDisconnect = false isUnpaired = false }"))
        assertTrue(member("private fun failGattWrite(").contains("abandonUnconfirmedUnpair("))
        assertTrue(member("override fun connectDevice(").contains("abandonUnconfirmedUnpair("))
        val unpair = member("override fun unpairSensor(): Boolean")
        assertTrue(unpair.contains("AiDexRuntimePolicy.decideUnpairAdmission("))
        assertTrue(unpair.indexOf("synchronized(unpairLock)") in 0 until unpair.indexOf("enqueueGattOp("))
    }

    @Test
    fun theBondHoldIsReleasedBeforeTheGattGuardInBonded() {
        val bonded = member("override fun bonded()")
        val hold = bonded.indexOf("postResetBondHold != null")
        val guard = bonded.indexOf("mBluetoothGatt ?: return")
        assertTrue(hold in 0 until guard)
        assertTrue(bonded.substring(hold, guard).contains("handler.post { pollPostResetBondHold("))
        // No second removeBond from the hold, and no timed return out of it: the 0L has to come
        // after enterBroadcastOnlyFallback, which stamps elapsedRealtime() for a paired sensor.
        val publish = member("private fun publishPostResetBondHold(")
        assertFalse(publish.contains("removeBond"))
        assertTrue(publish.indexOf("if (_isPaused || isUnpaired || forgotten) return null") in 0 until publish.indexOf("postResetBondHold = hold"))
        val tail = member("private fun completePostResetReconnect(").after("if (confirmed) {")
        val published = tail.indexOf("publishPostResetBondHold(")
        val reset = tail.indexOf("reconnect.reset()")
        val entry = tail.indexOf("enterBroadcastOnlyFallback(reason = \"post-reset-bond-hold\"")
        val closed = tail.indexOf("close()")
        assertTrue(published in 0 until closed && closed < reset && reset < entry)
        // The fallback is entered only for the hold this call published, and not for a paused,
        // unpaired or forgotten sensor.
        assertTrue(
            tail.substring(reset, entry).contains(
                "if (hold != null && postResetBondHold === hold && !_isPaused && !isUnpaired && !forgotten)"
            )
        )
        assertTrue(tail.indexOf("broadcastFallbackEnteredAtElapsed = 0L", entry) > entry)
        assertTrue(member("override fun connectDevice(").contains("(reconnect.isBroadcastOnlyMode || postResetBondHold != null) && !stop"))
        val poll = member("private fun pollPostResetBondHold(")
        assertFalse(poll.contains("removeBond"))
        // A stop-only pause keeps the hold: the pause check comes before the hold is dropped.
        val pauseCheck = poll.indexOf("if (stop || _isPaused || isUnpaired || forgotten) return false")
        assertTrue(pauseCheck in 0 until poll.indexOf("postResetBondHold = null"))
        // Every exit by the user or the lifecycle clears the hold.
        val reconnect = member("override fun manualReconnectNow()")
        assertTrue(reconnect.indexOf("postResetBondHold = null") in 0 until reconnect.indexOf("connectDevice(0L)"))
        for (caller in listOf("override fun softDisconnect()", "override fun forgetVendor(unbond: Boolean)", "override fun onTerminalFree()")) {
            val body = member(caller)
            assertTrue(caller, body.indexOf("postResetBondHold = null") in 0 until body.indexOf("teardownOnHandler("))
        }
        val resume = member("override fun rePairSensor()").after("handler.postDelayed({")
        assertTrue(resume.indexOf("postResetBondHold = null") in 0 until resume.indexOf("connectDevice(500L)"))
        assertTrue(resume.indexOf("handler.removeCallbacks(postResetSettle)") in 0 until resume.indexOf("connectDevice(500L)"))
        // A confirmed latch that meets STATE_CONNECTED on the old bond does not adopt that link.
        val connected = flatten(
            manager.after("if (newState == BluetoothProfile.STATE_CONNECTED) {")
                .before("} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {")
        )
        val latchArm = connected.indexOf("if (pendingResetReconnect) {")
        val oldBond = connected.indexOf(
            "else if (bondStateAtConnection != BluetoothDevice.BOND_NONE) { " +
                "completePostResetReconnect(trigger = \"connected-on-old-bond\", stateAlreadyReset = false) return }"
        )
        assertTrue(latchArm >= 0 && oldBond > latchArm && oldBond < connected.indexOf("gatt.requestMtu(512)"))
        assertTrue(connected.substring(latchArm, oldBond).contains("abandonPendingReset(\"stale-reset-dropped-on-connect\")"))
        assertTrue(member("private fun pollPostResetBondHold(").contains("AiDexRuntimePolicy.mayLeavePostResetBondHold("))
        // Every scan window polls the hold, and the rebind that would re-key the routed address is off.
        assertTrue(member("private fun maybeLeaveBroadcastOnlyFallback()").contains("pollPostResetBondHold("))
        assertTrue(member("private fun startBroadcastScan(").contains("postResetBondHold == null &&"))
        // The hold is entered at completion, before the settle delay, and the settle step respects a pause.
        val complete = member("private fun completePostResetReconnect(")
        assertTrue(complete.indexOf("publishPostResetBondHold(") in 0 until complete.indexOf("handler.postDelayed(postResetSettle"))
        assertTrue(member("private val postResetSettle").contains("if (_isPaused || isUnpaired || forgotten) return@Runnable"))
        assertTrue(member("override fun manualReconnectNow()").contains("handler.removeCallbacks(postResetSettle)"))
    }

    @Test
    fun anExhaustedBondNoneStillScansForBroadcasts() {
        val bonded = member("override fun bonded()")
        val exhausted = bonded.after("max auth failures reached")
        val pause = exhausted.indexOf("softDisconnect()")
        assertTrue(pause >= 0)
        assertTrue(exhausted.indexOf("stop = false", pause) > pause)
        assertTrue(exhausted.indexOf("startBroadcastScan(\"bond-none-exhausted\")", pause) > pause)
    }

    @Test
    fun teardownUsesTheTicketedHandlerHop() {
        assertFalse(manager.contains("runOnHandlerBlocking"))
        assertFalse(manager.contains("startNewSensorGeneration"))
        for (caller in listOf("override fun forgetVendor(unbond: Boolean)", "override fun onTerminalFree()", "override fun softDisconnect()")) {
            assertTrue(caller, member(caller).contains("teardownOnHandler("))
        }
        val teardown = member("private fun teardownOnHandler(")
        assertTrue(teardown.contains("if (!ticket.tryStart()) return@Runnable"))
        assertTrue(teardown.contains("if (ticket.tryCancel()) {"))
        val wait = member("private fun <T> runOnHandlerAndWait(")
        assertTrue(wait.contains("if (!ticket.tryStart()) return@post"))
        assertTrue(wait.contains("if (ticket.tryCancel()) throw"))
        // A forgotten manager keeps its late wipe; the cancel comes after that check.
        assertTrue(teardown.indexOf("if (forgotten)") in 0 until teardown.indexOf("ticket.tryCancel()"))
        assertTrue(member("private fun <T> runOnHandlerAndWait(").contains("ticket.tryCancel()"))
    }

    @Test
    fun noGenericActuatorEntryRemains() {
        assertFalse(manager.contains("fun activateSensor("))
        val maintenance = member("override fun sendMaintenanceCommand(")
        assertFalse(maintenance.contains("enqueueGattOp("))
        assertFalse(maintenance.contains("buildEncrypted("))
        val viewModel = stripComments(File(repoRoot(), VIEW_MODEL).readText())
        assertFalse(viewModel.contains("sendAiDexMaintenanceCommand"))
    }

    @Test
    fun historyHandlersGoThroughTheirSeams() {
        val raw = member("private fun handleHistoryRawResponse(")
        assertTrue(raw.contains("AiDexHistoryPolicy.decideEmptyRawPage("))
        assertTrue(raw.contains("HistoryMerge.nextRawCursorAfterPage("))
        assertFalse(raw.contains("containsKey("))
        val brief = member("private fun handleHistoryBriefResponse(")
        val cap = brief.indexOf("AiDexHistoryPolicy.rowsBelowCap(")
        assertTrue(cap >= 0 && cap < brief.indexOf("HistoryMerge.mergeHistoryEntries("))
        // The merge consumes the capped rows, not the whole page, and the page loop stops at the cap.
        assertTrue(brief.contains("HistoryMerge.mergeHistoryEntries(mergeable,"))
        assertTrue(brief.contains("AiDexHistoryPolicy.nextBriefCursor("))
        assertTrue(brief.contains("if (historyBriefNextIndex < cap)"))
        assertTrue(member("private fun storeHistoryEntries(").contains("AiDexHistoryPolicy.historyStoreRejection("))
        val range = member("private fun handleHistoryRangeResponse(")
        assertTrue(range.indexOf("historyRoomBufferDropped = true") in 0 until range.indexOf("clearPendingRoomHistory(\"history-range-reset\")"))
        assertTrue(member("private fun enqueueExclusiveClearStorage(").contains("historyRoomBufferDropped = true"))
        // The no-stream ladder waits out a reset instead of being cancelled by it.
        val watchdog = member("private val noStreamWatchdog")
        val defer = watchdog.indexOf("if (pendingResetReconnect || clearStorageQuietWindowActive) { scheduleNoStreamWatchdog() return@Runnable }")
        assertTrue(defer in 0 until watchdog.indexOf("AiDexStreamingPolicy.decideNoStreamRecovery("))
    }

    @Test
    fun remainingDecisionsGoThroughTheirSeams() {
        assertTrue(member("private fun handleBroadcastPayload(").contains("AiDexParser.parseBroadcastSample(payload)"))
        assertFalse(manager.contains("fallbackGlucose"))
        assertTrue(member("private fun applyParsedSessionStartTime(").contains("AiDexRuntimePolicy.sessionStartPredatesReset("))
        assertTrue(member("private fun applyParsedSessionStartTime(").contains("aiDexSessionStartOffsetSeconds("))
        assertFalse(member("private fun applyParsedSessionStartTime(").contains("2L * 60_000L"))
        assertTrue(member("private val clearStorageQuietWindowReconnect").contains("AiDexRuntimePolicy.decideQuietWindowExpiry("))
        assertTrue(member("private fun maybeLeaveBroadcastOnlyFallback()").contains("AiDexRuntimePolicy.decideBroadcastFallbackExit("))
        assertTrue(member("private fun failGattWrite(").contains("AiDexRuntimePolicy.decideUnconfirmedWrite("))
    }

    @Test
    fun aZeroSessionStartReachesTheCommandOnlyOnThePolicysWord() {
        val body = member("private fun applyParsedSessionStartTime(")
        // Only the source that may activate reaches the decision: the two legacy 0x21 handlers pass
        // allowActivation = false, and zeros in their reply must never become a 0x20.
        assertTrue(
            body.contains(
                "if (allowActivation) { if (settleUnconfirmedActivation(startMs = 0L, source = source)) return " +
                    "val hasStoredPairCredential = persistedPairKey != null"
            )
        )
        // Three callers and the declaration: no other path reaches the decision.
        assertEquals(4, count(manager, "applyParsedSessionStartTime("))
        assertEquals(2, count(manager, "allowActivation = false,"))
        assertEquals(1, count(manager, "allowActivation = true,"))
        // Which source that is: the CGM 0x2AAA read, never a legacy 0x21 reply.
        assertTrue(member("private fun handleCGMSessionStartTime(").contains("allowActivation = true,"))
        for (legacy in listOf("private fun handleLegacyStartTimeResponse(", "private fun handleLegacyCombinedMetadataResponse(")) {
            assertTrue("$legacy must not activate", member(legacy).contains("allowActivation = false,"))
        }
        // The evidence that keeps a running sensor away from 0x20, from the fields that hold it into
        // the parameters that read it.
        assertTrue(body.contains("val hasDownloadedHistory = historyRawNextIndex > 0 || historyBriefNextIndex > 0"))
        assertTrue(body.contains("val hasAcceptedReading = lastGlucoseTimeMs > 0L"))
        val call = body.after("AiDexRuntimePolicy.decideZeroSessionStartActivation(").before(")")
        for (argument in listOf(
            "needsPostResetActivation = needsPostResetActivation,",
            "autoActivationAttemptedThisConnection = autoActivationAttemptedThisConnection,",
            "hasStoredPairCredential = hasStoredPairCredential,",
            "hasDownloadedHistory = hasDownloadedHistory,",
            "hasAcceptedReading = hasAcceptedReading,",
            "bondValidatedByStreaming = bondValidatedByStreaming,",
        )) {
            assertTrue("missing argument: $argument", call.contains(argument))
        }
        // Post-reset zero, fresh zero and a start predating the erase; the fourth hit in the manager
        // is the declaration, so the manager has no other call of startNewSensor().
        assertEquals(3, count(body, "startNewSensor()"))
        assertEquals(4, count(manager, "startNewSensor()"))
        for (sender in listOf("FRESH_SENSOR", "POST_RESET")) {
            val arm = zeroStartArm(body, sender)
            val latch = arm.indexOf("autoActivationAttemptedThisConnection = true")
            assertTrue("$sender: the once-per-connection latch comes first", latch >= 0 && latch < arm.indexOf("startNewSensor()"))
        }
        for (refusal in listOf("ALREADY_ATTEMPTED", "KNOWN_RUNNING")) {
            assertFalse("$refusal must not start the sensor", zeroStartArm(body, refusal).contains("startNewSensor("))
        }
    }

    @Test
    fun theHandshakeFlagIsSetOnStreamingAndNeverCleared() {
        assertTrue(member("private fun enterStreamingPhase(").contains("handshakeCompleted = true setPhase(Phase.STREAMING)"))
        assertEquals(1, count(manager, "handshakeCompleted = true"))
        assertEquals(0, count(manager, "handshakeCompleted = false"))
        // Starts false, and the assignment above is the only one: nothing clears or flips it.
        assertTrue(manager.contains("@Volatile private var handshakeCompleted: Boolean = false"))
        assertEquals(1, count(manager, "handshakeCompleted ="))
        assertTrue(manager.contains("override fun hasCompletedHandshake(): Boolean = handshakeCompleted"))
    }

    @Test
    fun theSetupRollbackKeepsAFinishedHandshakeAndTheAndroidBond() {
        val source = flatten(stripComments(File(repoRoot(), SENSOR_BLUETOOTH).readText()))
        val rollback = source
            .after("public static void rollbackUnpairedAiDexSensor(")
            .before("public void startDevices(")
        // The whole guard: without the negation and the keep branch an inverted call still matches.
        // A handshake that ever completed keeps the sensor, not a link that happens to be up now.
        assertTrue(
            rollback.contains(
                "if (!tk.glucodata.drivers.aidex.AiDexSetupPolicy.mayRollBack( " +
                    "driver.isVendorPaired(), driver.hasCompletedHandshake())) { paired = true; break; }"
            )
        )
        assertTrue(rollback.contains("teardownLeftoverAiDex(context, victim);"))
        // Teardown never removes the Android bond, and nothing else in this file asks for it.
        val teardown = source
            .after("private static void teardownLeftoverAiDex(Context context, SuperGattCallback leftover) {")
            .before("private static void")
        assertTrue(teardown.contains(".forgetVendor(false);"))
        assertEquals(1, count(source, "forgetVendor("))
    }

    @Test
    fun theSetupWizardSucceedsOnAStreamingDriver() {
        val source = flatten(stripComments(File(repoRoot(), WIZARD).readText()))
        assertTrue(source.contains("AiDexSetupPolicy.decideConnectingState("))
        // What the wizard polls goes into the policy, and every deadline back into the decision.
        for (wiring in listOf(
            "sessionEstablished = driver?.isVendorConnected() == true,",
            "pairingInProgress = pairing,",
            "driverGaveUp = gaveUp,",
            "deadlineMs = deadlines.softMs,",
            "hardDeadlineMs = deadlines.hardMs,",
            "notConnectedDeadlineMs = deadlines.notConnectedMs,",
            "notConnectedLimitMs = AIDEX_SETUP_NOT_CONNECTED_LIMIT_MS,",
            "graceMs = AIDEX_SETUP_PAIRING_GRACE_MS,",
            "val now = SystemClock.elapsedRealtime()",
        )) {
            assertTrue("missing wiring: $wiring", source.contains(wiring))
        }
        // These go into both nextDeadlines and decideConnectingState; contains() would still pass
        // with one of the two lost (no gave-up in the deadlines rolls back on the first give-up).
        for (shared in listOf("pairingInProgress = pairing,", "driverGaveUp = gaveUp,", "nowMs = now,")) {
            assertEquals("both policy calls take $shared", 2, count(source, shared))
        }
        // The driver's hold runs on elapsedRealtime; a wall-clock deadline would drift against it.
        assertFalse(source.contains("System.currentTimeMillis()"))
        // Each outcome does what it says, and each one ends the wait (without the return the
        // loop would spin with no delay): success on READY, rollback only of a new sensor on
        // TIMED_OUT, "Sensor added" on KEPT.
        val ready = connectingArm(source, "READY")
        assertTrue(ready.contains("currentStep = AiDexSetupStep.SUCCESS return"))
        val timedOut = connectingArm(source, "TIMED_OUT")
        assertTrue(timedOut.contains("if (mayRollback) { SensorBluetooth.rollbackUnpairedAiDexSensor(context, name) }"))
        assertTrue(timedOut.contains("currentStep = AiDexSetupStep.SCAN return"))
        // The loop feeds each result back and keeps polling in every waiting state: only READY,
        // TIMED_OUT and KEPT end the wait, every other state falls through to the delay.
        assertTrue(source.contains("deadlines = AiDexSetupPolicy.nextDeadlines( current = deadlines,"))
        assertTrue(source.contains("while (true) {"))
        assertEquals(1, count(source, "when (state) {"))
        val arms = source.after("when (state) {").before("else -> delay(500)")
        for (waiting in listOf(
            "ConnectingState.CONNECTING",
            "ConnectingState.AWAITING_PAIRING_CONFIRMATION",
            "ConnectingState.NOT_CONNECTED",
        )) {
            assertFalse("$waiting must not have an arm of its own", arms.contains(waiting))
        }
        // The bond bookkeeping behind the "Not connected" text, in this order: the reset must see
        // the previous poll's gaveUp, so it has to come before gaveUpBefore is updated.
        assertTrue(
            source.contains(
                "if (gaveUpBefore && !gaveUp) { sawPairing = false sawBonded = false } " +
                    "gaveUpBefore = gaveUp " +
                    "if (pairing) sawPairing = true " +
                    "if (sawPairing && bondState == BluetoothDevice.BOND_BONDED) sawBonded = true " +
                    "notConnectedReason = AiDexSetupPolicy.notConnectedReason(sawPairing, sawBonded)"
            )
        )
        for (mapping in listOf(
            "AiDexSetupPolicy.NotConnectedReason.PAIRING_NOT_CONFIRMED -> R.string.aidex_setup_pairing_not_confirmed",
            "AiDexSetupPolicy.NotConnectedReason.CONNECT_FAILED -> R.string.aidex_setup_connect_failed",
        )) {
            assertTrue("missing: $mapping", source.contains(mapping))
        }
        // A timeout on a sensor this setup added that the rollback keeps anyway (its own rule: a
        // stored key or a completed key exchange) ends as added: no rollback, and the wizard closes
        // through SUCCESS after this job has returned, not from inside it. A configured sensor's
        // failed re-setup stays a failure.
        assertTrue(
            source.contains(
                "sensorStays = mayRollback && driver?.let { " +
                    "!AiDexSetupPolicy.mayRollBack(it.isVendorPaired(), it.hasCompletedHandshake()) } == true,"
            )
        )
        // That is the only place a stored key counts: success must never wait for one (it is
        // written only after a valid reading, which a new sensor cannot give within the wait).
        assertEquals(1, count(source, "isVendorPaired()"))
        val kept = connectingArm(source, "KEPT")
        assertTrue(kept.contains("addedWithoutLink = true"))
        assertTrue(kept.contains("currentStep = AiDexSetupStep.SUCCESS return"))
        assertFalse(kept.contains("rollbackUnpairedAiDexSensor"))
        assertFalse(kept.contains("onComplete("))
        assertTrue(
            source.contains(
                "title = stringResource( if (addedWithoutLink) R.string.aidex_setup_added_title else R.string.status_connected )"
            )
        )
        // Retry sits in the status column under the text, not over it, and the screen draws it.
        assertTrue(source.contains("action = { Button(onClick = { retrySetupConnection() }) {"))
        val statusScreen = flatten(stripComments(File(repoRoot(), STATUS_SCREEN).readText()))
        assertTrue(statusScreen.contains("SensorSetupStatusTone.Attention -> action?.invoke()"))
        // The column scrolls, or a short screen (landscape, large font) squeezes the button out.
        assertTrue(statusScreen.contains(".fillMaxSize() .verticalScroll(rememberScrollState())"))
        // ...and the public screen hands the button on: the private one defaults action to null.
        val notConnectedScreen = statusScreen
            .after("fun SensorSetupNotConnectedScreen(")
            .before("fun SensorSetupSuccessScreen(")
        assertTrue(notConnectedScreen.contains("tone = SensorSetupStatusTone.Attention,"))
        assertTrue(notConnectedScreen.contains("action = action"))
        // The grace after a pairing has to cover the driver's own key-exchange budget, plus the few
        // seconds between the last pairing poll and the start of that budget.
        val grace = constantMs(source, "AIDEX_SETUP_PAIRING_GRACE_MS")
        val keyExchange = constantMs(flatten(manager), "KEY_EXCHANGE_TIMEOUT_MS")
        assertTrue("grace $grace must cover $keyExchange + 5 s", grace >= keyExchange + 5_000L)
        // The wait for Retry has to end before the driver's own retry out of broadcast-only can
        // raise another pairing prompt with nobody there.
        val limit = constantMs(source, "AIDEX_SETUP_NOT_CONNECTED_LIMIT_MS")
        val driverRetry = constantMs(flatten(manager), "BROADCAST_FALLBACK_RETRY_MS")
        assertTrue("limit $limit must be in (0, $driverRetry)", limit in 1 until driverRetry)
    }

    @Test
    fun anUnconfirmedActivationIsDecidedByTheSessionStartNeverResent() {
        val ack = member("private fun handleNewSensorAck(")
        assertTrue(ack.contains("val verdict = AiDexRuntimePolicy.setNewSensorAck(statusByte)"))
        // 0x01 has its own branch at both entry points: only 0x00 is accepted outright, and only a
        // refusal is latched on the binder.
        assertTrue(ack.contains("if (verdict == AiDexRuntimePolicy.SetNewSensorAck.ACCEPTED) {"))
        assertTrue(ack.contains("} else if (verdict == AiDexRuntimePolicy.SetNewSensorAck.UNCONFIRMED) {"))
        assertTrue(
            member("private fun peekCommandAcksOnBinder(")
                .contains("if (AiDexRuntimePolicy.isSetNewSensorNack(plaintext)) { setNewSensorNackSeen = true }")
        )
        val unconfirmed = ack
            .after("verdict == AiDexRuntimePolicy.SetNewSensorAck.UNCONFIRMED) {")
            .before("} else {")
        assertTrue(unconfirmed.contains("setNewSensorUnconfirmedAckAtElapsed = SystemClock.elapsedRealtime()"))
        // The verdict comes from a session-start read, so one has to be asked for.
        assertTrue(unconfirmed.contains("handler.postDelayed({ readCGMSessionCharacteristics() }, UNCONFIRMED_ACTIVATION_SETTLE_MS)"))
        val settle = member("private fun settleUnconfirmedActivation(")
        // Neither the unconfirmed answer nor its verdict sends anything: no second 0x20 on any path.
        val senders = listOf("startNewSensor(", "gattQueue.add(", "enqueueGattOp(", "queueSetNewSensorWrite(", "writeCharacteristic(")
        for (forbidden in senders + listOf("rollbackSetNewSensorLocalSession(", "setNewSensorNackSeen = true")) {
            assertFalse("the unconfirmed answer must not call $forbidden", unconfirmed.contains(forbidden))
        }
        for (forbidden in senders) {
            assertFalse("the verdict must not call $forbidden", settle.contains(forbidden))
        }
        // A refusal's rollback clears the queued-this-connection latch; what still stops a zeros
        // read after it from auto-starting again is the per-connection latch, cleared only when a
        // new connection comes up.
        assertEquals(1, count(manager, "autoActivationAttemptedThisConnection = false"))
        val connected = flatten(
            manager.after("if (newState == BluetoothProfile.STATE_CONNECTED) {")
                .before("} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {")
        )
        assertEquals(1, count(connected, "autoActivationAttemptedThisConnection = false"))
        assertFalse(member("private fun rollbackSetNewSensorLocalSession(").contains("autoActivationAttemptedThisConnection"))
        // The verdict takes the 0x20's own start stamp, so an older kept session is never "took effect".
        assertTrue(member("private fun commitSetNewSensorLocalSession(").contains("val now = System.currentTimeMillis() setNewSensorDispatchedAtMs = now"))
        for (argument in listOf(
            "startMs = startMs,",
            "dispatchAtMs = setNewSensorDispatchedAtMs,",
            "ackAtMs = ackAt,",
            "nowMs = SystemClock.elapsedRealtime(),",
            "settleMs = UNCONFIRMED_ACTIVATION_SETTLE_MS,",
            "slackMs = AiDexRuntimePolicy.POST_RESET_START_SLACK_MS,",
        )) {
            assertTrue("missing argument: $argument", settle.contains(argument))
        }
        assertEquals(3_000L, constantMs(flatten(manager), "UNCONFIRMED_ACTIVATION_SETTLE_MS"))
        // With no unconfirmed 0x20 pending every read goes back to the caller as usual (a non-zero
        // start is accepted, zeros go to the zero-start decision): both call sites return early on
        // true, so this guard, first in the body, decides every ordinary session-start read.
        assertTrue(
            settle.contains(
                "source: String): Boolean { val ackAt = setNewSensorUnconfirmedAckAtElapsed " +
                    "if (ackAt <= 0L) return false return when ("
            )
        )
        // Each verdict does what it says; with one pending, only a confirmed start is handed back.
        val started = unconfirmedArm(settle, "STARTED")
        assertTrue(started.contains("setNewSensorUnconfirmedAckAtElapsed = 0L"))
        assertTrue(started.contains(" false }") && !started.contains(" true }"))
        val again = unconfirmedArm(settle, "READ_AGAIN")
        assertTrue(again.contains("handler.postDelayed({ readCGMSessionCharacteristics() }, UNCONFIRMED_ACTIVATION_SETTLE_MS)"))
        assertTrue(again.contains(" true }") && !again.contains(" false }"))
        val refused = unconfirmedArm(settle, "REFUSED")
        assertTrue(refused.contains("setNewSensorUnconfirmedAckAtElapsed = 0L"))
        assertTrue(refused.contains("showTransientStatus(\"Activation refused by the sensor\""))
        assertTrue(refused.contains(" true }") && !refused.contains(" false }"))
        // Latched before the post, so a disconnect wipe that drops the rollback cannot keep it: the
        // teardown's consume rolls back a dispatched attempt with the latch set.
        assertTrue(
            refused.contains(
                "setNewSensorNackSeen = true handler.post { rollbackSetNewSensorLocalSession(\"unconfirmed-refused\") " +
                    "if (startMs > 0L) readCGMSessionCharacteristics() }"
            )
        )
        assertTrue(member("private fun noteSetNewSensorDispatched(").contains("commitSetNewSensorLocalSession() setNewSensorWriteDispatchedThisAttempt = true"))
        // Whole bodies up to the rollback, so no guard can slip in ahead of it.
        assertTrue(
            member("private fun consumeBinderPeeksOnHandler()").contains(
                "consumeBinderPeeksOnHandler() { consumePendingSetNewSensorNackOnHandler() commitConfirmedClearStorageIfOwed() }"
            )
        )
        assertTrue(
            member("private fun consumePendingSetNewSensorNackOnHandler()").contains(
                "consumePendingSetNewSensorNackOnHandler() { val fate = AiDexRuntimePolicy.setNewSensorLocalFate( " +
                    "dispatched = setNewSensorWriteDispatchedThisAttempt, nackSeen = setNewSensorNackSeen, ) when (fate) { " +
                    "AiDexRuntimePolicy.SetNewSensorLocalFate.ROLLBACK_SENSOR_NACK -> rollbackSetNewSensorLocalSession(\"sensor-nack\")"
            )
        )
        // The verdict decides the read before it is accepted as authoritative (on zeros, before the
        // zero-start decision); a refused old start is accepted only by the read after the rollback.
        val body = member("private fun applyParsedSessionStartTime(")
        assertTrue(body.contains("if (settleUnconfirmedActivation(startMs = startMs, source = source)) return hasAuthoritativeSessionStart = true"))
        // Cleared on every exit: a new connection, a rollback, a new attempt, and both verdicts.
        assertEquals(5, count(manager, "setNewSensorUnconfirmedAckAtElapsed = 0L"))
        assertTrue(
            member("private fun consumeThenClearSetNewSensorConnectionFlags()")
                .contains("consumeBinderPeeksOnHandler() setNewSensorUnconfirmedAckAtElapsed = 0L")
        )
        assertTrue(
            member("private fun rollbackSetNewSensorLocalSession(")
                .contains("(reason: String) { setNewSensorUnconfirmedAckAtElapsed = 0L")
        )
        assertTrue(
            member("private fun queueSetNewSensorWrite(").before("gattQueue.add(")
                .contains("setNewSensorUnconfirmedAckAtElapsed = 0L")
        )
        assertEquals(1, count(unconfirmedArm(settle, "STARTED"), "setNewSensorUnconfirmedAckAtElapsed = 0L"))
        assertEquals(1, count(unconfirmedArm(settle, "REFUSED"), "setNewSensorUnconfirmedAckAtElapsed = 0L"))
    }

    // -- helpers --

    /**
     * One arm of settleUnconfirmedActivation's `when`, up to the next arm or, for the last one, to
     * the end of the member (callers check contents, not endings, so any arm order works).
     */
    private fun unconfirmedArm(settle: String, verdict: String): String {
        val rest = settle.after("AiDexRuntimePolicy.UnconfirmedActivation.$verdict -> {")
        val next = rest.indexOf("AiDexRuntimePolicy.UnconfirmedActivation.")
        return if (next >= 0) rest.substring(0, next) else rest
    }

    /** One arm of the wizard's `when (state)`, up to the next arm or the delay, in any arm order. */
    private fun connectingArm(source: String, state: String): String {
        val rest = source.after("AiDexSetupPolicy.ConnectingState.$state -> {")
        val ends = listOf(rest.indexOf("AiDexSetupPolicy.ConnectingState."), rest.indexOf("else -> delay(500)")).filter { it >= 0 }
        assertTrue("no end for arm $state", ends.isNotEmpty())
        return rest.substring(0, ends.min())
    }

    /** One arm of the zero-start `when`, up to the next arm or the end of the `when`, in any arm order. */
    private fun zeroStartArm(body: String, outcome: String): String {
        val rest = body.after("AiDexRuntimePolicy.ZeroStartActivation.$outcome ->")
        val ends = listOf(rest.indexOf("AiDexRuntimePolicy.ZeroStartActivation."), rest.indexOf("return")).filter { it >= 0 }
        assertTrue("no end for arm $outcome", ends.isNotEmpty())
        return rest.substring(0, ends.min())
    }

    /** Value of a `const val NAME = 10L * 60_000L`-style constant (a product of integer literals). */
    private fun constantMs(source: String, name: String): Long {
        val match = Regex("const val $name = ([0-9_L* ]+)").find(source)
        assertTrue("missing constant: $name", match != null)
        return match!!.groupValues[1].split("*").fold(1L) { product, factor ->
            product * factor.trim().replace("_", "").removeSuffix("L").toLong()
        }
    }

    /** Body of the member whose declaration starts with [signature], up to the next member at class indent. */
    private fun member(signature: String): String {
        val at = manager.indexOf("\n    $signature")
        assertTrue("missing: $signature", at >= 0)
        val rest = manager.substring(at + 1)
        val next = MEMBER_START.find(rest, signature.length)
        return flatten(if (next == null) rest else rest.substring(0, next.range.first))
    }

    private fun count(source: String, needle: String): Int {
        var n = 0
        var i = source.indexOf(needle)
        while (i >= 0) {
            n++
            i = source.indexOf(needle, i + needle.length)
        }
        return n
    }

    private fun flatten(source: String) = source.replace(Regex("\\s+"), " ")

    // Kotlin's substringAfter/substringBefore return the whole string when the delimiter is
    // missing, which turns a pin on a slice into a pin on the whole member. These fail instead.
    private fun String.after(delimiter: String): String {
        val at = indexOf(delimiter)
        assertTrue("missing: $delimiter", at >= 0)
        return substring(at + delimiter.length)
    }

    private fun String.before(delimiter: String): String {
        val at = indexOf(delimiter)
        assertTrue("missing: $delimiter", at >= 0)
        return substring(0, at)
    }

    // No string literal in these files holds "//" or "/*" and no block comment nests.
    private fun stripComments(source: String): String =
        source.replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("(?m)//.*$"), " ")

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, MANAGER).isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val MANAGER = "Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt"
        const val VIEW_MODEL = "Common/src/mobile/java/tk/glucodata/ui/viewmodel/SensorViewModel.kt"
        const val SENSOR_BLUETOOTH = "Common/src/main/java/tk/glucodata/SensorBluetooth.java"
        const val WIZARD = "Common/src/mobile/java/tk/glucodata/ui/setup/AiDexSetupWizard.kt"
        const val STATUS_SCREEN = "Common/src/mobile/java/tk/glucodata/ui/setup/SensorSetupStatusScreen.kt"
        val MEMBER_START = Regex(
            "\n    (?:@\\S+ )*(?:private |override |internal |protected |public )*" +
                "(?:fun|val|var|inner|data|enum|sealed|object|class) "
        )
    }
}
