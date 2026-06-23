package com.noop.ui

import androidx.compose.ui.res.stringResource
import com.noop.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

// MARK: - Deep Timeline (Android twin of FullDayChartView) — #575
//
// A full-day, full-resolution metric viewer reached from the Explore tab. The hard problem — never
// drawing ~86k points for a worn 24h — is solved by reading adaptively: day scale → coarse Room HR
// buckets (WhoopDao.hrBuckets, which already COALESCEs measured + v26 PPG #156), zoomed-in → raw
// per-second rows (WhoopDao.hrSamples, same COALESCE). The chart's pinch/pan reports the new window and
// we re-read at the new resolution. Mirrors macOS FullDayChartView + OverviewHRChart's zoom binding.

// The enum keeps a canonical English `title` (logic / lowercase() input); the localized label is
// resolved at the @Composable display sites via [timelineMetricTitle]. HRV/SpO₂ stay untranslated.
private enum class TimelineMetric(val title: String) {
    Hr("Heart Rate"),
    Hrv("HRV"),
    Spo2("SpO₂"),
    SkinTemp("Skin Temp"),
    Respiration("Respiration"),
    Motion("Motion"),
}

/** Localized timeline-metric title, resolved at the display site (the enum's `title` stays English). */
@Composable
private fun timelineMetricTitle(metric: TimelineMetric): String = stringResource(
    when (metric) {
        TimelineMetric.Hr -> R.string.full_day_chart_metric_hr
        TimelineMetric.Hrv -> R.string.full_day_chart_metric_hrv
        TimelineMetric.Spo2 -> R.string.full_day_chart_metric_spo2
        TimelineMetric.SkinTemp -> R.string.full_day_chart_metric_skin_temp
        TimelineMetric.Respiration -> R.string.full_day_chart_metric_respiration
        TimelineMetric.Motion -> R.string.full_day_chart_metric_motion
    }
)

