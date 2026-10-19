package tk.glucodata.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the SMS decision engine.
 *
 * Every case here is a scenario somebody could actually be in, because the cost
 * of a wrong answer is either a missed hypo or a phone ringing at a relative's
 * bedside for no reason.
 */
class SmsEscalationTests {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun contacts(vararg stages: Int): List<SmsContact> =
        stages.mapIndexed { index, stage ->
            SmsContact(number = "+100000000$index", label = "C$index", stage = stage)
        }

    private fun policy(vararg contacts: SmsContact) = SmsPolicy(
        contacts = contacts.toList(),
        relayMode = SmsPolicy.RELAY_OFF
    ).sanitized()

    private fun alarm(firedAtMs: Long, alertId: Int = 0, ackAtMs: Long = 0L) =
        ArmedAlarm(alertId = alertId, alertName = "Low", firedAtMs = firedAtMs, acknowledgedAtMs = ackAtMs)

    // ------------------------------------------------------- unacked alarms

    @Test
    fun anAlarmDismissedInTimeNeverTextsAnyone() {
        val policy = policy(*contacts(0).toTypedArray())
        val state = SmsWatchState(alarms = listOf(alarm(t0, ackAtMs = t0 + 2 * minute)))

        val plan = SmsEscalation.plan(policy, state, t0 + 30 * minute)

        assertTrue(plan.messages.isEmpty())
    }

    @Test
    fun anAlarmLeftUnansweredTextsTheFirstContactAfterTheConfiguredWait() {
        val policy = policy(*contacts(0).toTypedArray()).copy(unackedMinutes = 10)
        val state = SmsWatchState(alarms = listOf(alarm(t0)))

        val early = SmsEscalation.plan(policy, state, t0 + 9 * minute)
        assertTrue("must not fire before the wait elapses", early.messages.isEmpty())

        val due = SmsEscalation.plan(policy, state, t0 + 10 * minute)
        assertEquals(1, due.messages.size)
        assertEquals(SmsEventKind.ALARM_UNACKED, due.messages[0].kind)
        assertEquals(0, due.messages[0].stage)
        assertEquals(10, due.messages[0].elapsedMinutes)
    }

    @Test
    fun alertTypesOutsideTheSelectionDoNotEscalate() {
        val policy = policy(*contacts(0).toTypedArray())
            .copy(alarmAlertIds = setOf(5))
        val state = SmsWatchState(alarms = listOf(alarm(t0, alertId = 1)))

        val plan = SmsEscalation.plan(policy, state, t0 + 60 * minute)

        assertTrue(plan.messages.isEmpty())
    }

    @Test
    fun theBackupContactIsOnlyReachedAfterTheStageDelay() {
        val policy = policy(*contacts(0, 1).toTypedArray())
            .copy(unackedMinutes = 10, stageStepMinutes = 5)
        val progress = mapOf(
            SmsEscalation.EPISODE_ALARM_PREFIX + "0" to EpisodeProgress(
                openedAtMs = t0,
                sends = 1,
                lastSentAtMs = t0 + 10 * minute,
                maxStageNotified = 0
            )
        )
        val state = SmsWatchState(alarms = listOf(alarm(t0)), episodes = progress)

        assertTrue(SmsEscalation.plan(policy, state, t0 + 14 * minute).messages.isEmpty())

        val escalated = SmsEscalation.plan(policy, state, t0 + 15 * minute)
        assertEquals(1, escalated.messages.size)
        assertEquals(1, escalated.messages[0].stage)
    }

    @Test
    fun repeatsStopAtTheConfiguredCeiling() {
        val policy = policy(*contacts(0).toTypedArray())
            .copy(unackedMinutes = 10, repeatMinutes = 20, maxSendsPerEpisode = 2)
        val exhausted = mapOf(
            SmsEscalation.EPISODE_ALARM_PREFIX + "0" to EpisodeProgress(
                openedAtMs = t0,
                sends = 2,
                lastSentAtMs = t0 + 30 * minute,
                maxStageNotified = 0
            )
        )
        val state = SmsWatchState(alarms = listOf(alarm(t0)), episodes = exhausted)

        val plan = SmsEscalation.plan(policy, state, t0 + 5 * 60 * minute)

        assertTrue(plan.messages.isEmpty())
    }

    // ---------------------------------------------------------- all clear

    @Test
    fun resolvingAnEpisodeThatWasTextedSendsAnAllClearToEveryoneReached() {
        val policy = policy(*contacts(0, 1).toTypedArray())
        val previouslyPaged = mapOf(
            SmsEscalation.EPISODE_ALARM_PREFIX + "0" to EpisodeProgress(
                openedAtMs = t0,
                sends = 2,
                lastSentAtMs = t0 + 15 * minute,
                maxStageNotified = 1
            )
        )
        val state = SmsWatchState(alarms = emptyList(), episodes = previouslyPaged)

        val plan = SmsEscalation.plan(policy, state, t0 + 40 * minute)

        assertEquals(1, plan.messages.size)
        assertEquals(SmsEventKind.ALL_CLEAR, plan.messages[0].kind)
        assertEquals(2, plan.messages[0].contacts.size)
        assertTrue(plan.closedEpisodes.contains(SmsEscalation.EPISODE_ALARM_PREFIX + "0"))
    }

