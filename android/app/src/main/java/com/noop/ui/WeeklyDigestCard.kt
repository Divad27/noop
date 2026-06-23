package com.noop.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.noop.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.BalanceRead
import com.noop.analytics.RestScorer
import com.noop.analytics.WeeklyDigest
import com.noop.analytics.WeeklyDigestEngine
import com.noop.analytics.WeeklyMetric
import com.noop.analytics.WeeklyMetricSummary
import com.noop.data.DailyMetric
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Weekly Digest (#208)
//
// A deterministic, offline "week in review". Kotlin parity for the macOS/iOS
// WeeklyDigestView. Reads the merged daily history from the view model, pulls each
// tracked metric into a "yyyy-MM-dd"→value map, and feeds the pure
// WeeklyDigestEngine to produce a Monday-anchored summary: per-metric this-week
// mean + week-over-week delta + vs-baseline, the biggest movers, a strain-vs-recovery
// balance read, and 1–2 plain-English focal points. No AI, no network.
//
// Two surfaces are exposed so navigation can wire whichever it wants:
//   • WeeklyDigestCard  — an embeddable card (drop into Today / Trends).
//   • WeeklyDigestScreen — a full ScreenScaffold screen (for a nav destination).
// Both share WeeklyDigestContent so they never drift. Framing is informational
// (non-clinical), consistent with the app disclaimer.

/**
 * Build the weekly digest for the week containing today's logical local day from a
 * [DailyMetric] history. Extracts each metric into a day→value map and hands it to the
 * pure engine.
 */
fun buildWeeklyDigest(
    days: List<DailyMetric>,
    anchorDay: String = logicalDayKeyNow(),
): WeeklyDigest {
    val charge = HashMap<String, Double>()
    val effort = HashMap<String, Double>()
    val rest = HashMap<String, Double>()
    val rhr = HashMap<String, Double>()
    val hrv = HashMap<String, Double>()
    for (d in days) {
        d.recovery?.let { charge[d.day] = it }
        d.strain?.let { effort[d.day] = it }
        // Rest = the sleep-performance composite recomputed on the persisted day.
        RestScorer.restFromDaily(d)?.let { rest[d.day] = it }
        d.restingHr?.let { rhr[d.day] = it.toDouble() }
        d.avgHrv?.let { hrv[d.day] = it }
    }
    return WeeklyDigestEngine.build(
        byMetric = mapOf(
            WeeklyMetric.CHARGE to charge,
            WeeklyMetric.EFFORT to effort,
            WeeklyMetric.REST to rest,
            WeeklyMetric.RHR to rhr,
            WeeklyMetric.HRV to hrv,
        ),
        anchorDay = anchorDay,
    )
}

// MARK: - Embeddable card

/**
 * The weekly digest as a single card (for Today / Trends). Renders nothing when there's
 * no data this week, so it's safe to always place.
 */
@Composable
fun WeeklyDigestCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val digest = buildWeeklyDigest(days)
    if (digest.isEmpty) return
    NoopCard(modifier = modifier) {
        WeeklyDigestContent(digest = digest, compact = true)
    }
}

// MARK: - Full screen

/** The weekly digest as a full screen (for a nav destination). */
@Composable
fun WeeklyDigestScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    ScreenScaffold(
        title = stringResource(R.string.digest_screen_title),
        subtitle = stringResource(R.string.digest_screen_subtitle),
    ) {
        val digest = buildWeeklyDigest(days)
        if (digest.isEmpty) {
            DataPendingNote(
                title = stringResource(R.string.digest_empty_title),
                body = stringResource(R.string.digest_empty_body),
            )
        } else {
            NoopCard { WeeklyDigestContent(digest = digest, compact = false) }
        }
    }
}

// MARK: - Shared content

private val MONTHS: Array<String> =
    java.text.DateFormatSymbols.getInstance(java.util.Locale.getDefault()).shortMonths

private val DISPLAY_ORDER = listOf(
    WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST, WeeklyMetric.HRV, WeeklyMetric.RHR,
)

/**
 * The inner content shared by the card and the full screen. [compact] trims the metric
 * grid to the headline rows for the card; the full screen shows everything plus a footer.
 */