@Composable
fun FullDayChartScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val deviceId = "my-whoop"
    val recentDays by vm.recentDays.collectAsStateWithLifecycle()

    // Today's local calendar midnight — the clamp the day stepper can never pass.
    val todayStart = remember {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis / 1000
    }
    // The day being shown … +24h. Mutable so the user can step back to days that actually have data
    // instead of a possibly-empty today (#597 — was today-only with no way back).
    var dayStartSec by remember { mutableStateOf(todayStart) }
    var didLand by remember { mutableStateOf(false) }
    val dayBounds = dayStartSec..(dayStartSec + 86_400)

    var metric by remember { mutableStateOf(TimelineMetric.Hr) }
    var ownedOnly by remember { mutableStateOf(true) }
    // The visible window the gestures drive; null → the whole day.
    var window by remember { mutableStateOf<LongRange?>(null) }
    val visible = window ?: dayBounds

    // #597 — one-shot: open on the most recent day that has data (lexicographic max of the yyyy-MM-dd keys
    // is chronological), so a just-synced-history user lands on real data instead of an empty today.
    LaunchedEffect(recentDays) {
        if (!didLand && recentDays.isNotEmpty()) {
            didLand = true
            val latest = recentDays.maxByOrNull { it.day }?.day?.let { dayKeyToEpochSec(it) }
            if (latest != null && latest < dayStartSec) { dayStartSec = latest; window = null }
        }
    }

    var points by remember { mutableStateOf<List<TimelinePoint>>(emptyList()) }
    var isRaw by remember { mutableStateOf(false) }
    var bucketSeconds by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }

    // Re-read on metric / source / settled-window / fresh-data change. The DB read picks raw vs buckets.
    LaunchedEffect(metric, ownedOnly, visible.first, visible.last, recentDays) {
        // PERF (#scroll-jank): a pinch/pan reports a NEW window on every gesture frame, each of which
        // re-keys this effect and previously fired a fresh Room query mid-gesture (heavy, on every
        // frame). Debounce by sleeping first: while the window is still moving, the next frame re-keys
        // the effect and cancels this one before the query runs, so ONLY the settled window (after the
        // gesture pauses ~130ms) actually hits the DB. The sleep is before `loading = true`, so during
        // a live gesture the existing chart stays put instead of flashing the "Loading the day…" state.
        // A metric/source/day switch re-keys too and waits the same ~130ms before loading — imperceptible,
        // and it keeps the chart showing the prior data until the new read lands. Behaviour-preserving:
        // the settled window still issues exactly the same query and renders identically.
        delay(130)
        loading = true
        val from = visible.first
        val to = visible.last
        val bucket = timelineBucketSeconds(to - from, targetPoints = 600)
        bucketSeconds = bucket
        isRaw = bucket <= 1L
        points = readTimeline(vm, deviceId, metric, from, to, bucket)
        loading = false
    }

    ScreenScaffold(
        title = stringResource(R.string.full_day_chart_screen_title),
        subtitle = stringResource(R.string.full_day_chart_screen_subtitle),
    ) {
        // METRIC PILLS — horizontally scrollable so all six fit on a phone. Titles are pre-resolved
        // here (the SegmentedPillControl label lambda is not @Composable) and looked up in the lambda.
        val metricTitles = TimelineMetric.entries.associateWith { timelineMetricTitle(it) }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            SegmentedPillControl(
                items = TimelineMetric.entries.toList(),
                selection = metric,
                label = { metricTitles.getValue(it) },
                onSelect = { metric = it; window = null },
            )
        }

        // SOURCE PILL — the owned strap, with the #574 owned/all scope toggle.
        val ownedLabel = stringResource(R.string.full_day_chart_owned)
        val allLabel = stringResource(R.string.full_day_chart_all)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.full_day_chart_my_whoop), style = NoopType.footnote, color = Palette.textSecondary)
            Spacer(Modifier.weight(1f))
            SegmentedPillControl(
                items = listOf(true, false),
                selection = ownedOnly,
                label = { if (it) ownedLabel else allLabel },
                onSelect = { ownedOnly = it },
            )
        }

        // DAY STEPPER — move the whole timeline back/forward a day (#597). Forward clamps at today.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            Text(
                "‹", style = NoopType.title2, color = Palette.accent,
                modifier = Modifier
                    .clickable { dayStartSec -= 86_400; window = null }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(dayLabel(dayStartSec, todayStart), style = NoopType.headline, color = Palette.textPrimary)
            Spacer(Modifier.weight(1f))
            val onLatest = dayStartSec >= todayStart
            Text(
                "›", style = NoopType.title2, color = if (onLatest) Palette.textTertiary else Palette.accent,
                modifier = Modifier
                    .then(if (onLatest) Modifier else Modifier.clickable { dayStartSec += 86_400; window = null })
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        NoopCard(tint = Palette.metricRose) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Overline(timelineMetricTitle(metric))
                        Text(resolutionSubtitle(points, isRaw, bucketSeconds),
                            style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    points.lastOrNull()?.let {
                        Text(formatValue(metric, it.value) + unitSuffix(metric),
                            style = NoopType.bodyNumber, color = Palette.textPrimary)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading && points.isEmpty() ->
                            Text(stringResource(R.string.full_day_chart_loading_the_day), style = NoopType.footnote, color = Palette.textTertiary)
                        points.isEmpty() -> EmptyTimelineState(metric, ownedOnly)
                        else -> TimelineChart(
                            points = points,
                            windowStart = visible.first,
                            windowEnd = visible.last,
                            bounds = dayBounds,
                            color = metricColor(metric),
                            modifier = Modifier.fillMaxWidth().height(280.dp),
                            onWindowChange = { window = it },
                        )
                    }
                }

                if (points.isNotEmpty()) {
                    val vals = points.map { it.value }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TimelineStat(stringResource(R.string.full_day_chart_min), formatValue(metric, vals.minOrNull() ?: 0.0), Modifier.weight(1f))
                        TimelineStat(stringResource(R.string.full_day_chart_avg), formatValue(metric, vals.average()), Modifier.weight(1f))
                        TimelineStat(stringResource(R.string.full_day_chart_max), formatValue(metric, vals.maxOrNull() ?: 0.0), Modifier.weight(1f))
                    }
                }
            }
        }

        // ZOOM HINT + reset.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (window == null) stringResource(R.string.full_day_chart_pinch_to_zoom) else stringResource(R.string.full_day_chart_zoomed_in),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            if (window != null) {
                Text(
                    stringResource(R.string.today_reset),
                    style = NoopType.footnote,
                    color = Palette.accent,
                    modifier = Modifier.clickable { window = null },
                )
            }
        }
    }
}

@Composable
private fun EmptyTimelineState(metric: TimelineMetric, ownedOnly: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Text(stringResource(R.string.full_day_chart_no_metric_here, timelineMetricTitle(metric).lowercase(Locale.US)),
            style = NoopType.body, color = Palette.textSecondary)
        Text(
            if (ownedOnly) stringResource(R.string.full_day_chart_nothing_offloaded)
            else stringResource(R.string.full_day_chart_other_sources_no_raw),
            style = NoopType.footnote, color = Palette.textTertiary, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TimelineStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = NoopType.footnote, color = Palette.textTertiary)
        Text(value, style = NoopType.captionNumber, color = Palette.textSecondary)
    }
}

// MARK: - Read

/** Adaptive read: HR rides the COALESCE-preserving Room reads (buckets at day scale, raw when zoomed);
 *  other metrics read their raw sample tables and bin in-process when zoomed out. */
