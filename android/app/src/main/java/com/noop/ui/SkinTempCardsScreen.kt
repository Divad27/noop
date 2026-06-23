package com.noop.ui

import androidx.compose.ui.res.stringResource
import com.noop.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.analytics.CircadianEngine
import com.noop.analytics.CyclePhaseEngine
import com.noop.analytics.IllnessSignalEngine
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Skin-temperature suite cards (v5 pillar) — Compose twin
//
// Kotlin/Compose mirror of Strand/Screens/SkinTempCardsView.swift. Three self-contained,
// reusable cards driven entirely by a pure com.noop.analytics engine RESULT passed in
// (Wave 3 runs the engines in the analytics pass and mounts these in the Health hub):
//
//   • CycleAwarenessCard   — CyclePhaseEngine.Result. OPT-IN (the host gates on a default-OFF
//                            preference). Awareness only — NOT contraception, NOT a fertility/
//                            ovulation predictor, NOT a diagnosis. Phase + cycle-day RANGE +
//                            probabilistic next-period WINDOW (never a hard date).
//   • BodyClockCard        — CircadianEngine.PhaseEstimate (+ optional JetLagPlan). LIGHT +
//                            SLEEP TIMING only, never a supplement/drug.
//   • HeadsUpCard          — IllnessSignalEngine.Result. Confounder-suppressed illness
//                            "heads-up". On-device estimate — not a diagnosis.
//
// DESIGN-SYSTEM ONLY: NoopCard + Palette/DomainTheme tokens, NoopType, Metrics, StatePill.
// No raw hex, no ad-hoc cards. Privacy-forward copy (this data is incapable of leaving the
// device, said on every sensitive surface). Cards take VALUES not stores, so a slip stays
// local and the engines remain testable + I/O-free. Wave-3 wiring noted at the foot.

// MARK: - Shared chrome

@Composable
private fun PrivacyNote(text: String = stringResource(R.string.skin_temp_cards_privacy_line)) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics { contentDescription = text },
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(11.dp),
        )
        Text(text, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/** A quiet tinted chip (fired signal / confounder / overline-adjacent tag). */
@Composable
private fun WhyChip(label: String, tint: Color) {
    Text(
        label,
        style = NoopType.captionNumber,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// MARK: - 1. Cycle awareness card (OPT-IN)

/**
 * Cycle phase awareness from the nightly skin-temperature shift. OPT-IN by design — the host
 * renders this only after the user enables cycle awareness (default OFF). Awareness only;
 * never contraception / fertility / diagnosis. Calm Rest indigo world — no valence, no red.
 */
@Composable
fun CycleAwarenessCard(
    result: CyclePhaseEngine.Result,
    onLogPeriod: (() -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
) {
    val hue = Palette.restColor
    NoopCard(tint = hue) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header: overline + confidence pill.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(stringResource(R.string.skin_temp_cards_cycle_awareness_overline))
                    Text(
                        stringResource(R.string.skin_temp_cards_from_your_nightly_temperature),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                StatePill(cycleConfidenceLabel(result.confidence), tone = cycleConfidenceTone(result.confidence))
            }

            // Headline phase + cycle-day RANGE.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    cyclePhaseTitle(result.phase),
                    style = NoopType.title2,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                cycleDayText(result)?.let {
                    Text(it, style = NoopType.bodyNumber, color = Palette.textSecondary)
                }
            }

            Text(result.note, style = NoopType.subhead, color = Palette.textSecondary)

            // Probabilistic next-period WINDOW, never a single date.
            result.nextPeriodWindow?.let { w ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = hue, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.skin_temp_cards_period_window, prettyDay(w.earliestDay), prettyDay(w.latestDay)),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }

            // Actions.
            if (onLogPeriod != null || onOpenDetail != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    if (onLogPeriod != null) {
                        OutlinedButton(onClick = onLogPeriod) { Text(stringResource(R.string.skin_temp_cards_log_period_start)) }
                    }
                    if (onOpenDetail != null) {
                        OutlinedButton(
                            onClick = onOpenDetail,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
                        ) { Text(stringResource(R.string.skin_temp_cards_view_detail)) }
                    }
                }
            }

            HorizontalDivider(color = Palette.hairline)

            // Standing awareness-only legal line (verbatim from the engine) + privacy promise.
            Text(CyclePhaseEngine.awarenessLine, style = NoopType.footnote, color = Palette.textTertiary)
            PrivacyNote()
        }
    }
}

