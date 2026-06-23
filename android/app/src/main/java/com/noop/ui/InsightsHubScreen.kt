package com.noop.ui

import androidx.compose.ui.res.stringResource
import com.noop.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.analytics.DoseCurvePoint
import com.noop.analytics.DoseResponse
import com.noop.analytics.DoseResponseEngine
import com.noop.analytics.DoseResponsePriors
import com.noop.analytics.DosedBehavior
import com.noop.analytics.EffectRanker
import com.noop.analytics.RankedEffect
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Insights Hub (v5)
//
// The headline n-of-1 "what actually moves YOUR recovery" surface — the Compose twin of
// Strand/Screens/InsightsHubView.swift. Two halves, both pure association on the user's
// own logged days, never advice / cause / diagnosis:
//
//  1. WHAT MOVES YOUR CHARGE — the unified lag-aware EffectRanker feed. Each row keeps the
//     strongest honest lag ({0,+1,+2}) so it reads "shows up the next morning"; carries the
//     sign-aware sentence, with/without means, a lead/lag chip, the effect-size word, and a
//     Solid / Building / Calibrating confidence pill (not a bare "significant" stamp).
//
//  2. ALCOHOL / CAFFEINE DOSE-RESPONSE — the personal DoseResponseEngine curve that SHRINKS
//     toward a documented population prior until enough nights accrue. Plots the shrunk curve,
//     states "each extra drink ≈ −N for you" (honest when prior-dominated, or when YOUR data
//     contradicts the prior), and an evening "damage forecast" — "a 2nd drink tonight ≈ −X
//     Charge tomorrow" — driven by a small dose stepper on the latest Charge. Never a nudge
//     to drink or abstain.
//
// SELF-CONTAINED: owns its own InsightsHubViewModel (constructed from vm.repo + cached days);
// does NOT edit AppViewModel / AppRoot / the central nav. Wave 3 surfaces it at the head of the
// Insights hub. All maths is in com.noop.analytics (EffectRanker / DoseResponseEngine).

