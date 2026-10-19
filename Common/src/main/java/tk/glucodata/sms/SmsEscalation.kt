package tk.glucodata.sms

import androidx.annotation.Keep

/** What a planned SMS is about. Ordered most to least urgent. */
@Keep
enum class SmsEventKind {
    CRITICAL,
    ALARM_UNACKED,
    NO_DATA,
    ALL_CLEAR,
    RELAY
}

/** An alert that fired on the phone and has not been dealt with yet. */
@Keep
data class ArmedAlarm(
    val alertId: Int,
    val alertName: String,
    val firedAtMs: Long,
    val acknowledgedAtMs: Long = 0L
)

/** How far an episode has already escalated. */
@Keep
data class EpisodeProgress(
    val openedAtMs: Long,
    val sends: Int = 0,
    val lastSentAtMs: Long = 0L,
    val maxStageNotified: Int = -1
)

/** Everything [SmsEscalation.plan] needs; assembled by [SmsWatchdog]. */
@Keep
data class SmsWatchState(
    val alarms: List<ArmedAlarm> = emptyList(),
    val readingMgdl: Int = 0,
    val readingAtMs: Long = 0L,
    /** When the outbound data path went down, or 0 while it is up. */
    val dataPathDownSinceMs: Long = 0L,
    val episodes: Map<String, EpisodeProgress> = emptyMap(),
    val lastRelayAtMs: Long = 0L,
    val lastAlarmAckAtMs: Long = 0L,
    val sentInLastHour: Int = 0,
    val sentInLastDay: Int = 0
)

/** One batch of identical texts to one escalation stage. */
@Keep
data class SmsMessagePlan(
    val episodeKey: String,
    val kind: SmsEventKind,
    val stage: Int,
    val contacts: List<SmsContact>,
    val alertName: String = "",
    val elapsedMinutes: Int = 0
)

@Keep
data class SmsPlan(
    val messages: List<SmsMessagePlan> = emptyList(),
    /** Conditions currently active, with the instant each began. Persisted by the caller. */
    val activeEpisodes: Map<String, Long> = emptyMap(),
    /** Episodes that were open on the previous tick and have now resolved. */
    val closedEpisodes: Set<String> = emptySet(),
    val nextCheckDelayMs: Long = SmsEscalation.MAX_TICK_MS
)

/**
 * The decision engine behind an SMS destination.
 *
 * Deliberately pure: no Android, no clock, no I/O. [SmsWatchdog] gathers the
 * state, calls [plan], sends what comes back and persists the returned episode
 * bookkeeping. Everything that decides whether a person's phone buzzes at 3 a.m.
 * is therefore reachable from unit tests.
 */
@Keep
object SmsEscalation {
    const val MIN_TICK_MS = 30_000L
    const val MAX_TICK_MS = 5 * 60_000L

    /** A critical value is only worth paging about if the reading behind it is recent. */
    const val CRITICAL_READING_MAX_AGE_MS = 15 * 60_000L

    const val EPISODE_NO_DATA = "nodata"
    const val EPISODE_CRITICAL_LOW = "critical:low"
    const val EPISODE_CRITICAL_HIGH = "critical:high"
    const val EPISODE_ALARM_PREFIX = "alarm:"

    private data class Condition(
        val key: String,
        val kind: SmsEventKind,
        val sinceMs: Long,
        val firstDelayMs: Long,
        val alertName: String = ""
    )