    @Test
    fun anEpisodeThatNeverTextedAnyoneResolvesSilently() {
        val policy = policy(*contacts(0).toTypedArray())
        val untouched = mapOf(
            SmsEscalation.EPISODE_ALARM_PREFIX + "0" to EpisodeProgress(openedAtMs = t0)
        )
        val state = SmsWatchState(alarms = emptyList(), episodes = untouched)

        val plan = SmsEscalation.plan(policy, state, t0 + 40 * minute)

        assertTrue(plan.messages.isEmpty())
    }

    // ------------------------------------------------------ critical values

    @Test
    fun aCriticalLowPagesEvenAfterTheAlarmWasDismissed() {
        val policy = policy(*contacts(0).toTypedArray())
            .copy(criticalGraceMinutes = 3, criticalLowMgdl = 55)
        val state = SmsWatchState(
            readingMgdl = 44,
            readingAtMs = t0,
            lastAlarmAckAtMs = t0 + minute,
            episodes = mapOf(SmsEscalation.EPISODE_CRITICAL_LOW to EpisodeProgress(openedAtMs = t0))
        )

        val plan = SmsEscalation.plan(policy, state, t0 + 3 * minute)

        assertEquals(1, plan.messages.size)
        assertEquals(SmsEventKind.CRITICAL, plan.messages[0].kind)
    }

    @Test
    fun criticalCanBeConfiguredToRespectAnAcknowledgement() {
        val policy = policy(*contacts(0).toTypedArray())
            .copy(criticalGraceMinutes = 3, criticalIgnoresAck = false)
        val state = SmsWatchState(
            readingMgdl = 44,
            readingAtMs = t0,
            lastAlarmAckAtMs = t0 + minute,
            episodes = mapOf(SmsEscalation.EPISODE_CRITICAL_LOW to EpisodeProgress(openedAtMs = t0))
        )

        val plan = SmsEscalation.plan(policy, state, t0 + 3 * minute)

        assertTrue(plan.messages.isEmpty())
    }

    @Test
    fun aStaleReadingNeverTriggersACriticalPage() {
        val policy = policy(*contacts(0).toTypedArray()).copy(criticalGraceMinutes = 0)
        val state = SmsWatchState(readingMgdl = 40, readingAtMs = t0)

        val plan = SmsEscalation.plan(policy, state, t0 + 40 * minute)

        assertFalse(plan.messages.any { it.kind == SmsEventKind.CRITICAL })
    }

    // ----------------------------------------------------------- no data

    @Test
    fun sensorSilencePagesOnceTheThresholdPasses() {
        val policy = policy(*contacts(0).toTypedArray())
            .copy(noDataMinutes = 45, criticalEnabled = false)
        val state = SmsWatchState(readingMgdl = 120, readingAtMs = t0)

        assertTrue(SmsEscalation.plan(policy, state, t0 + 44 * minute).messages.isEmpty())

        val plan = SmsEscalation.plan(policy, state, t0 + 45 * minute)
        assertEquals(1, plan.messages.size)
        assertEquals(SmsEventKind.NO_DATA, plan.messages[0].kind)
    }

    // -------------------------------------------------------------- relay

    @Test
    fun theOfflineRelayStaysQuietWhileTheDataPathWorks() {
        val relayContact = SmsContact(number = "+15551234", stage = 0, relay = true)
        val policy = SmsPolicy(
            contacts = listOf(relayContact),
            relayMode = SmsPolicy.RELAY_WHEN_OFFLINE,
            alarmEscalationEnabled = false,
            criticalEnabled = false,
            noDataEnabled = false
        ).sanitized()
        val state = SmsWatchState(readingMgdl = 120, readingAtMs = t0, dataPathDownSinceMs = 0L)

        val plan = SmsEscalation.plan(policy, state, t0 + 4 * 60 * minute)

        assertTrue(plan.messages.isEmpty())
    }

    @Test
    fun theOfflineRelayStartsAfterTheGraceAndOnlyReachesRelayContacts() {
        val policy = SmsPolicy(
            contacts = listOf(
                SmsContact(number = "+15551234", stage = 0, relay = true),
                SmsContact(number = "+15559999", stage = 0, relay = false)
            ),
            relayMode = SmsPolicy.RELAY_WHEN_OFFLINE,
            relayOfflineGraceMinutes = 10,
            alarmEscalationEnabled = false,
            criticalEnabled = false,
            noDataEnabled = false
        ).sanitized()
        val state = SmsWatchState(
            readingMgdl = 120,
            readingAtMs = t0 + 9 * minute,
            dataPathDownSinceMs = t0
        )

        assertTrue(SmsEscalation.plan(policy, state, t0 + 9 * minute).messages.isEmpty())

        val plan = SmsEscalation.plan(policy, state, t0 + 10 * minute)
        assertEquals(1, plan.messages.size)
        assertEquals(SmsEventKind.RELAY, plan.messages[0].kind)
        assertEquals(listOf("+15551234"), plan.messages[0].contacts.map { it.number })
    }

