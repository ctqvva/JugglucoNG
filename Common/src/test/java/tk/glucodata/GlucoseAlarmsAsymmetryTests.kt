package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation of the two `GlucoseAlarms` implementations, before either is changed.
 *
 * This is the category S pair the plan singles out: "GlucoseAlarms sits on the alert path:
 * characterise it first and give it full review" (direction.md §4). So these tests assert
 * the *asymmetry* between the phone and the watch, not that they are equal -- the
 * asymmetries are mostly deliberate, and unifying them is a behaviour change that would
 * need a device, not a refactor.
 *
 * Source checks rather than behavioural ones, like HealthConnectNullSensorptrTests next
 * door: `handlealarm()` calls Natives, Notify, SensorBluetooth and LossOfSensorAlarm, none
 * of which a local JVM test can load or fake usefully. The decision logic itself (the
 * nexttime arithmetic and the `saidloss` latch) is identical in both and lives in
 * [SuperGlucoseAlarms] on the alarm path, so it is not restated here either.
 *
 * What this pins, phone vs watch:
 * - the watch recomputes the complication views, the phone does not;
 * - only the phone relays the loss alarm to WearInt, and only behind `doWearInt`;
 * - only the phone pushes the stale value to the home-screen widget;
 * - the phone's wake is conditional on `shouldwakesender()`, the watch's is unconditional;
 * - the watch asks for a sync afterwards, the phone does not;
 * - the phone calls `ensureCurrentSensorSelection()` on reconnect, the watch does not;
 * - and the one that looks like a bug rather than a decision: with no loss alarm the phone
 *   sends an `oldnotification` and the watch does nothing at all.
 */
class GlucoseAlarmsAsymmetryTests {
    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }

    private val phone: String
        get() = source("Common/src/mobile/java/tk/glucodata/MobileGlucoseAlarms.java")
    private val watch: String
        get() = source("Common/src/wear/java/tk/glucodata/WearGlucoseAlarms.java")

    @Test
    fun onlyTheWatchRecomputesTheComplicationViews() {
        // Spelled as the watch spells it today. This assertion encoded the old name
        // GlucoseValue and went red on rebase, because #469 renamed that class to
        // WearComplicationValue -- which is the property of source checks worth remembering:
        // they are only as stable as the names they quote.
        assertTrue(watch, watch.contains("ComplicationValue.updateall()"))
        assertFalse(phone, phone.contains("ComplicationValue.updateall()"))
        assertFalse(phone, phone.contains("GlucoseValue.updateall()"))
    }

    @Test
    fun onlyThePhoneRelaysTheLossAlarmToWearInt() {
        assertTrue(phone, phone.contains("WearInt.missingalarm("))
        assertTrue("the relay must stay behind doWearInt", phone.contains("doWearInt"))
        assertFalse(watch, watch.contains("WearInt.missingalarm("))
    }

    @Test
    fun onlyThePhonePushesTheStaleValueToTheWidget() {
        assertTrue(phone, phone.contains("GlucoseWidget.oldvalue("))
        assertFalse(watch, watch.contains("GlucoseWidget.oldvalue("))
    }

    @Test
    fun theWakeIsConditionalOnThePhoneAndUnconditionalOnTheWatch() {
        assertTrue("the phone must gate the wake", phone.contains("Natives.shouldwakesender()"))
        assertTrue(phone, phone.contains("if (shouldwake)"))
        assertTrue("the watch wakes unconditionally", watch.contains("MessageSender.sendwakestream()"))
        assertFalse(watch, watch.contains("shouldwakesender()"))
    }

    @Test
    fun onlyTheWatchAsksForASyncAfterwards() {
        assertTrue(watch, watch.contains("WearSync2.requestSync()"))
        assertFalse(phone, phone.contains("WearSync2.requestSync()"))
    }

    @Test
    fun onlyThePhoneReselectsTheCurrentSensorOnReconnect() {
        assertTrue(phone, phone.contains("ensureCurrentSensorSelection()"))
        assertFalse(watch, watch.contains("ensureCurrentSensorSelection()"))
    }

    /**
     * [after] must be present, or substringAfter hands back the whole file and the
     * assertion that follows would pass for the wrong reason.
     */
    private fun after(source: String, anchor: String): String {
        assertTrue("anchor not found: $anchor", source.contains(anchor))
        return source.substringAfter(anchor)
    }

    @Test
    fun withNoLossAlarmThePhoneNotifiesAndTheWatchDoesNothing() {
        // This is the one I would call a candidate bug rather than a decision. Both read
        // hasalarmloss() and both skip the loss branch when it is false, but the phone then
        // sends an oldnotification for the stale value and the watch falls out of the if.
        // Pinned so that closing it is a deliberate change with the review it deserves.
        val phoneNoLoss = after(phone, "if(!haslossalarm) {")
        assertTrue(
            "the phone is expected to notify about the stale value",
            phoneNoLoss.contains("Notify.onenot.oldnotification(wastime)"),
        )
        val watchNoLoss = after(watch, "if(hasalarmloss())")
        assertFalse(
            "today the watch does nothing when there is no loss alarm; if this now notifies, " +
                "the change was intended and this line should say so",
            watchNoLoss.contains("Notify.onenot.oldnotification(wastime)"),
        )
    }
}
