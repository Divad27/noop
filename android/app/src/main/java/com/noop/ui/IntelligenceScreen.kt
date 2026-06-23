package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.RecoveryForecast
import com.noop.analytics.RecoveryForecaster
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Intelligence — NOOP's own Charge / Effort / Rest scores, presented with the
 * WHOOP-model explanation so the read-out is legible rather than a black box.
 *
 * Ports macOS Strand/Screens/IntelligenceView.swift. The macOS build runs an
 * on-device IntelligenceEngine that recomputes these scores from the strap's raw
 * streams (HR, R-R, accelerometer) using the WHOOP model shape. That raw-compute
 * port is later work on Android; until it lands this screen reads the cached
 * `DailyMetric` values the strap/store already provide and shows the same model
 * explainer + per-day breakdown — matching the macOS sparse-data contract of
 * surfacing real data with an honest note, never a fabricated score.
 */
@Composable
fun IntelligenceScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    // PERF (#scroll-jank): the BLE live state ticks ~1Hz. This screen reads `live` ONLY for the
    // "syncing history" note (backfilling + the chunk count), so reading the whole `live` object at
    // body scope recomposed the entire Intelligence screen on every HR tick. Collapse it to the two
    // fields the note needs via a structural-equality snapshot: a 72→73 bpm tick produces an EQUAL
    // snapshot and the body is NOT recomposed; it only recomposes when the backfilling state / chunk
    // count actually changes. Mirrors the shipped Today liveSnap fix. Appearance-preserving.
    val live by vm.live.collectAsStateWithLifecycle()
    val backfillNote by remember {
        derivedStateOf {
            val s = live
            if (s.backfilling) s.syncChunksThisSession else null
        }
    }

    // Effort display scale (#268) — routes every Effort value/label on this screen. Display-only.
    val effortScale = UnitPrefs.effortScale(LocalContext.current)

    // Newest first for the per-day list (macOS ForEach renders most-recent at top).
    val ordered = remember(days) { days.reversed() }

    // Evening forecast of tomorrow-morning Charge from tonight's known levers. `days` is
    // already OLDEST→NEWEST (what the forecaster wants); today's Effort is the newest day.
    // null (and the card hidden) until there are enough scored nights to anchor honestly.
    val forecast = remember(days) {
        val charge = days.mapNotNull { it.recovery }
        val effort = days.mapNotNull { it.strain }
        val sleeps = days.mapNotNull { it.totalSleepMin }
        val plannedHours = if (sleeps.isEmpty()) RecoveryForecaster.defaultNeedHours
            else (sleeps.sum() / sleeps.size) / 60.0
        RecoveryForecaster.forecast(
            recentCharge = charge,
            recentEffort = effort,
            todayEffort = ordered.firstOrNull()?.strain,
            plannedSleepHours = plannedHours,
        )
    }

    // Range + filtered day list are hoisted ABOVE the lazy scaffold: these are @Composable
    // state hooks (remember), which can't run inside the LazyListScope content lambda. The
    // filtering is a cheap in-memory predicate; the freeze (#345) was the eager BUILDING of
    // 800+ day cards, which the LazyColumn below now defers to what's on screen.
    var range by remember { mutableStateOf(IntelRange.Month) }
    val filtered = remember(ordered, range) {
        val n = range.days ?: return@remember ordered
        val cutoff = LocalDate.now().minusDays((n - 1).toLong()).toString()
        ordered.filter { it.day >= cutoff }
    }

    LazyScreenScaffold(
        title = stringResource(R.string.intelligence_title),
        subtitle = stringResource(R.string.intelligence_subtitle),
    ) {
        item { forecast?.let { ForecastCard(it) } }
        item { ExplainerCard(effortScale) }
        item { ModelBreakdownCard(effortScale) }

        if (ordered.isEmpty()) {
            item {
                // While the strap is mid-offload, say so — an empty list reads as final otherwise (#77).
                if (backfillNote != null) SyncingHistoryNote(chunks = backfillNote!!)
                EmptyNote()
            }
        } else {
            item {
                // Header row: section label left, range control right. Lets you narrow the
                // per-day list to a recent window (lexicographic YYYY-MM-DD compare == chronological).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Overline(stringResource(R.string.intelligence_recent))
                        Text(
                            stringResource(R.string.intelligence_by_day),
                            style = NoopType.title2,
                            color = Palette.textPrimary,
                        )
                    }
                    // Range labels are localized at the render site: the enum keeps canonical en-US
                    // codes ("W"/"M"/"3M"/"6M"/"1Y"/"ALL"), and the shared resolver maps them to the
                    // German short labels (1J/ALLE) — SegmentedPillControl's `label` lambda is not
                    // @Composable, so build the lookup map in composable scope first.
                    val rangeItems = IntelRange.entries.toList()
                    val rangeLabels = rememberRangeShortLabels(rangeItems) { it.label }
                    SegmentedPillControl(
                        items = rangeItems,
                        selection = range,
                        label = { rangeLabels[it] ?: it.label },
                        onSelect = { range = it },
                    )
                }
            }
            item {
                val dayWord = stringResource(
                    if (filtered.size == 1) {
                        R.string.intelligence_day_singular
                    } else {
                        R.string.intelligence_day_plural
                    },
                )
                Text(
                    stringResource(R.string.intelligence_day_count, filtered.size, dayWord),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }

            if (filtered.isEmpty()) {
                item {
                    NoopCard(padding = 18.dp) {
                        Text(
                            stringResource(R.string.intelligence_no_days_in_window),
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    }
                }
            } else {
                // The lazily-built day list — only the visible cards are composed, so even an
                // 800+ day "ALL" range no longer blocks the main thread. Keyed by day so scroll
                // position and recomposition stay stable as the range changes.
                items(filtered, key = { it.day }) { day -> DayCard(day, effortScale) }
            }
        }
    }
}