    fun plan(policy: SmsPolicy, state: SmsWatchState, nowMs: Long): SmsPlan {
        if (!policy.hasUsableContacts()) {
            return SmsPlan(closedEpisodes = state.episodes.keys, nextCheckDelayMs = MAX_TICK_MS)
        }

        val dataPathDown = state.dataPathDownSinceMs > 0L
        val conditions = activeConditions(policy, state, nowMs)
        // The offline guard silences escalation, but the situation itself is still
        // happening — the episodes stay open so the network coming back can never
        // be mistaken for the hypo being over.
        val escalationSilenced = policy.escalateOnlyWhenOffline && !dataPathDown

        val active = conditions.associate { it.key to it.sinceMs }
        val closed = state.episodes.keys - active.keys

        val dueTimes = ArrayList<Long>()
        val batches = ArrayList<SmsMessagePlan>()

        if (!escalationSilenced) {
            conditions.forEach { condition ->
                val progress = state.episodes[condition.key]
                    ?: EpisodeProgress(openedAtMs = condition.sinceMs)
                val outcome = planForCondition(policy, condition, progress, nowMs)
                outcome.message?.let { batches += it }
                outcome.nextDueAtMs?.let { dueTimes += it }
            }
        }

        // An episode that resolved after we paged somebody deserves a follow-up, so
        // the people who were woken up know they can go back to sleep.
        if (policy.allClearEnabled) {
            closed.forEach { key ->
                val progress = state.episodes[key] ?: return@forEach
                if (progress.sends <= 0 || progress.maxStageNotified < 0) return@forEach
                val contacts = (0..progress.maxStageNotified)
                    .flatMap { policy.contactsForStage(it) }
                if (contacts.isEmpty()) return@forEach
                batches += SmsMessagePlan(
                    episodeKey = key,
                    kind = SmsEventKind.ALL_CLEAR,
                    stage = progress.maxStageNotified,
                    contacts = contacts,
                    elapsedMinutes = elapsedMinutes(progress.openedAtMs, nowMs)
                )
            }
        }

        val relay = planRelay(policy, state, nowMs, suppressed = batches.isNotEmpty())
        relay.message?.let { batches += it }
        relay.nextDueAtMs?.let { dueTimes += it }

        val budgeted = applyBudget(policy, state, batches)
        val nextDelay = dueTimes
            .map { (it - nowMs).coerceAtLeast(MIN_TICK_MS) }
            .minOrNull()
            ?.coerceAtMost(MAX_TICK_MS)
            ?: MAX_TICK_MS

        return SmsPlan(
            messages = budgeted,
            activeEpisodes = active,
            closedEpisodes = closed,
            nextCheckDelayMs = nextDelay
        )
    }

    private fun activeConditions(
        policy: SmsPolicy,
        state: SmsWatchState,
        nowMs: Long
    ): List<Condition> {
        val out = ArrayList<Condition>(4)

        if (policy.alarmEscalationEnabled) {
            state.alarms.forEach { alarm ->
                if (alarm.acknowledgedAtMs > 0L) return@forEach
                if (policy.alarmAlertIds.isNotEmpty() && alarm.alertId !in policy.alarmAlertIds) {
                    return@forEach
                }
                out += Condition(
                    key = EPISODE_ALARM_PREFIX + alarm.alertId,
                    kind = SmsEventKind.ALARM_UNACKED,
                    sinceMs = alarm.firedAtMs,
                    firstDelayMs = policy.unackedMinutes * 60_000L,
                    alertName = alarm.alertName
                )
            }
        }

        if (policy.criticalEnabled && state.readingMgdl > 0 && state.readingAtMs > 0L &&
            nowMs - state.readingAtMs <= CRITICAL_READING_MAX_AGE_MS
        ) {
            val key = when {
                state.readingMgdl <= policy.criticalLowMgdl -> EPISODE_CRITICAL_LOW
                state.readingMgdl >= policy.criticalHighMgdl -> EPISODE_CRITICAL_HIGH
                else -> null
            }
            if (key != null) {
                val since = state.episodes[key]?.openedAtMs ?: nowMs
                val engagedSince = state.lastAlarmAckAtMs >= since
                if (policy.criticalIgnoresAck || !engagedSince) {
                    out += Condition(
                        key = key,
                        kind = SmsEventKind.CRITICAL,
                        sinceMs = since,
                        firstDelayMs = policy.criticalGraceMinutes * 60_000L
                    )
                }
            }
        }

        if (policy.noDataEnabled && state.readingAtMs > 0L) {
            val silentSince = state.readingAtMs + policy.noDataMinutes * 60_000L
            if (nowMs >= silentSince) {
                out += Condition(
                    key = EPISODE_NO_DATA,
                    kind = SmsEventKind.NO_DATA,
                    sinceMs = silentSince,
                    firstDelayMs = 0L
                )
            }
        }

        return out
    }

    private data class ConditionOutcome(
        val message: SmsMessagePlan?,
        val nextDueAtMs: Long?
    )