/**
 * Shown in place of the card when the user has NOT opted in. A single calm opt-in card restating
 * the privacy promise at the point of consent (manual-first; default OFF).
 */
@Composable
fun CycleAwarenessOptInCard(onEnable: () -> Unit) {
    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Thermostat, contentDescription = null, tint = Palette.restColor, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.skin_temp_cards_cycle_awareness), style = NoopType.headline, color = Palette.textPrimary)
            }
            Text(
                stringResource(R.string.skin_temp_cards_noop_can_read_a_coarse_menstrual),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            PrivacyNote()
            OutlinedButton(onClick = onEnable) { Text(stringResource(R.string.skin_temp_cards_turn_on_cycle_awareness)) }
        }
    }
}

// MARK: - 2. Body Clock card

/**
 * Estimated body-clock phase + an optional jet-lag / shift plan. LIGHT + SLEEP TIMING only —
 * never a supplement. Behavioural awareness, approximate.
 */
@Composable
fun BodyClockCard(
    estimate: CircadianEngine.PhaseEstimate,
    plan: CircadianEngine.JetLagPlan? = null,
    onOpenPlanner: (() -> Unit)? = null,
) {
    val hue = Palette.restColor
    NoopCard(tint = hue) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(stringResource(R.string.skin_temp_cards_body_clock_overline))
                    Text(stringResource(R.string.skin_temp_cards_light_sleep_timing_only), style = NoopType.footnote, color = Palette.textTertiary)
                }
                StatePill(bodyClockConfidenceLabel(estimate.confidence), tone = bodyClockConfidenceTone(estimate.confidence))
            }

            Text(bodyClockOffsetTitle(estimate), style = NoopType.title2, color = Palette.textPrimary)

            Text(estimate.note, style = NoopType.subhead, color = Palette.textSecondary)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.NightsStay, contentDescription = null, tint = hue, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.skin_temp_cards_body_clock_low, clockString(estimate.tempMinHour)),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }

            val firstDay = plan?.days?.firstOrNull()
            if (plan != null && plan.direction != CircadianEngine.ShiftDirection.NONE && firstDay != null) {
                HorizontalDivider(color = Palette.hairline)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Overline(stringResource(R.string.skin_temp_cards_plan_overline, plan.estimatedDays))
                    Text(
                        stringResource(
                            R.string.skin_temp_cards_plan_day1,
                            clockString(firstDay.brightLightStartHour),
                            clockString(firstDay.brightLightEndHour),
                            clockString(firstDay.targetSleepHour),
                        ),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                    Text(plan.note, style = NoopType.footnote, color = Palette.textTertiary)
                }
            }

            if (onOpenPlanner != null) {
                OutlinedButton(
                    onClick = onOpenPlanner,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
                ) { Text(if (plan == null) stringResource(R.string.skin_temp_cards_plan_a_trip) else stringResource(R.string.skin_temp_cards_view_full_plan)) }
            }
        }
    }
}

// MARK: - 3. Heads-Up card (illness early-warning, confounder-suppressed)

/**
 * The confounder-suppressed illness "heads-up". Renders the engine's already-decided level +
 * copy; the host only mounts it when the engine returns a non-quiet level. On-device estimate
 * — not a diagnosis. Mirrors the existing amber alert treatment.
 */