@Composable
fun WeeklyDigestContent(digest: WeeklyDigest, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Header.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline(stringResource(R.string.digest_overline))
                Text(weekRangeLabel(digest), style = NoopType.title2, color = Palette.textPrimary)
            }
            val daysA11y = stringResource(R.string.digest_days_with_data_a11y, digest.daysWithData)
            Text(
                stringResource(R.string.digest_days_with_data, digest.daysWithData),
                style = NoopType.footnote,
                color = Palette.textSecondary,
                modifier = Modifier.semantics {
                    contentDescription = daysA11y
                },
            )
        }

        // Focal points — the plain-English read, most salient first. Re-derived in the UI
        // locale (the engine emits already-built English sentences we can't translate opaquely).
        val focals = localizedFocalPoints(digest)
        if (focals.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                focals.forEach { FocalRow(it) }
            }
        }

        HorizontalDivider(color = Palette.hairline)

        // Per-metric rows.
        val rows = (if (compact) listOf(WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST)
        else DISPLAY_ORDER).mapNotNull { digest.summary(it) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { MetricRow(it) }
        }

        if (!compact) {
            HorizontalDivider(color = Palette.hairline)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                digest.sleepConsistencySD?.let { sd ->
                    Text(
                        stringResource(R.string.digest_sleep_steadiness, fmt1(sd)),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Text(balanceSentence(digest.balance), style = NoopType.footnote, color = Palette.textTertiary)
                Text(
                    stringResource(R.string.digest_disclaimer),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun FocalRow(line: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = line },
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Palette.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(line, style = NoopType.subhead, color = Palette.textPrimary)
    }
}

@Composable
private fun MetricRow(s: WeeklyMetricSummary) {
    val rowA11y = rowAccessibility(s)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = rowA11y },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            metricLabel(s.metric),
            style = NoopType.subhead,
            color = Palette.textSecondary,
            modifier = Modifier.width(92.dp),
        )
        Text(
            meanText(s),
            style = NoopType.bodyNumber,
            color = Palette.textPrimary,
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.weight(1f))
        DeltaChip(s)
    }
}

@Composable
private fun DeltaChip(s: WeeklyMetricSummary) {
    val tone = chipTone(s)
    val arrow: ImageVector = when {
        s.wowDelta > 0 -> Icons.Filled.ArrowUpward
        s.wowDelta < 0 -> Icons.Filled.ArrowDownward
        else -> Icons.Filled.Remove
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(Metrics.cornerPill))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clearAndSetSemantics { },
    ) {
        Icon(arrow, contentDescription = null, tint = tone, modifier = Modifier.size(10.dp))
        Text(deltaText(s), style = NoopType.captionNumber, color = tone)
    }
}

// MARK: - Formatting

private fun weekRangeLabel(digest: WeeklyDigest): String =
    "${shortDate(digest.weekStart)} – ${shortDate(digest.weekEnd)}"

/** "Jun 8" from "2026-06-08", via the engine's own pure parse (no Calendar). */
private fun shortDate(ymd: String): String {
    val p = WeeklyDigestEngine.parseYMD(ymd) ?: return ymd
    val name = if (p[1] in 1..12) MONTHS[p[1] - 1] else p[1].toString()
    return "$name ${p[2]}"
}

private fun meanText(s: WeeklyMetricSummary): String {
    if (s.thisWeek.n == 0) return "—"
    val v = s.thisWeek.mean.roundToInt()
    return if (s.metric.unit.isEmpty()) "$v" else "$v ${s.metric.unit}"
}

@Composable
private fun deltaText(s: WeeklyMetricSummary): String {
    if (s.weekOverWeek.current.n == 0 || s.weekOverWeek.previous.n == 0) {
        return stringResource(R.string.digest_delta_new)
    }
    val pct = s.weekOverWeek.pctChange
    return if (pct != null && abs(pct) >= 1) "${abs(pct).roundToInt()}%" else fmt1(abs(s.wowDelta))
}

/**
 * Tone: good moves green, bad moves rose, flat/uncomparable grey — folding in each
 * metric's higherIsBetter (so a Resting-HR rise reads as a warning).
 */
private fun chipTone(s: WeeklyMetricSummary): Color = when (s.wowGoodness) {
    1 -> Palette.statusPositive
    -1 -> Palette.statusCritical
    else -> Palette.textTertiary
}

@Composable
private fun rowAccessibility(s: WeeklyMetricSummary): String {
    val label = metricLabel(s.metric)
    val mean = meanText(s)
    if (s.weekOverWeek.current.n == 0 || s.weekOverWeek.previous.n == 0) {
        return stringResource(R.string.digest_row_a11y_no_comparison, label, mean)
    }
    val magnitude = deltaText(s)
    val base = when {
        s.wowDelta > 0 -> stringResource(R.string.digest_row_a11y_up, label, mean, magnitude)
        s.wowDelta < 0 -> stringResource(R.string.digest_row_a11y_down, label, mean, magnitude)
        else -> stringResource(R.string.digest_row_a11y_unchanged, label, mean)
    }
    val frame = when (s.wowGoodness) {
        1 -> ", " + stringResource(R.string.digest_frame_a11y_good)
        -1 -> ", " + stringResource(R.string.digest_frame_a11y_bad)
        else -> ""
    }
    return "$base$frame."
}

private fun fmt1(x: Double): String {
    val s = ((x * 10).roundToInt() / 10.0).toString()
    val sep = java.text.DecimalFormatSymbols.getInstance(java.util.Locale.getDefault()).decimalSeparator
    return if (sep == '.') s else s.replace('.', sep)
}