@Composable
fun InsightsHubScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsState()
    val hub = remember { InsightsHubViewModel() }
    val state by hub.state.collectAsState()

    // Re-derive whenever the cached days change underneath (journal + dose are read via repo).
    androidx.compose.runtime.LaunchedEffect(days) { hub.load(vm, days) }

    var outcome by remember { mutableStateOf(InsightsOutcome.Recovery) }
    val ranked = remember(state, outcome) { hub.rankFor(state, outcome) }

    // PERF (#707): lazy scaffold — each section (and its standalone Spacer, a real child of the eager
    // `spacedBy(20.dp)` Column) becomes one `item { }`, so the LazyColumn's matching `spacedBy(20.dp)`
    // reproduces identical spacing and only on-screen sections compose + are semantics-walked.
    LazyScreenScaffold(
        title = stringResource(R.string.insights_title),
        subtitle = stringResource(R.string.insights_hub_subtitle),
    ) {
        if (!state.loaded) {
            item {
            NoopCard {
                Text(stringResource(R.string.insights_reading_journal), style = NoopType.subhead, color = Palette.textTertiary)
            }
            }
            return@LazyScreenScaffold
        }

        // --- What moves your Charge -------------------------------------------
        item { MoversSection(outcome = outcome, onOutcome = { outcome = it }, ranked = ranked) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Dose-response (alcohol / caffeine) -------------------------------
        item { DoseSection(state.doseCards) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Method / honesty note --------------------------------------------
        item {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Overline(stringResource(R.string.insights_hub_how_to_read), color = Palette.textTertiary)
                Text(
                    stringResource(R.string.insights_hub_everything_here_is_a_pattern_in),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        }
    }
}

// MARK: - What moves your Charge

@Composable
private fun MoversSection(
    outcome: InsightsOutcome,
    onOutcome: (InsightsOutcome) -> Unit,
    ranked: List<RankedEffect>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Header then the outcome selector on its own row below it — on a ~360dp phone the pill
        // control can't share a row with the weighted header without compressing (matches macOS).
        SectionHeader(
            stringResource(
                R.string.insights_behaviour_effects_overline,
                insightsOutcomeLabel(outcome.outcomeName),
            ),
            overline = stringResource(R.string.insights_hub_movers_overline),
        )
        val outcomeLabels = rememberInsightsOutcomeLabels()
        SegmentedPillControl(
            items = InsightsOutcome.entries.toList(),
            selection = outcome,
            label = { outcomeLabels.getValue(it) },
            onSelect = onOutcome,
        )

        if (ranked.isEmpty()) {
            NoopCard {
                Text(
                    stringResource(
                        R.string.insights_hub_movers_no_overlap,
                        insightsOutcomeLabel(outcome.outcomeName),
                    ),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            // Fade + rise the ranked mover cards in sequence (mirrors iOS .staggeredAppear(index:)).
            ranked.forEachIndexed { i, r ->
                Box(modifier = Modifier.staggeredAppear(i)) { MoverCard(r, outcome) }
            }
        }
    }
}

@Composable
private fun MoverCard(r: RankedEffect, outcome: InsightsOutcome) {
    val e = r.effect
    val movedGood: Boolean? = when {
        e.delta == 0.0 -> null
        else -> (e.delta > 0) == outcome.higherIsBetter
    }
    val tone: StrandTone = when (movedGood) {
        null -> StrandTone.Neutral
        true -> StrandTone.Positive
        false -> if (e.significant) StrandTone.Critical else StrandTone.Warning
    }
    val tintColor = tone.color
    val arrow = if (e.delta > 0) stringResource(R.string.insights_effect_arrow_up)
        else if (e.delta < 0) stringResource(R.string.insights_effect_arrow_down)
        else stringResource(R.string.insights_effect_arrow_flat)
    val deltaText = e.pctChange?.let { stringResource(R.string.insights_hub_pct_change, arrow, abs(it).roundToInt()) }
        ?: "$arrow ${String.format(Locale.US, "%.1f", abs(e.delta))}"

    NoopCard(tint = outcome.domain.color) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header: behaviour name + lead/lag chip + confidence pill.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .drawBehind { drawCircle(tintColor) },
                    )
                    Text(
                        r.behavior,
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatePill(insightsLeadLag(r.lag), tone = StrandTone.Accent, showsDot = false)
                Spacer(Modifier.width(6.dp))
                ConfidencePill(r.confidence)
            }

            Text(
                insightsMoverSentence(r, outcomeLabel = insightsOutcomeLabel(outcome.outcomeName)),
                style = NoopType.body,
                color = Palette.textSecondary,
            )

            // With / without means as uniform StatTiles.
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.insights_stat_with),
                    value = outcome.format(e.meanWith),
                    caption = stringResource(R.string.insights_n_eq, e.nWith),
                    accent = tintColor,
                    delta = deltaText,
                    deltaColor = tintColor,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.insights_stat_without),
                    value = outcome.format(e.meanWithout),
                    caption = stringResource(R.string.insights_n_eq, e.nWithout),
                    accent = Palette.textPrimary,
                )
            }

            HorizontalDivider(color = Palette.hairline)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline(stringResource(R.string.insights_effect_size), modifier = Modifier.weight(1f))
                Text(
                    String.format(Locale.US, "d = %.2f", e.cohensD),
                    style = NoopType.captionNumber,
                    color = tintColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(effectMagnitudeWord(e.cohensD), style = NoopType.caption, color = Palette.textTertiary)
            }
        }
    }
}

// MARK: - Dose-response

@Composable
private fun DoseSection(cards: List<DoseCardData>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.insights_hub_dose_title),
            overline = stringResource(R.string.insights_hub_dose_overline),
        )
        if (cards.isEmpty()) {
            NoopCard {
                Text(
                    stringResource(R.string.insights_hub_log_alcohol_or_late_caffeine_with),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            // Fade + rise the dose-response cards in sequence (mirrors iOS .staggeredAppear(index:)).
            cards.forEachIndexed { i, card ->
                Box(modifier = Modifier.staggeredAppear(i)) { DoseResponseCard(card) }
            }
        }
    }
}