// MARK: - Tomorrow's Charge forecast hero (ported from IntelligenceView.forecastCard)
//
// An evening ESTIMATE of tomorrow-morning Charge from tonight's known levers —
// today's Effort vs your norm, your typical sleep, and the recent recovery baseline.
// Design Reset (Today parity): a clean flat Charge ring on an opaque flat card — a
// [GlowRing] (bloom off) on a [NoopCard] surfaceRaised surface, NOT a layered bevel
// gauge floated over a scenic backdrop — with the ± band + state word beneath it and
// the plain-English read-out below. Labelled an estimate; the real Charge is scored
// from tomorrow's HRV. The number, band and copy are unchanged.

@Composable
private fun ForecastCard(f: RecoveryForecast) {
    val charge = f.charge.roundToInt()
    val band = f.band.roundToInt()
    // Hoisted out of the readout Text() below: stringResource is @Composable-only and can't run
    // inside the "…" + "…" expression argument. The label rounds to the nearest 30 min.
    val sleepLabel = sleepHoursLabel(f.plannedSleepHours)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.intelligence_tomorrows_charge),
            overline = stringResource(R.string.intelligence_evening_forecast),
            trailing = stringResource(R.string.intelligence_estimate),
        )
        NoopCard(padding = 20.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlowRing(
                        fraction = (f.charge / 100.0).coerceIn(0.0, 1.0).toFloat(),
                        value = f.charge,
                        color = Palette.recoveryColor(f.charge),
                        diameter = 168.dp,
                        lineWidth = 168.dp * 0.10f,
                    )
                    Text(
                        stringResource(R.string.intel_forecast_band, band),
                        style = NoopType.captionNumber,
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        // Localized recovery-state word; Palette.recoveryState stays canonical en-US.
                        recoveryStateLabel(f.charge),
                        style = NoopType.overline,
                        color = Palette.recoveryColor(f.charge),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.intelligence_forecast_readout,
                            charge,
                            band,
                            sleepLabel,
                        ),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                    Text(
                        stringResource(R.string.intelligence_forecast_disclaimer, f.nights),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

/** "7h" / "7h 30m" for the planned-sleep assumption (rounded to the nearest 30 min). */
@Composable
private fun sleepHoursLabel(hours: Double): String {
    val half = (hours * 2).roundToInt() / 2.0
    val h = half.toInt()
    val m = ((half - h) * 60).roundToInt()
    return if (m == 0) {
        stringResource(R.string.intelligence_sleep_hours_h, h)
    } else {
        stringResource(R.string.intelligence_sleep_hours_h_m, h, m)
    }
}

// MARK: - Explainer (ported from IntelligenceView.explainerCard)

@Composable
private fun ExplainerCard(effortScale: EffortScale) {
    NoopCard(padding = 20.dp, tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Palette.chargeColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.intelligence_how_this_works),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
            }
            Text(
                stringResource(
                    R.string.intelligence_explainer_body,
                    UnitFormatter.effortScaleMax(effortScale),
                ),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

// MARK: - Empty note

@Composable
private fun EmptyNote() {
    NoopCard(padding = 20.dp, tint = Palette.chargeColor) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = Palette.chargeColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.intelligence_empty_note),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

// MARK: - Model weighting breakdown
//
// Makes the Charge formula concrete: the five weighted inputs plus the 0–100
// Effort scale. Pure presentation of the model the macOS engine uses — no per-day
// data, so it's always legible even before any day is scored.

@Composable
private fun ModelBreakdownCard(effortScale: EffortScale) {
    NoopCard(padding = 20.dp, tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Overline(stringResource(R.string.intelligence_charge_model))
            WeightRow(
                stringResource(R.string.intelligence_weight_hrv),
                stringResource(R.string.intelligence_weight_hrv_pct),
                0.55f,
                Palette.metricPurple,
            )
            WeightRow(
                stringResource(R.string.intelligence_weight_rhr),
                stringResource(R.string.intelligence_weight_rhr_pct),
                0.20f,
                Palette.metricRose,
            )
            WeightRow(
                stringResource(R.string.intelligence_weight_rest_quality),
                stringResource(R.string.intelligence_weight_rest_quality_pct),
                0.15f,
                Palette.metricCyan,
            )
            WeightRow(
                stringResource(R.string.intelligence_weight_respiration),
                stringResource(R.string.intelligence_weight_respiration_pct),
                0.05f,
                Palette.accent,
            )
            WeightRow(
                stringResource(R.string.intelligence_weight_skin_temp),
                stringResource(R.string.intelligence_weight_skin_temp_pct),
                0.05f,
                Palette.metricAmber,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.intelligence_stat_effort),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        R.string.intelligence_effort_scale,
                        UnitFormatter.effortScaleMax(effortScale),
                    ),
                    style = NoopType.captionNumber,
                    color = Palette.effortColor,
                )
            }
        }
    }
}