// MARK: - Localized resolvers
//
// The engine (analytics/WeeklyDigest.kt) is a canonical en-US oracle: its enum labels,
// BalanceRead.sentence and focalPoints() emit English. We re-resolve those at the render
// site against the UI locale so the digest matches the German domain terms used elsewhere.

/** Maps a metric to its UI-locale domain label (the same terms the Today screen uses). */
@Composable
private fun metricLabel(metric: WeeklyMetric): String = stringResource(
    when (metric) {
        WeeklyMetric.CHARGE -> R.string.today_charge
        WeeklyMetric.EFFORT -> R.string.today_effort
        WeeklyMetric.REST -> R.string.today_rest
        WeeklyMetric.RHR -> R.string.today_resting_hr
        WeeklyMetric.HRV -> R.string.today_hrv
    },
)

/** Maps a balance read to its UI-locale sentence (mirrors BalanceRead.sentence). */
@Composable
private fun balanceSentence(balance: BalanceRead): String = stringResource(
    when (balance) {
        BalanceRead.OVERREACHING -> R.string.digest_balance_overreaching
        BalanceRead.BALANCED -> R.string.digest_balance_balanced
        BalanceRead.UNDERLOADED -> R.string.digest_balance_underloaded
        BalanceRead.INSUFFICIENT -> R.string.digest_balance_insufficient
    },
)

private fun round1(x: Double): Double = (x * 10).roundToInt() / 10.0

/** Format a 1-decimal value with the UI locale's decimal separator (e.g. German comma). */
private fun localizedDecimal(x: Double): String {
    val v = round1(x)
    val sep = java.text.DecimalFormatSymbols.getInstance(java.util.Locale.getDefault()).decimalSeparator
    return v.toString().replace('.', sep)
}

/**
 * Re-derives the engine's focalPoints() selection in the UI locale. Mirrors
 * WeeklyDigestEngine.focalPoints(): same mover filter/sort, same balance/second-mover/
 * fallback ladder, capped to two lines — but emits German via the string templates.
 */
@Composable
private fun localizedFocalPoints(digest: WeeklyDigest): List<String> {
    val movers = digest.metrics
        .filter {
            it.weekOverWeek.current.n >= WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS &&
                it.weekOverWeek.previous.n >= WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS &&
                abs(it.normalisedMove) >= WeeklyDigestEngine.FOCUS_THRESHOLD
        }
        .sortedByDescending { abs(it.normalisedMove) }

    val lines = mutableListOf<String>()

    movers.firstOrNull()?.let { lines.add(moverSentenceDe(it)) }

    if (digest.balance == BalanceRead.OVERREACHING || digest.balance == BalanceRead.UNDERLOADED) {
        lines.add(balanceSentence(digest.balance))
    } else if (movers.size >= 2) {
        lines.add(moverSentenceDe(movers[1]))
    }

    if (lines.isEmpty()) {
        val currentDays = digest.metrics.maxOfOrNull { it.weekOverWeek.current.n } ?: 0
        if (currentDays in 1 until WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS) {
            lines.add(pluralStringResource(R.plurals.digest_focal_too_early, currentDays, currentDays))
        } else {
            val sd = digest.sleepConsistencySD
            if (sd != null && sd <= 6.0) {
                lines.add(stringResource(R.string.digest_focal_steady_even, fmt1(sd)))
            } else {
                lines.add(stringResource(R.string.digest_focal_steady_plain))
            }
        }
    }

    return lines.take(2)
}

/** One mover as a UI-locale sentence with good/bad framing. Mirrors engine moverSentence(). */
@Composable
private fun moverSentenceDe(s: WeeklyMetricSummary): String {
    val pct = s.weekOverWeek.pctChange
    val magnitude = if (pct != null && abs(pct) >= 1) {
        "${abs(pct).roundToInt()}%"
    } else {
        val suffix = if (s.metric.unit.isEmpty()) {
            " " + stringResource(R.string.digest_unit_pts)
        } else {
            " ${s.metric.unit}"
        }
        "${localizedDecimal(abs(s.wowDelta))}$suffix"
    }
    val thisAvg = s.thisWeek.mean.roundToInt()
    val lastAvg = s.weekOverWeek.previous.mean.roundToInt()
    val label = metricLabel(s.metric)
    val base = when {
        s.wowDelta > 0 -> stringResource(R.string.digest_focal_mover_up, label, magnitude, thisAvg, lastAvg)
        s.wowDelta < 0 -> stringResource(R.string.digest_focal_mover_down, label, magnitude, thisAvg, lastAvg)
        else -> stringResource(R.string.digest_focal_mover_flat, label, thisAvg, lastAvg)
    }
    val frame = when (s.wowGoodness) {
        1 -> " — " + stringResource(R.string.digest_frame_good)
        -1 -> " — " + stringResource(R.string.digest_frame_bad)
        else -> ""
    }
    return "$base$frame."
}