@Composable
private fun DoseResponseCard(card: DoseCardData) {
    val r = card.response
    val domain = if (card.outcomeName == "HRV") DomainTheme.Rest else DomainTheme.Charge
    // The evening preview dose, defaulting to a 2nd drink so the headline reads as a 2nd-drink forecast.
    var previewDose by remember(card.id) { mutableStateOf(2) }

    NoopCard(tint = domain.color) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(card.icon, contentDescription = null, tint = domain.color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(doseTitle(card), style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                ConfidencePill(r.confidence)
            }

            Text(
                insightsDoseSentence(r, outcomeLabel = insightsOutcomeLabel(card.outcomeName)),
                style = NoopType.body,
                color = Palette.textSecondary,
            )

            // The prior-shrunk curve. Resolve the a11y description here (a @Composable point);
            // the semantics lambda below is NOT @Composable so it can't call stringResource.
            val curveCd = curveDescription(card, r)
            DoseCurveChart(
                points = r.curve,
                accent = domain.color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clearAndSetSemantics { contentDescription = curveCd },
            )

            if (r.priorDominated) {
                HonestyBanner(
                    stringResource(
                        R.string.insights_hub_prior_dominated,
                        doseUnitLabel(card).lowercase(Locale.US),
                    ),
                    accent = Palette.textTertiary,
                )
            } else if (r.contradictsPrior) {
                HonestyBanner(
                    stringResource(R.string.insights_hub_contradicts_prior, insightsOutcomeLabel(card.outcomeName)),
                    accent = Palette.statusPositive,
                )
            }

            if (card.timingProxy) {
                Text(
                    stringResource(R.string.insights_hub_dose_here_is_timing_later_in),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }

            HorizontalDivider(color = Palette.hairline)

            DamageForecast(card, previewDose = previewDose, onPreviewDose = { previewDose = it }, domain = domain)
        }
    }
}

@Composable
private fun DamageForecast(
    card: DoseCardData,
    previewDose: Int,
    onPreviewDose: (Int) -> Unit,
    domain: DomainTheme,
) {
    val r = card.response
    val fromDose = 1
    val delta = r.delta(fromDose, previewDose)
    val projected = card.latestOutcome?.let { max(0.0, min(card.outcomeCeiling, it + delta)) }
    val stepLabel = if (previewDose <= 1) {
        stringResource(R.string.insights_hub_step_no_extra)
    } else {
        stringResource(R.string.insights_hub_step_label, previewDose, doseplusSuffix(card, previewDose))
    }
    val doseChoiceLabels = rememberDoseChoiceLabels(card)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Overline then the dose stepper on its own row — the choices (0…max+) overflow a ~360dp
        // phone if they share a row with the overline (matches the macOS fix).
        Overline(doseForecastOverline(card), modifier = Modifier.fillMaxWidth())
        SegmentedPillControl(
            items = card.doseChoices,
            selection = previewDose,
            label = { doseChoiceLabels.getValue(it) },
            onSelect = onPreviewDose,
        )

        Text(
            forecastSentence(card, previewDose, delta, stepLabel),
            style = NoopType.subhead,
            color = Palette.textSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.insights_hub_per_extra, doseUnitNoun(card)),
                value = signed(r.perUnit, card.outcomeSuffix),
                caption = if (r.priorDominated) stringResource(R.string.insights_hub_per_extra_typical)
                    else stringResource(R.string.insights_hub_per_extra_your_data),
                accent = if (r.perUnit < 0) Palette.statusCritical else Palette.statusPositive,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.insights_hub_tomorrows_outcome, insightsOutcomeLabel(card.outcomeName)),
                value = projected?.let { "${it.roundToInt()}${card.outcomeSuffix}" }
                    ?: stringResource(R.string.insights_dash),
                caption = if (projected != null) stringResource(R.string.insights_hub_projected, stepLabel)
                    else stringResource(R.string.insights_hub_needs_recent_day),
                accent = domain.color,
            )
        }
    }
}

@Composable
private fun HonestyBanner(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.surfaceInset)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).drawBehind { drawCircle(accent) })
        Text(text, style = NoopType.footnote, color = Palette.textSecondary)
    }
}