@Composable
private fun WeightRow(label: String, percent: String, fraction: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = NoopType.subhead,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(percent, style = NoopType.captionNumber, color = color)
        }
        Meter(fraction = fraction, color = color)
    }
}

/** A thin, rounded proportional meter on the inset well. */
@Composable
private fun Meter(fraction: Float, color: Color) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(Palette.surfaceInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(shape)
                .background(color),
        )
    }
}

// MARK: - Per-day card (ported from IntelligenceView.dayCard)
//
// Header = the day + a NOOP-computed source badge; a row of the five headline
// scores (Charge / Effort / Rest / HRV / RHR) tinted to the design-system metric
// colors, then a thin Effort meter for at-a-glance load.

@Composable
private fun DayCard(d: DailyMetric, effortScale: EffortScale) {
    NoopCard(padding = 18.dp, tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    prettyDay(d.day),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                // The REAL source of this day's dashboard headline, not a hard-coded "NOOP-computed".
                // The merged DailyMetric carries the WINNING row's deviceId (mergeDaily: an import wins
                // over the computed "-noop" row), so a strap-scored night reads "On-device" while a day an
                // import covers reads "Whoop" / "Apple Health". Computed rows keep the charge tint; imports
                // use the accent tint to stand out. Mirrors macOS IntelligenceEngine.DaySource. (Sleep §2.6.)
                // Localize only the DISPLAY at the render site: the tint is the canonical
                // daySourceBadge() merge logic (shared with SleepScreen), the label is resolved
                // from the structured deviceId through the German string resources.
                val src = daySourceBadge(d.deviceId)
                SourceBadge(daySourceLabel(d.deviceId), tint = src.second)
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                DayStat(
                    stringResource(R.string.intelligence_stat_charge),
                    d.recovery?.let { stringResource(R.string.intelligence_pct_value, it.roundToInt()) } ?: "—",
                    d.recovery?.let { Palette.recoveryColor(it) } ?: Palette.textSecondary,
                    Modifier.weight(1f),
                )
                DayStat(
                    stringResource(R.string.intelligence_stat_effort),
                    d.strain?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: "—",
                    d.strain?.let { Palette.strainColor(it) } ?: Palette.textSecondary,
                    Modifier.weight(1f),
                )
                DayStat(
                    stringResource(R.string.intelligence_stat_rest),
                    sleepValue(d.totalSleepMin),
                    Palette.restColor,
                    Modifier.weight(1f),
                )
                DayStat(
                    stringResource(R.string.intelligence_stat_hrv),
                    d.avgHrv?.let { "${it.roundToInt()}" } ?: "—",
                    Palette.metricPurple,
                    Modifier.weight(1f),
                )
                DayStat(
                    stringResource(R.string.intelligence_stat_rhr),
                    d.restingHr?.toString() ?: "—",
                    Palette.metricRose,
                    Modifier.weight(1f),
                )
            }

            // Effort load meter (0–100), tinted along the strain ramp.
            d.strain?.let { s ->
                Meter(
                    fraction = (s / 100.0).toFloat(),
                    color = Palette.strainColor(s),
                )
            }
        }
    }
}

