package tk.glucodata.ui.stats

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.R

/**
 * Which sections the statistics screen shows and in what order.
 *
 * Everyone reads this screen for something different — one person opens it for
 * overnight lows, the next for the A1c estimate — so the order is the user's, not
 * ours. The defaults below are only a starting arrangement.
 */
enum class StatsCard(@param:StringRes val titleResId: Int) {
    OVERVIEW(R.string.stats_card_overview),
    METRICS(R.string.stats_card_metrics),
    EPISODES(R.string.episodes_title),
    PATTERNS(R.string.stats_patterns),
    DAY_BY_DAY(R.string.stats_day_by_day),
    RISK_INDEX(R.string.gri_title),
    TEMPERATURE(R.string.stats_temperature_title),
    INSIGHTS(R.string.insights);

    companion object {
        /**
         * The risk index sits below the patterns rather than second from the top: it is
         * a summary of what the bands above already showed, not a headline.
         */
        val DEFAULT_ORDER = listOf(
            OVERVIEW,
            METRICS,
            PATTERNS,
            DAY_BY_DAY,
            EPISODES,
            RISK_INDEX,
            TEMPERATURE,
            INSIGHTS
        )
    }
}

/**
 * Individual numbers inside the metrics card. All of them are on by default and any of
 * them can be pinned to the dashboard; order, visibility and width are the user's.
 */
enum class StatsMetric(@param:StringRes val titleResId: Int) {
    AVERAGE(R.string.average_glucose),
    GMI(R.string.a1c_gmi_label),
    MEDIAN(R.string.median),
    IQR(R.string.report_iqr_short),
    STD_DEV(R.string.std_dev_short),
    CV(R.string.cv),
    GVI(R.string.gvi),
    PSG(R.string.psg),
    TIGHT_RANGE(R.string.stats_tight_range),
    DAWN_RISE(R.string.stats_metric_dawn),
    MAGE(R.string.stats_metric_mage),
    MODD(R.string.stats_metric_modd),
    STREAK(R.string.stats_metric_streak),
    TIME_IN_RANGE(R.string.tir),
    LOW_EPISODES(R.string.episodes_lows),
    HIGH_EPISODES(R.string.episodes_highs),
    LBGI(R.string.lbgi),
    HBGI(R.string.hbgi),
    COVERAGE(R.string.stats_card_coverage),
    GRI(R.string.gri_short);

    companion object {
        // Ordered in pairs that belong on the same row, and so that the ten shown by
        // default fill five rows exactly with nothing left over.
        val DEFAULT_ORDER = entries.toList()

        /**
         * Off by default because each of these already has a home elsewhere on the
         * screen: time in range is the ring, the episode counts are the Episodes card,
         * coverage is on the window line, and GRI/LBGI/HBGI are the risk card. They are
         * one tap away in Arrange for anyone who wants them in the grid as well.
         */
        val HIDDEN_BY_DEFAULT = setOf(
            TIME_IN_RANGE,
            LOW_EPISODES,
            HIGH_EPISODES,
            LBGI,
            HBGI,
            COVERAGE,
            GRI,
            MAGE,
            MODD,
            STREAK
        )

        /** Rows shown before the rest fold away behind the disclosure. */
        const val DEFAULT_VISIBLE_ROWS = 4

        /**
         * Pinned to the dashboard out of the box.
         *
         * Mean, variability and time in range are the trio every consensus report leads
         * with, and each answers a question the dashboard chart does not: where the level
         * sits, how steady it is, and how much of the window was on target.
         *
         * A1c (GMI) is deliberately not here. It is a regression fitted to mean glucose
         * over fourteen days or more; over the strip's default window it is the mean
         * wearing a percent sign, and it swings hour to hour in a way that reads as noise.
         * It is one tap away for anyone running the strip at 30 or 90 days, where it means
         * something.
         */
        val PINNED_BY_DEFAULT = listOf(TIME_IN_RANGE, AVERAGE, CV)
    }
}

data class StatsLayoutState(
    val cardOrder: List<StatsCard> = StatsCard.DEFAULT_ORDER,
    val hiddenCards: Set<StatsCard> = emptySet(),
    val metricOrder: List<StatsMetric> = StatsMetric.DEFAULT_ORDER,
    val hiddenMetrics: Set<StatsMetric> = StatsMetric.HIDDEN_BY_DEFAULT,
    val wideMetrics: Set<StatsMetric> = emptySet(),
    val dashboardMetrics: List<StatsMetric> = StatsMetric.PINNED_BY_DEFAULT
) {
    val visibleCards: List<StatsCard> get() = cardOrder.filterNot { it in hiddenCards }
    val visibleMetrics: List<StatsMetric> get() = metricOrder.filterNot { it in hiddenMetrics }
}