/** The confidence-lifecycle pill: Solid (positive/gold) / Building (accent) / Calibrating (neutral). */
@Composable
private fun ConfidencePill(c: ScoreConfidence) {
    val (label, tone) = when (c) {
        ScoreConfidence.SOLID -> stringResource(R.string.scoring_confidence_solid) to StrandTone.Positive
        ScoreConfidence.BUILDING -> stringResource(R.string.scoring_confidence_building) to StrandTone.Accent
        ScoreConfidence.CALIBRATING -> stringResource(R.string.scoring_confidence_calibrating) to StrandTone.Neutral
    }
    StatePill(label, tone = tone, showsDot = false)
}

// MARK: - Dose curve chart
//
// A compact line+area chart of the prior-shrunk curve: dose on x (0…max), modelled outcome
// DELTA on y, symmetric around a dashed zero line so the sign reads honestly. Drawn with the
// Compose Canvas idiom so it sits in the design system with no extra dependency.

@Composable
private fun DoseCurveChart(points: List<DoseCurvePoint>, accent: Color, modifier: Modifier = Modifier) {
    val zeroColor = Palette.hairlineStrong
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val maxAbs = max(1.0, points.maxOf { abs(it.outcomeDelta) })
        fun yFor(d: Double): Float {
            val t = (d / maxAbs + 1) / 2          // 0 (most negative) … 1 (most positive)
            return (h - t * h).toFloat()
        }
        val n = max(1, points.size - 1)
        fun xFor(i: Int): Float = i.toFloat() / n * w
        val zeroY = yFor(0.0)

        // Zero baseline (dashed).
        drawLine(
            color = zeroColor,
            start = Offset(0f, zeroY),
            end = Offset(w, zeroY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )

        // Filled area between the curve and the zero line.
        val area = Path().apply {
            moveTo(xFor(0), zeroY)
            points.forEachIndexed { i, pt -> lineTo(xFor(i), yFor(pt.outcomeDelta)) }
            lineTo(xFor(points.size - 1), zeroY)
            close()
        }
        drawPath(
            area,
            brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.03f))),
        )

        // The curve line.
        val line = Path().apply {
            points.forEachIndexed { i, pt ->
                val x = xFor(i)
                val y = yFor(pt.outcomeDelta)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(line, color = accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

        // Dose markers.
        points.forEachIndexed { i, pt ->
            drawCircle(accent, radius = 2.5.dp.toPx(), center = Offset(xFor(i), yFor(pt.outcomeDelta)))
        }
    }
}

// MARK: - Outcome

// i18n: `label` (canonical English) shows in the outcome pill; the displayed label is
// localized at the @Composable render point via [rememberInsightsOutcomeLabels]
// (R.string.intelligence_stat_{charge,hrv,rest,rhr}). `outcomeName` is an EN engine
// match-key, passed verbatim into format strings.
internal enum class InsightsOutcome(
    val label: String,
    val outcomeName: String,
    val key: String,
    val higherIsBetter: Boolean,
    val domain: DomainTheme,
    val pick: (DailyMetric) -> Double?,
    val format: (Double) -> String,
) {
    Recovery("Charge", "Charge", "recovery", true, DomainTheme.Charge, { it.recovery }, { "${it.roundToInt()}%" }),
    Hrv("HRV", "HRV", "hrv", true, DomainTheme.Rest, { it.avgHrv }, { "${it.roundToInt()} ms" }),
    Sleep("Rest", "Rest", "sleep_performance", true, DomainTheme.Rest, { it.efficiency }, { "${it.roundToInt()}%" }),
    Rhr("RHR", "Resting HR", "rhr", false, DomainTheme.Stress, { it.restingHr?.toDouble() }, { "${it.roundToInt()} bpm" }),
}

/** Pre-resolved outcome-pill labels (R.string.intelligence_stat_*), for the non-@Composable
 *  `label` lambda of [SegmentedPillControl] — resolve here (a @Composable point) and look up. */
@Composable
private fun rememberInsightsOutcomeLabels(): Map<InsightsOutcome, String> =
    InsightsOutcome.entries.associateWith {
        when (it) {
            InsightsOutcome.Recovery -> stringResource(R.string.intelligence_stat_charge)
            InsightsOutcome.Hrv -> stringResource(R.string.intelligence_stat_hrv)
            InsightsOutcome.Sleep -> stringResource(R.string.intelligence_stat_rest)
            InsightsOutcome.Rhr -> stringResource(R.string.intelligence_stat_rhr)
        }
    }

// MARK: - Dose card view-data

// i18n: the user-facing getters below (title, unitNoun, unitLabel, forecastOverline,
// doseChoiceLabel, dosePlusSuffix) keep canonical English; their displayed values are
// localized at the @Composable render point via the dose* resolvers below
// (R.string.insights_hub_* — alcohol/caffeine, unit_*, forecast_overline_*, dose_caffeine_*,
// dose_choice*, dose_plus_drinks/dose_drinks). `outcomeName` is an engine match-key kept EN.
internal data class DoseCardData(
    val behavior: DosedBehavior,
    val response: DoseResponse,
    val latestOutcome: Double?,
) {
    val id: String get() = behavior.raw
    val outcomeName: String get() = response.outcome
    val title: String get() = if (behavior == DosedBehavior.ALCOHOL) "Alcohol" else "Caffeine"
    val icon get() = if (behavior == DosedBehavior.ALCOHOL) Icons.Filled.LocalBar else Icons.Filled.Coffee
    val unitNoun: String get() = if (behavior == DosedBehavior.ALCOHOL) "drink" else "later step"
    val unitLabel: String get() = if (behavior == DosedBehavior.ALCOHOL) "drink" else "late-caffeine"
    val timingProxy: Boolean get() = behavior == DosedBehavior.CAFFEINE
    val outcomeSuffix: String get() = if (outcomeName == "HRV") " ms" else "%"
    val outcomeCeiling: Double get() = if (outcomeName == "HRV") 400.0 else 100.0
    val forecastOverline: String
        get() = if (behavior == DosedBehavior.ALCOHOL) "Tonight’s forecast" else "Timing forecast"

    val doseChoices: List<Int> get() = (0..DoseResponseEngine.maxCurveDose).toList()

    fun doseChoiceLabel(d: Int): String = when (behavior) {
        DosedBehavior.ALCOHOL -> if (d >= DoseResponseEngine.maxCurveDose) "$d+" else "$d"
        DosedBehavior.CAFFEINE -> when (d) {
            0 -> "AM"
            1 -> "Noon"
            2 -> "2pm+"
            else -> "Eve"
        }
    }

    fun dosePlusSuffix(d: Int): String =
        if (behavior == DosedBehavior.ALCOHOL) {
            if (d >= DoseResponseEngine.maxCurveDose) "+ drinks" else " drinks"
        } else ""
}

// MARK: - Dose card view-data — localized label resolvers
//
// @Composable mirrors of the DoseCardData getters: resolve the displayed strings from
// resources at the render point, keyed on the same behaviour/dose logic.

@Composable
private fun doseTitle(card: DoseCardData): String =
    if (card.behavior == DosedBehavior.ALCOHOL) stringResource(R.string.insights_hub_alcohol)
    else stringResource(R.string.insights_hub_caffeine)

/** Singular unit noun ("drink"/"later step") — used inside per-extra / forecast copy. */
@Composable
private fun doseUnitNoun(card: DoseCardData): String =
    if (card.behavior == DosedBehavior.ALCOHOL) stringResource(R.string.insights_hub_unit_drink)
    else stringResource(R.string.insights_hub_unit_later_step)

/** Unit label ("drink"/"late-caffeine") — used inside the prior-dominated / forecast copy. */
@Composable
private fun doseUnitLabel(card: DoseCardData): String =
    if (card.behavior == DosedBehavior.ALCOHOL) stringResource(R.string.insights_hub_unit_label_drink)
    else stringResource(R.string.insights_hub_unit_label_late_caffeine)

@Composable
private fun doseForecastOverline(card: DoseCardData): String =
    if (card.behavior == DosedBehavior.ALCOHOL) stringResource(R.string.insights_hub_forecast_overline_tonight)
    else stringResource(R.string.insights_hub_forecast_overline_timing)

/** Pre-resolved dose-choice labels per choice value, for the non-@Composable `label`
 *  lambda of [SegmentedPillControl]. */
@Composable
private fun rememberDoseChoiceLabels(card: DoseCardData): Map<Int, String> {
    val labels = HashMap<Int, String>()
    for (d in card.doseChoices) {
        labels[d] = when (card.behavior) {
            DosedBehavior.ALCOHOL ->
                if (d >= DoseResponseEngine.maxCurveDose) stringResource(R.string.insights_hub_dose_choice_plus, d)
                else stringResource(R.string.insights_hub_dose_choice, d)
            DosedBehavior.CAFFEINE -> when (d) {
                0 -> stringResource(R.string.insights_hub_dose_caffeine_am)
                1 -> stringResource(R.string.insights_hub_dose_caffeine_noon)
                2 -> stringResource(R.string.insights_hub_dose_caffeine_2pm)
                else -> stringResource(R.string.insights_hub_dose_caffeine_eve)
            }
        }
    }
    return labels
}

/** Localized "+ drinks"/" drinks" suffix for the step label (empty for caffeine). */
@Composable
private fun doseplusSuffix(card: DoseCardData, d: Int): String =
    if (card.behavior == DosedBehavior.ALCOHOL) {
        if (d >= DoseResponseEngine.maxCurveDose) stringResource(R.string.insights_hub_dose_plus_drinks)
        else stringResource(R.string.insights_hub_dose_drinks)
    } else ""

// MARK: - View-model
//
// Self-contained: loads the journal (behaviour → days), dose rows (under the dedicated
// noop-journal-dose source), and outcome series (cached DailyMetric rows), then runs
// EffectRanker for the ranked feed and DoseResponseEngine for each dosed behaviour with data.
// No edits to AppViewModel. Holds an immutable snapshot in a StateFlow.

internal class InsightsHubViewModel {

    data class Snapshot(
        val loaded: Boolean = false,
        val behaviours: Map<String, Set<String>> = emptyMap(),
        val outcomeByKey: Map<String, Map<String, Double>> = emptyMap(),
        val doseCards: List<DoseCardData> = emptyList(),
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    companion object {
        const val DOSE_SOURCE = "noop-journal-dose"
        private val OUTCOME_KEYS = listOf("recovery", "hrv", "sleep_performance", "rhr")

        fun doseKey(behavior: DosedBehavior): String = "dose_${behavior.raw}"

        fun matches(behavior: DosedBehavior, question: String): Boolean {
            val q = question.lowercase(Locale.US)
            return when (behavior) {
                DosedBehavior.ALCOHOL -> q.contains("alcohol") || q.contains("drink")
                DosedBehavior.CAFFEINE -> q.contains("caffeine") || q.contains("coffee")
            }
        }

        fun outcomeKeyFor(engineName: String): String = when (engineName) {
            "Charge" -> "recovery"
            "HRV" -> "hrv"
            "Rest" -> "sleep_performance"
            "Resting HR" -> "rhr"
            else -> "recovery"
        }
    }

    suspend fun load(vm: AppViewModel, days: List<DailyMetric>) {
        // Journal → behaviour → days (imported ∪ native, native wins; only "yes" counts).
        val imported = vm.repo.journal("my-whoop", "0000-01-01", "9999-12-31")
        val native = vm.repo.journal(JOURNAL_DEVICE_ID, "0000-01-01", "9999-12-31")
        val entries = mergeJournalEntries(imported, native)
        val byBehaviour = HashMap<String, MutableSet<String>>()
        for (e in entries) if (e.answeredYes) byBehaviour.getOrPut(e.question) { mutableSetOf() }.add(e.day)
        val behaviours = byBehaviour.mapValues { it.value.toSet() }

        // Outcome series straight off the cached DailyMetric rows (the guaranteed Android source).
        val outcomeByKey = HashMap<String, Map<String, Double>>()
        for (o in InsightsOutcome.entries) {
            val dict = HashMap<String, Double>()
            for (d in days) o.pick(d)?.let { dict[d.day] = it }
            outcomeByKey[o.key] = dict
        }

        // Dose rows per dosed behaviour, under the dedicated dose source; logged "yes" days
        // back-fill dose = 1, explicit dose rows override (matches the Swift contract).
        val doseCards = ArrayList<DoseCardData>()
        for (behavior in DosedBehavior.entries) {
            val doses = HashMap<String, Int>()
            for ((question, set) in behaviours) if (matches(behavior, question)) {
                for (day in set) doses[day] = max(doses[day] ?: 0, 1)
            }
            val rows = vm.repo.metricSeries(DOSE_SOURCE, doseKey(behavior), "0000-01-01", "9999-12-31")
            for (row in rows) doses[row.day] = row.value.roundToInt()
            if (doses.isEmpty()) continue

            val outcomeName = DoseResponsePriors.defaultOutcome(behavior)
            val outcomeDays = outcomeByKey[outcomeKeyFor(outcomeName)] ?: emptyMap()
            val response = DoseResponseEngine.estimate(behavior, doses, outcomeDays) ?: continue
            val latest = outcomeDays.keys.maxOrNull()?.let { outcomeDays[it] }
            doseCards.add(DoseCardData(behavior, response, latest))
        }

        _state.value = Snapshot(
            loaded = true,
            behaviours = behaviours,
            outcomeByKey = outcomeByKey,
            doseCards = doseCards,
        )
    }

    /** Re-rank the mover feed for a (possibly new) outcome — cheap, no DB. */
    fun rankFor(snapshot: Snapshot, outcome: InsightsOutcome): List<RankedEffect> {
        if (!snapshot.loaded) return emptyList()
        val outcomeDays = snapshot.outcomeByKey[outcome.key] ?: emptyMap()
        return EffectRanker.rank(snapshot.behaviours, outcomeDays, outcome.outcomeName)
    }
}

// MARK: - Copy helpers

// i18n: @Composable so the assembled forecast copy resolves from format strings
// (insights_hub_forecast_no_extra / _sentence / _dir_lower / _dir_higher /
// _basis_typical / _basis_your_data). `outcomeName` is the EN engine key, substituted verbatim.
@Composable
private fun forecastSentence(card: DoseCardData, previewDose: Int, delta: Double, stepLabel: String): String {
    val outcomeLower = insightsOutcomeLabel(card.outcomeName).lowercase(Locale.getDefault())
    if (previewDose <= 1) {
        return stringResource(R.string.insights_hub_forecast_no_extra, outcomeLower)
    }
    val mag = abs(delta).roundToInt()
    val dir = if (delta <= 0) stringResource(R.string.insights_hub_forecast_dir_lower)
        else stringResource(R.string.insights_hub_forecast_dir_higher)
    val basis = if (card.response.priorDominated) {
        stringResource(R.string.insights_hub_forecast_basis_typical)
    } else {
        stringResource(
            R.string.insights_hub_forecast_basis_your_data,
            card.response.nUser,
            doseUnitLabel(card).lowercase(Locale.US),
        )
    }
    return stringResource(
        R.string.insights_hub_forecast_sentence,
        stepLabel, mag, card.outcomeSuffix, dir, outcomeLower, basis,
    )
}

// i18n: @Composable so the contentDescription copy resolves from format strings
// (insights_hub_curve_description + _typical / _your_data); the outcome is localized via insightsOutcomeLabel.
@Composable
private fun curveDescription(card: DoseCardData, r: DoseResponse): String {
    val tail = if (r.priorDominated) stringResource(R.string.insights_hub_curve_description_typical)
        else stringResource(R.string.insights_hub_curve_description_your_data)
    return stringResource(
        R.string.insights_hub_curve_description,
        doseUnitNoun(card),
        signed(r.perUnit, card.outcomeSuffix),
        insightsOutcomeLabel(card.outcomeName),
        tail,
    )
}

private fun signed(v: Double, suffix: String): String {
    val mag = abs(v)
    val rounded = (mag * 10).roundToInt() / 10.0
    val sign = if (v < 0) "−" else if (v > 0) "+" else ""
    val body = if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}" else String.format(Locale.US, "%.1f", rounded)
    return "$sign$body$suffix"
}

// i18n: @Composable so the magnitude word resolves from R.string.insights_effect_magnitude_*.
@Composable
private fun effectMagnitudeWord(d: Double): String = when {
    abs(d) < 0.2 -> stringResource(R.string.insights_effect_magnitude_negligible)
    abs(d) < 0.5 -> stringResource(R.string.insights_effect_magnitude_small)
    abs(d) < 0.8 -> stringResource(R.string.insights_effect_magnitude_moderate)
    else -> stringResource(R.string.insights_effect_magnitude_large)
}

// MARK: - Insights "What moves you" / dose-response — localized sentence resolvers
//
// The analytics ENGINES (EffectRanker / DoseResponseEngine) are canonical en-US Swift-parity
// oracles and stay untouched; they format their own sentences in Locale.US. These @Composable
// resolvers RE-DERIVE the same sentences in the current locale from the engines' structured
// fields, so the German build reads German without ever editing the engines.

/** Format a non-integer number with the current locale's decimal separator (comma for de-DE). */
private fun germanDecimal(x: Double): String {
    val sep = java.text.DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
    return String.format(Locale.US, "%.1f", x).replace('.', sep)
}

/** Map an engine outcome match-key (e.g. "Charge", "HRV", "Resting HR") to its localized
 *  domain label; falls back to the raw name when there is no domain key. */
@Composable
private fun insightsOutcomeLabel(name: String): String = when (name.lowercase(Locale.US)) {
    "charge" -> stringResource(R.string.today_charge)
    "hrv" -> stringResource(R.string.today_hrv)
    "rest" -> stringResource(R.string.today_rest)
    "effort" -> stringResource(R.string.today_effort)
    "resting hr", "rhr" -> stringResource(R.string.today_resting_hr)
    "sleep" -> stringResource(R.string.today_sleep)
    else -> name
}

/** Localized lead/lag chip text — the German twin of RankedEffect.leadLagText. */
@Composable
private fun insightsLeadLag(lag: Int): String = when (lag) {
    0 -> stringResource(R.string.insights_lag_same_day)
    1 -> stringResource(R.string.insights_lag_next_morning)
    else -> stringResource(R.string.insights_lag_n_mornings, lag)
}

/** Localized twin of RankedEffect.sentence(): rebuilds the magnitude in the current locale
 *  from the structured BehaviorEffect fields, then fills the German sentence template. */
@Composable
private fun insightsMoverSentence(r: RankedEffect, outcomeLabel: String): String {
    val e = r.effect
    val dirWord = if (e.delta > 0) stringResource(R.string.insights_mover_dir_higher)
        else stringResource(R.string.insights_mover_dir_lower)
    val magnitude = when {
        e.delta == 0.0 -> stringResource(R.string.insights_mover_no_different)
        e.pctChange != null -> "${EffectRanker.roundedInt(abs(e.pctChange))}% $dirWord"
        else -> "${germanDecimal(EffectRanker.round1(abs(e.delta)))} $dirWord"
    }
    val avgWith = EffectRanker.roundedInt(e.meanWith)
    val avgWithout = EffectRanker.roundedInt(e.meanWithout)
    return stringResource(
        R.string.insights_mover_sentence,
        r.behavior, outcomeLabel, magnitude, avgWith, avgWithout, e.nWith, e.nWithout,
        insightsLeadLag(r.lag),
    )
}

/** Localized twin of DoseResponse.sentence(): branches on priorDominated / contradictsPrior /
 *  else exactly like the engine, with the magnitude re-derived in the current locale. */
@Composable
private fun insightsDoseSentence(r: DoseResponse, outcomeLabel: String): String {
    val mag = germanDecimal(DoseResponseEngine.round1(abs(r.perUnit)))
    val dirWord = if (r.perUnit <= 0) stringResource(R.string.insights_mover_dir_lower)
        else stringResource(R.string.insights_mover_dir_higher)
    return when {
        r.priorDominated ->
            stringResource(R.string.insights_dose_prior, mag, dirWord, outcomeLabel, r.nUser)
        r.contradictsPrior ->
            stringResource(R.string.insights_dose_contradicts, outcomeLabel, r.nUser)
        else ->
            stringResource(R.string.insights_dose_personal, mag, dirWord, outcomeLabel, r.nUser)
    }
}