@Composable
private fun DayStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label.uppercase(),
            style = NoopType.footnote,
            color = Palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = NoopType.number(19f),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Derived helpers

private fun sleepValue(totalMin: Double?): String {
    val m = totalMin ?: return "—"
    val total = m.roundToInt()
    return "${total / 60}h ${total % 60}m"
}

/**
 * The By-Day source badge (label + tint) for a merged [DailyMetric], from the WINNING row's [deviceId].
 * The By-Day numbers are always NOOP's on-device scores, but when an import covers the day it wins the
 * dashboard merge (mergeDaily), so the badge says so instead of the old hard-coded "NOOP-computed".
 * A computed row's id ends in "-noop"; imports keep their source id ("my-whoop" export, "apple-health" /
 * "health-connect"). Brand wording matches the rest of the app (macOS DaySource: "On-device"/"Whoop"/
 * "Apple Health"); imports use the accent tint, computed rows the charge tint. (Sleep overhaul §2.6.)
 */
internal fun daySourceBadge(deviceId: String): Pair<String, Color> = when {
    deviceId.endsWith("-noop") -> "On-device" to Palette.chargeColor
    deviceId == com.noop.data.WhoopRepository.APPLE_HEALTH_SOURCE ||
        deviceId == com.noop.data.WhoopRepository.HEALTH_CONNECT_SOURCE -> "Apple Health" to Palette.accent
    else -> "Whoop" to Palette.accent
}

/**
 * Localized DISPLAY label for the By-Day source badge, resolved from the structured [deviceId].
 * Mirrors the same merge cases as [daySourceBadge] (which stays canonical en-US, shared with
 * SleepScreen and the dashboard merge logic); this routes only the word through the German string
 * resources — the producer-vs-display split the rest of the i18n uses. (Sleep overhaul §2.6.)
 */
@Composable
private fun daySourceLabel(deviceId: String): String = when {
    deviceId.endsWith("-noop") -> stringResource(R.string.intelligence_source_on_device)
    deviceId == com.noop.data.WhoopRepository.APPLE_HEALTH_SOURCE ||
        deviceId == com.noop.data.WhoopRepository.HEALTH_CONNECT_SOURCE ->
        stringResource(R.string.intelligence_source_apple_health)
    else -> stringResource(R.string.intelligence_source_whoop)
}

/** Recent-window options for the By Day list. `days == null` means show everything. */
private enum class IntelRange(val days: Int?, val label: String) {
    Week(7, "W"), Month(30, "M"), Quarter(90, "3M"),
    Half(180, "6M"), Year(365, "1Y"), All(null, "ALL"),
}

/** "YYYY-MM-DD" → "Mon 5 Jun"; falls back to the raw key if it doesn't parse. */
private fun prettyDay(day: String): String {
    return try {
        val parts = day.split("-")
        val y = parts[0].toInt()
        val mo = parts[1].toInt()
        val da = parts[2].toInt()
        val cal = Calendar.getInstance().apply { set(y, mo - 1, da) }
        val dow = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[
            cal.get(Calendar.DAY_OF_WEEK) - 1,
        ]
        val month = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )[mo - 1]
        "$dow $da $month"
    } catch (_: Exception) {
        day
    }
}