/**
 * Persists the layout in the shared preference file the rest of the app already uses.
 * Small enough that a single in-memory [StateFlow] plus a synchronous write is the
 * whole story; both the statistics screen and the dashboard read the same instance.
 */
object StatsLayoutStore {

    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_CARD_ORDER = "stats_layout_card_order"
    private const val KEY_CARD_HIDDEN = "stats_layout_card_hidden"
    private const val KEY_METRIC_ORDER = "stats_layout_metric_order"
    private const val KEY_METRIC_HIDDEN = "stats_layout_metric_hidden"
    private const val KEY_METRIC_WIDE = "stats_layout_metric_wide"
    private const val KEY_VERSION = "stats_layout_version"

    /**
     * Bumped whenever the default order changes meaningfully. A layout saved against an
     * older default is discarded rather than merged: merging left people with the old
     * pairing plus new metrics tacked on the end, which is worse than a clean default.
     */
    private const val LAYOUT_VERSION = 5
    private const val KEY_DASHBOARD = "stats_layout_dashboard_metrics"

    /** The dashboard starts with three metrics, but users can pin one more. */
    const val MAX_DASHBOARD_METRICS = 4

    private val _state = MutableStateFlow(StatsLayoutState())
    val state: StateFlow<StatsLayoutState> = _state.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    fun ensureLoaded(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = store
            _state.value = read(store)
        }
    }

    fun setCardOrder(order: List<StatsCard>) = update { current ->
        current.copy(cardOrder = order.distinct() + StatsCard.DEFAULT_ORDER.filterNot { it in order })
    }

    fun setCardHidden(card: StatsCard, hidden: Boolean) = update { current ->
        current.copy(
            hiddenCards = if (hidden) current.hiddenCards + card else current.hiddenCards - card
        )
    }

    fun setMetricOrder(order: List<StatsMetric>) = update { current ->
        current.copy(metricOrder = order.distinct() + StatsMetric.DEFAULT_ORDER.filterNot { it in order })
    }

    fun setMetricHidden(metric: StatsMetric, hidden: Boolean) = update { current ->
        current.copy(
            hiddenMetrics = if (hidden) current.hiddenMetrics + metric else current.hiddenMetrics - metric
        )
    }

    /** Wide metrics take a whole row; the packer fills around them. */
    fun setMetricWide(metric: StatsMetric, wide: Boolean) = update { current ->
        current.copy(
            wideMetrics = if (wide) current.wideMetrics + metric else current.wideMetrics - metric
        )
    }

    /** Returns false when the pin was rejected because the dashboard is already full. */
    fun setPinnedToDashboard(metric: StatsMetric, pinned: Boolean): Boolean {
        val current = _state.value
        if (pinned && current.dashboardMetrics.size >= MAX_DASHBOARD_METRICS &&
            metric !in current.dashboardMetrics
        ) {
            return false
        }
        update { state ->
            state.copy(
                dashboardMetrics = if (pinned) {
                    (state.dashboardMetrics + metric).distinct()
                } else {
                    state.dashboardMetrics - metric
                }
            )
        }
        return true
    }

    /** Whole pinned list at once, for reordering and for slot edits. */
    fun setDashboardMetrics(order: List<StatsMetric>) = update { current ->
        current.copy(dashboardMetrics = order.distinct().take(MAX_DASHBOARD_METRICS))
    }

    /** Replaces the metric in one slot, or drops the slot when [metric] is null. */
    fun replaceDashboardMetric(slot: Int, metric: StatsMetric?) = update { current ->
        val next = current.dashboardMetrics.toMutableList()
        if (slot !in next.indices) {
            if (metric != null && next.size < MAX_DASHBOARD_METRICS) next.add(metric)
        } else if (metric == null) {
            next.removeAt(slot)
        } else {
            // Pinning something already on the strip moves it here rather than
            // duplicating it.
            next.remove(metric)
            if (slot in next.indices) next[slot] = metric else next.add(metric)
        }
        current.copy(dashboardMetrics = next.distinct().take(MAX_DASHBOARD_METRICS))
    }

    fun resetLayout() = update { StatsLayoutState() }

    private inline fun update(transform: (StatsLayoutState) -> StatsLayoutState) {
        val next = transform(_state.value)
        _state.value = next
        prefs?.edit()
            ?.putString(KEY_CARD_ORDER, next.cardOrder.joinToString(",") { it.name })
            ?.putString(KEY_CARD_HIDDEN, next.hiddenCards.joinToString(",") { it.name })
            ?.putString(KEY_METRIC_ORDER, next.metricOrder.joinToString(",") { it.name })
            ?.putString(KEY_METRIC_HIDDEN, next.hiddenMetrics.joinToString(",") { it.name })
            ?.putString(KEY_METRIC_WIDE, next.wideMetrics.joinToString(",") { it.name })
            ?.putString(KEY_DASHBOARD, next.dashboardMetrics.joinToString(",") { it.name })
            ?.putInt(KEY_VERSION, LAYOUT_VERSION)
            ?.apply()
    }

    private fun read(store: SharedPreferences): StatsLayoutState {
        val defaults = StatsLayoutState()
        if (store.getInt(KEY_VERSION, 0) != LAYOUT_VERSION) {
            // Ordering resets, but what the user pinned to the dashboard is unrelated to
            // the default order and survives — losing it on every version bump was its
            // own small annoyance. Someone who had pinned nothing gets the new defaults,
            // since a version bump is exactly the moment the defaults changed.
            val stored = readPinned(store)
            return defaults.copy(
                dashboardMetrics = stored.takeIf { it.isNotEmpty() } ?: defaults.dashboardMetrics
            )
        }
        return StatsLayoutState(
            cardOrder = readOrder(store, KEY_CARD_ORDER, StatsCard.DEFAULT_ORDER) { StatsCard.valueOf(it) },
            hiddenCards = readSet(store, KEY_CARD_HIDDEN, defaults.hiddenCards) { StatsCard.valueOf(it) },
            metricOrder = readOrder(store, KEY_METRIC_ORDER, StatsMetric.DEFAULT_ORDER) { StatsMetric.valueOf(it) },
            hiddenMetrics = readSet(store, KEY_METRIC_HIDDEN, defaults.hiddenMetrics) { StatsMetric.valueOf(it) },
            wideMetrics = readSet(store, KEY_METRIC_WIDE, defaults.wideMetrics) { StatsMetric.valueOf(it) },
            // Unlike every other key, an empty value here is a real answer: it means the
            // user unpinned everything, and handing them the defaults back on the next
            // launch would be a bug rather than a kindness.
            dashboardMetrics = if (store.getString(KEY_DASHBOARD, null) == null) {
                defaults.dashboardMetrics
            } else {
                readPinned(store)
            }
        )
    }

    private fun readPinned(store: SharedPreferences): List<StatsMetric> =
        store.getString(KEY_DASHBOARD, null)
            .orEmpty()
            .split(',')
            .mapNotNull { name -> runCatching { StatsMetric.valueOf(name.trim()) }.getOrNull() }
            .distinct()
            .take(MAX_DASHBOARD_METRICS)

    /**
     * Stored order wins for everything it names; anything added to the enum since the
     * layout was saved is appended, so a new card appears instead of vanishing.
     */
    private fun <T : Enum<T>> readOrder(
        store: SharedPreferences,
        key: String,
        fallback: List<T>,
        parse: (String) -> T
    ): List<T> {
        val raw = store.getString(key, null) ?: return fallback
        val stored = raw.split(',').mapNotNull { name ->
            runCatching { parse(name.trim()) }.getOrNull()
        }
        if (stored.isEmpty()) return fallback
        return stored.distinct() + fallback.filterNot { it in stored }
    }

    private fun <T : Enum<T>> readSet(
        store: SharedPreferences,
        key: String,
        fallback: Set<T>,
        parse: (String) -> T
    ): Set<T> {
        val raw = store.getString(key, null) ?: return fallback
        return raw.split(',')
            .mapNotNull { name -> runCatching { parse(name.trim()) }.getOrNull() }
            .toSet()
    }
}

/**
 * Whether the statistics screen is in arrange mode.
 *
 * Held outside the screen so that every way out of a mode actually works: system back,
 * tapping Statistics in the navigation bar while already on it, and leaving the screen
 * altogether. As local state, none of those could reach it, and the only exit was a button
 * the user had to go looking for.
 */
object StatsArrangeMode {
    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    fun open() {
        _editing.value = true
    }

    /** True when this actually closed something, so a caller can stop handling the event. */
    fun close(): Boolean {
        if (!_editing.value) return false
        _editing.value = false
        return true
    }
}

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val mutable = toMutableList()
    mutable.add(to, mutable.removeAt(from))
    return mutable
}