    private fun planForCondition(
        policy: SmsPolicy,
        condition: Condition,
        progress: EpisodeProgress,
        nowMs: Long
    ): ConditionOutcome {
        val lastStage = policy.lastStage()
        if (lastStage < 0) return ConditionOutcome(null, null)

        val stageStepMs = policy.stageStepMinutes * 60_000L
        val nextStage = progress.maxStageNotified + 1

        // A stage advance always wins over a repeat: reaching further beats
        // re-texting the people who already know.
        if (nextStage <= lastStage) {
            val dueAtMs = condition.sinceMs + condition.firstDelayMs + nextStage * stageStepMs
            if (nowMs >= dueAtMs) {
                val contacts = policy.contactsForStage(nextStage)
                return if (contacts.isEmpty()) {
                    // Empty rung: skip it without consuming a send.
                    planForCondition(
                        policy = policy,
                        condition = condition,
                        progress = progress.copy(maxStageNotified = nextStage),
                        nowMs = nowMs
                    )
                } else {
                    ConditionOutcome(
                        message = SmsMessagePlan(
                            episodeKey = condition.key,
                            kind = condition.kind,
                            stage = nextStage,
                            contacts = contacts,
                            alertName = condition.alertName,
                            elapsedMinutes = elapsedMinutes(condition.sinceMs, nowMs)
                        ),
                        nextDueAtMs = null
                    )
                }
            }
            return ConditionOutcome(null, dueAtMs)
        }

        if (policy.repeatMinutes <= 0 || progress.sends >= policy.maxSendsPerEpisode) {
            return ConditionOutcome(null, null)
        }
        val repeatAtMs = progress.lastSentAtMs + policy.repeatMinutes * 60_000L
        if (nowMs < repeatAtMs) {
            return ConditionOutcome(null, repeatAtMs)
        }
        val contacts = (0..progress.maxStageNotified).flatMap { policy.contactsForStage(it) }
        if (contacts.isEmpty()) return ConditionOutcome(null, null)
        return ConditionOutcome(
            message = SmsMessagePlan(
                episodeKey = condition.key,
                kind = condition.kind,
                stage = progress.maxStageNotified,
                contacts = contacts,
                alertName = condition.alertName,
                elapsedMinutes = elapsedMinutes(condition.sinceMs, nowMs)
            ),
            nextDueAtMs = null
        )
    }

    private fun planRelay(
        policy: SmsPolicy,
        state: SmsWatchState,
        nowMs: Long,
        suppressed: Boolean
    ): ConditionOutcome {
        val mode = policy.normalizedRelayMode()
        if (mode == SmsPolicy.RELAY_OFF) return ConditionOutcome(null, null)
        val contacts = policy.relayContacts()
        if (contacts.isEmpty()) return ConditionOutcome(null, null)
        if (state.readingMgdl <= 0 || state.readingAtMs <= 0L) return ConditionOutcome(null, null)

        val eligibleSinceMs = if (mode == SmsPolicy.RELAY_WHEN_OFFLINE) {
            val downSince = state.dataPathDownSinceMs
            if (downSince <= 0L) return ConditionOutcome(null, null)
            downSince + policy.relayOfflineGraceMinutes * 60_000L
        } else {
            0L
        }

        val intervalMs = policy.relayIntervalMinutes * 60_000L
        val dueAtMs = maxOf(eligibleSinceMs, state.lastRelayAtMs + intervalMs)
        if (nowMs < dueAtMs) return ConditionOutcome(null, dueAtMs)
        // A safety-net text going out right now already carries the current value.
        if (suppressed) return ConditionOutcome(null, nowMs + intervalMs)

        return ConditionOutcome(
            message = SmsMessagePlan(
                episodeKey = "relay",
                kind = SmsEventKind.RELAY,
                stage = 0,
                contacts = contacts,
                elapsedMinutes = elapsedMinutes(state.readingAtMs, nowMs)
            ),
            nextDueAtMs = null
        )
    }

    /**
     * Trims the plan to what the hourly and daily caps still allow, dropping the
     * least urgent batches first and then trimming contacts off the end of the
     * last batch that fits.
     */
    private fun applyBudget(
        policy: SmsPolicy,
        state: SmsWatchState,
        batches: List<SmsMessagePlan>
    ): List<SmsMessagePlan> {
        var remaining = minOf(
            policy.maxPerHour - state.sentInLastHour,
            policy.maxPerDay - state.sentInLastDay
        )
        if (remaining <= 0) return emptyList()

        val out = ArrayList<SmsMessagePlan>(batches.size)
        batches.sortedBy { it.kind.ordinal }.forEach { batch ->
            if (remaining <= 0) return@forEach
            val contacts = batch.contacts.take(remaining)
            if (contacts.isEmpty()) return@forEach
            remaining -= contacts.size
            out += batch.copy(contacts = contacts)
        }
        return out
    }

    private fun elapsedMinutes(sinceMs: Long, nowMs: Long): Int =
        ((nowMs - sinceMs).coerceAtLeast(0L) / 60_000L).toInt()
}