@Composable
fun HeadsUpCard(result: IllnessSignalEngine.Result) {
    val hue = headsUpHue(result.level)
    NoopCard(padding = 14.dp, tint = hue) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(hue.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(headsUpGlyph(result.level), contentDescription = null, tint = hue, modifier = Modifier.size(15.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(headsUpTitle(result.level), style = NoopType.headline, color = Palette.textPrimary)
                    Text(result.copy, style = NoopType.subhead, color = Palette.textSecondary)
                }
            }

            // The visible "why": which signals fired.
            if (result.firedSignals.isNotEmpty()) {
                WhyRow(stringResource(R.string.skin_temp_cards_signals_up), result.firedSignals, hue)
            }
            // ...and what was ruled out (the differentiating part vs a black-box warning).
            if (result.suppressedBy.isNotEmpty()) {
                WhyRow(stringResource(R.string.skin_temp_cards_explained_by), result.suppressedBy, Palette.textTertiary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhyRow(label: String, values: List<String>, tint: Color) {
    val whyCd = stringResource(R.string.skin_temp_cards_why_cd, label, values.joinToString(", "))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = whyCd },
    ) {
        Overline(label)
        // Chips wrap to the next line rather than overflowing the card on a long confounder list.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { WhyChip(it, tint) }
        }
    }
}

// MARK: - Derived copy / presentation (mirror the Swift card exactly)

@Composable
private fun cyclePhaseTitle(phase: CyclePhaseEngine.Phase): String = when (phase) {
    CyclePhaseEngine.Phase.FOLLICULAR -> stringResource(R.string.skin_temp_cards_phase_follicular)
    CyclePhaseEngine.Phase.PERI_OVULATORY -> stringResource(R.string.skin_temp_cards_phase_mid_cycle)
    CyclePhaseEngine.Phase.LUTEAL -> stringResource(R.string.skin_temp_cards_phase_luteal)
    CyclePhaseEngine.Phase.UNKNOWN -> stringResource(R.string.skin_temp_cards_phase_no_pattern)
    CyclePhaseEngine.Phase.LEARNING -> stringResource(R.string.skin_temp_cards_phase_learning)
}

/** "~day 18–22" — always a RANGE, never a single point. */
@Composable
private fun cycleDayText(r: CyclePhaseEngine.Result): String? {
    val lo = r.cycleDayLow ?: return null
    val hi = r.cycleDayHigh ?: return null
    return if (lo == hi) stringResource(R.string.skin_temp_cards_day_range_single, lo)
    else stringResource(R.string.skin_temp_cards_day_range, lo, hi)
}

@Composable
private fun cycleConfidenceLabel(c: CyclePhaseEngine.Confidence): String = when (c) {
    CyclePhaseEngine.Confidence.LEARNING -> stringResource(R.string.skin_temp_cards_conf_learning)
    CyclePhaseEngine.Confidence.BUILDING -> stringResource(R.string.skin_temp_cards_conf_building)
    CyclePhaseEngine.Confidence.SOLID -> stringResource(R.string.skin_temp_cards_conf_solid)
}

private fun cycleConfidenceTone(c: CyclePhaseEngine.Confidence): StrandTone = when (c) {
    CyclePhaseEngine.Confidence.LEARNING -> StrandTone.Neutral
    CyclePhaseEngine.Confidence.BUILDING -> StrandTone.Accent
    CyclePhaseEngine.Confidence.SOLID -> StrandTone.Accent
}

/** "About 25 min later than your schedule" — a plain, skimmable headline. */
@Composable
private fun bodyClockOffsetTitle(e: CircadianEngine.PhaseEstimate): String {
    if (e.confidence == CircadianEngine.PhaseConfidence.UNREADABLE) return stringResource(R.string.skin_temp_cards_offset_hard_to_read)
    val mins = abs(e.offsetVsScheduleMinutes).roundToInt()
    if (mins <= 20) return stringResource(R.string.skin_temp_cards_offset_in_sync)
    val dir = if (e.offsetVsScheduleMinutes > 0) stringResource(R.string.skin_temp_cards_offset_dir_later)
    else stringResource(R.string.skin_temp_cards_offset_dir_earlier)
    return stringResource(R.string.skin_temp_cards_offset_delta, mins, dir)
}

@Composable
private fun bodyClockConfidenceLabel(c: CircadianEngine.PhaseConfidence): String = when (c) {
    CircadianEngine.PhaseConfidence.UNREADABLE -> stringResource(R.string.skin_temp_cards_bc_conf_calibrating)
    CircadianEngine.PhaseConfidence.WIDE -> stringResource(R.string.skin_temp_cards_bc_conf_building)
    CircadianEngine.PhaseConfidence.SOLID -> stringResource(R.string.skin_temp_cards_bc_conf_solid)
}

private fun bodyClockConfidenceTone(c: CircadianEngine.PhaseConfidence): StrandTone = when (c) {
    CircadianEngine.PhaseConfidence.UNREADABLE -> StrandTone.Neutral
    CircadianEngine.PhaseConfidence.WIDE -> StrandTone.Accent
    CircadianEngine.PhaseConfidence.SOLID -> StrandTone.Accent
}

/** Card hue follows the level: raised / already-unwell = amber warning (matches the shipped
 *  banner); suppressed / mild = a calmer neutral so it never scares. */
private fun headsUpHue(level: IllnessSignalEngine.Level): Color = when (level) {
    IllnessSignalEngine.Level.RAISED, IllnessSignalEngine.Level.ALREADY_UNWELL -> Palette.statusWarning
    else -> Palette.restColor
}

private fun headsUpGlyph(level: IllnessSignalEngine.Level): ImageVector = when (level) {
    IllnessSignalEngine.Level.RAISED -> Icons.Filled.Warning
    IllnessSignalEngine.Level.ALREADY_UNWELL -> Icons.Filled.NightsStay
    IllnessSignalEngine.Level.SUPPRESSED -> Icons.Filled.Info
    IllnessSignalEngine.Level.MILD -> Icons.Filled.MonitorHeart
    IllnessSignalEngine.Level.QUIET -> Icons.Filled.CheckCircle
}

@Composable
private fun headsUpTitle(level: IllnessSignalEngine.Level): String = when (level) {
    IllnessSignalEngine.Level.RAISED -> stringResource(R.string.skin_temp_cards_headsup)
    IllnessSignalEngine.Level.ALREADY_UNWELL -> stringResource(R.string.skin_temp_cards_rest_up)
    IllnessSignalEngine.Level.SUPPRESSED -> stringResource(R.string.skin_temp_cards_probably_not_illness)
    IllnessSignalEngine.Level.MILD -> stringResource(R.string.skin_temp_cards_few_signals_up)
    IllnessSignalEngine.Level.QUIET -> stringResource(R.string.skin_temp_cards_nothing_notable)
}

// MARK: - Formatting helpers (locale-free, matching the engine's own helpers)

/** Render a fractional clock hour as "HH:MM". */
private fun clockString(hour: Double): String {
    var h = hour % 24.0
    if (h < 0) h += 24.0
    var hh = h.toInt()
    var mm = ((h - hh) * 60.0).roundToInt()
    if (mm == 60) { mm = 0; hh = (hh + 1) % 24 }
    return "%02d:%02d".format(hh, mm)
}

/** "12 Jun" from a "yyyy-MM-dd" key (display only; the engine math stays UTC). */
private fun prettyDay(key: String): String {
    val parts = key.split("-")
    if (parts.size != 3) return key
    val m = parts[1].toIntOrNull() ?: return key
    val d = parts[2].toIntOrNull() ?: return key
    if (m !in 1..12) return key
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "$d ${months[m - 1]}"
}