private suspend fun readTimeline(
    vm: AppViewModel,
    deviceId: String,
    metric: TimelineMetric,
    from: Long,
    to: Long,
    bucket: Long,
): List<TimelinePoint> {
    val repo = vm.repo
    if (metric == TimelineMetric.Hr) {
        return if (bucket <= 1L) {
            runCatching { repo.hrSamples(deviceId, from, to, limit = 200_000) }.getOrDefault(emptyList())
                .map { TimelinePoint(it.ts, it.bpm.toDouble()) }
        } else {
            runCatching { repo.hrBuckets(deviceId, from, to, bucket) }.getOrDefault(emptyList())
                .map { TimelinePoint(it.bucket, it.avgBpm) }
        }
    }
    val raw: List<TimelinePoint> = when (metric) {
        TimelineMetric.Hr -> emptyList()
        TimelineMetric.Hrv ->
            runCatching { repo.rrIntervals(deviceId, from, to, 200_000) }.getOrDefault(emptyList())
                .map { TimelinePoint(it.ts, it.rrMs.toDouble()) }
        TimelineMetric.Spo2 ->
            runCatching { repo.spo2Samples(deviceId, from, to, 200_000) }.getOrDefault(emptyList())
                .mapNotNull { if (it.ir > 0) TimelinePoint(it.ts, it.red.toDouble() / it.ir) else null }
        TimelineMetric.SkinTemp ->
            runCatching { repo.skinTempSamples(deviceId, from, to, 200_000) }.getOrDefault(emptyList())
                .map { TimelinePoint(it.ts, it.raw / 100.0) } // centidegrees → °C (#156)
        TimelineMetric.Respiration ->
            runCatching { repo.respSamples(deviceId, from, to, 200_000) }.getOrDefault(emptyList())
                .map { TimelinePoint(it.ts, it.raw.toDouble()) }
        TimelineMetric.Motion ->
            runCatching { repo.gravitySamples(deviceId, from, to, 200_000) }.getOrDefault(emptyList())
                .map { TimelinePoint(it.ts, kotlin.math.sqrt(it.x * it.x + it.y * it.y + it.z * it.z)) }
    }
    if (raw.isEmpty() || bucket <= 1L) return raw
    return downsampleTimeline(raw, bucket)
}

/** Mean-bin raw timeline points onto a bucketSeconds grid (the in-process twin of the SQL hrBuckets),
 *  ascending. Pure. */
fun downsampleTimeline(points: List<TimelinePoint>, bucketSeconds: Long): List<TimelinePoint> {
    val bucket = bucketSeconds.coerceAtLeast(1L)
    if (points.isEmpty()) return emptyList()
    val sums = HashMap<Long, Pair<Double, Int>>()
    for (p in points) {
        val key = (p.ts / bucket) * bucket
        val acc = sums[key] ?: (0.0 to 0)
        sums[key] = (acc.first + p.value) to (acc.second + 1)
    }
    return sums.keys.sorted().map { key ->
        val acc = sums.getValue(key)
        TimelinePoint(key, acc.first / acc.second)
    }
}

// MARK: - Presentation

/** Localized resolution subtitle, resolved at the display site (single @Composable caller). */
@Composable
private fun resolutionSubtitle(points: List<TimelinePoint>, isRaw: Boolean, bucketSeconds: Long): String {
    if (points.isEmpty()) return "—"
    if (isRaw) return stringResource(R.string.full_day_chart_raw_per_second)
    val m = bucketSeconds / 60
    return if (m >= 1) stringResource(R.string.full_day_chart_minute_average, m)
    else stringResource(R.string.full_day_chart_second_average, bucketSeconds)
}

private fun metricColor(metric: TimelineMetric): Color = when (metric) {
    TimelineMetric.Hr -> Palette.metricRose
    TimelineMetric.SkinTemp -> Palette.strain033
    TimelineMetric.Hrv, TimelineMetric.Spo2 -> Palette.sleepLight
    TimelineMetric.Respiration, TimelineMetric.Motion -> Palette.textSecondary
}

private fun unitSuffix(metric: TimelineMetric): String = when (metric) {
    TimelineMetric.Hr -> " bpm"
    TimelineMetric.SkinTemp -> "°C"
    TimelineMetric.Hrv -> " ms"
    else -> ""
}

private fun formatValue(metric: TimelineMetric, v: Double): String = when (metric) {
    TimelineMetric.Hr, TimelineMetric.Respiration, TimelineMetric.Hrv -> v.toInt().toString()
    TimelineMetric.SkinTemp -> String.format(Locale.US, "%.1f", v)
    TimelineMetric.Spo2, TimelineMetric.Motion -> String.format(Locale.US, "%.2f", v)
}

/** Parse a yyyy-MM-dd day key to its LOCAL midnight epoch-seconds, or null if unparseable (#597). */
private fun dayKeyToEpochSec(day: String): Long? = runCatching {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = java.util.TimeZone.getDefault()
    (sdf.parse(day)?.time ?: return null) / 1000
}.getOrNull()

/** "Today" / "Yesterday" / "Wed 18 Jun" label for the Deep Timeline day stepper (#597). */
@Composable
private fun dayLabel(dayStartSec: Long, todayStart: Long): String = when (dayStartSec) {
    todayStart -> stringResource(R.string.timeline_day_today)
    todayStart - 86_400 -> stringResource(R.string.timeline_day_yesterday)
    else -> java.text.SimpleDateFormat(stringResource(R.string.fmt_timeline_day), Locale.getDefault())
        .format(java.util.Date(dayStartSec * 1000))
}