    // ------------------------------------------------------------- guards

    @Test
    fun escalateOnlyWhenOfflineSuppressesEverythingWhileTheNetworkIsUp() {
        val policy = policy(*contacts(0).toTypedArray())
            .copy(escalateOnlyWhenOffline = true, unackedMinutes = 1)
        val state = SmsWatchState(alarms = listOf(alarm(t0)), dataPathDownSinceMs = 0L)

        assertTrue(SmsEscalation.plan(policy, state, t0 + 60 * minute).messages.isEmpty())

        val offline = state.copy(dataPathDownSinceMs = t0)
        assertEquals(1, SmsEscalation.plan(policy, offline, t0 + 60 * minute).messages.size)
    }

    @Test
    fun theNetworkComingBackIsNeverMistakenForTheAlarmBeingOver() {
        val policy = policy(*contacts(0).toTypedArray())
            .copy(escalateOnlyWhenOffline = true, unackedMinutes = 1)
        val paged = mapOf(
            SmsEscalation.EPISODE_ALARM_PREFIX + "0" to EpisodeProgress(
                openedAtMs = t0,
                sends = 1,
                lastSentAtMs = t0 + minute,
                maxStageNotified = 0
            )
        )
        // Texted while offline; the connection is back but the alarm still stands.
        val state = SmsWatchState(
            alarms = listOf(alarm(t0)),
            episodes = paged,
            dataPathDownSinceMs = 0L
        )

        val plan = SmsEscalation.plan(policy, state, t0 + 30 * minute)

        assertTrue("no all-clear while the alarm is still unanswered", plan.messages.isEmpty())
        assertTrue(plan.closedEpisodes.isEmpty())
        assertTrue(plan.activeEpisodes.containsKey(SmsEscalation.EPISODE_ALARM_PREFIX + "0"))
    }

    @Test
    fun theHourlyCapTrimsThePlanRatherThanBlowingTheBudget() {
        val policy = policy(*contacts(0, 0, 0).toTypedArray())
            .copy(unackedMinutes = 1, maxPerHour = 2)
        val state = SmsWatchState(alarms = listOf(alarm(t0)), sentInLastHour = 1)

        val plan = SmsEscalation.plan(policy, state, t0 + 5 * minute)

        assertEquals(1, plan.messages.sumOf { it.contacts.size })
    }

    @Test
    fun anExhaustedDailyCapSendsNothingAtAll() {
        val policy = policy(*contacts(0).toTypedArray())
            .copy(unackedMinutes = 1, maxPerDay = 20)
        val state = SmsWatchState(alarms = listOf(alarm(t0)), sentInLastDay = 20)

        assertTrue(SmsEscalation.plan(policy, state, t0 + 5 * minute).messages.isEmpty())
    }

    @Test
    fun theMostUrgentBatchWinsTheLastRemainingMessage() {
        val policy = policy(*contacts(0).toTypedArray()).copy(
            unackedMinutes = 1,
            criticalGraceMinutes = 0,
            noDataEnabled = false,
            maxPerHour = 1
        )
        val state = SmsWatchState(
            alarms = listOf(alarm(t0)),
            readingMgdl = 40,
            readingAtMs = t0
        )

        val plan = SmsEscalation.plan(policy, state, t0 + 5 * minute)

        assertEquals(1, plan.messages.size)
        assertEquals(SmsEventKind.CRITICAL, plan.messages[0].kind)
    }

    @Test
    fun aDestinationWithoutContactsPlansNothingAndClosesEverything() {
        val policy = SmsPolicy().sanitized()
        val state = SmsWatchState(
            alarms = listOf(alarm(t0)),
            episodes = mapOf(SmsEscalation.EPISODE_NO_DATA to EpisodeProgress(openedAtMs = t0))
        )

        val plan = SmsEscalation.plan(policy, state, t0 + 60 * minute)

        assertTrue(plan.messages.isEmpty())
        assertTrue(plan.closedEpisodes.contains(SmsEscalation.EPISODE_NO_DATA))
    }

    @Test
    fun theNextCheckIsScheduledForTheMomentTheFirstStageFallsDue() {
        val policy = policy(*contacts(0).toTypedArray()).copy(unackedMinutes = 10)
        val state = SmsWatchState(alarms = listOf(alarm(t0)))

        val plan = SmsEscalation.plan(policy, state, t0)

        assertTrue(plan.nextCheckDelayMs <= SmsEscalation.MAX_TICK_MS)
        assertTrue(plan.nextCheckDelayMs >= SmsEscalation.MIN_TICK_MS)
    }
}
