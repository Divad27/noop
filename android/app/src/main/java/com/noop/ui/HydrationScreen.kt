package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.HydrationGoal
import com.noop.analytics.HydrationStore
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Hydration detail (MVP, opt-in, local-only)
//
// Design-Reset compliant: a clean progress ring (GlowRing, blue accent, no bloom on the light field), the
// three quick-log buttons (Sip / Cup / Bottle) in the secondary NoopButton style, today's logged entries
// as a small list, and a 7-day mini bar history. Flat cards (NoopCard), NoopMetrics spacing, tokens only,
// no gold. Mirrors the iOS HydrationView. The hydration day total + history come from the local-only
// HydrationStore series; the goal is the pure HydrationGoal engine (profile sex + today's Effort bump).

/** The reset accent blue (matches NoopButton's pinned iOS `StrandPalette.accent`: #234F9E / #60A0E0). */
private val hydrationAccent: Color
    @Composable get() = if (Palette.isLight) Color(0xFF234F9E) else Color(0xFF60A0E0)

/**
 * The Hydration detail screen. The goal's effort bump uses today's Effort/strain (0..100) read from the
 * view-model's `today` row (null leaves the bump at 0). The screen reads/writes via [viewModel].repo;
 * the SharedPreferences-backed profile sex is read once. A log tap appends to the local-only day total
 * and refreshes the ring + history.
 */
@Composable
fun HydrationScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val today by viewModel.today.collectAsStateWithLifecycle()
    val strain = today?.strain

    val sex = remember { ProfileStore.from(context).sex }
    val goalMl = remember(sex, strain) { HydrationGoal.dailyGoalMl(sex, strain) }

    // Today's running total + the per-day history, loaded off the gesture path and refreshed after a log.
    var totalMl by remember { mutableStateOf(0.0) }
    var history by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    // A simple reload key the log taps bump so the LaunchedEffect re-reads the store.
    var reloadTick by remember { mutableStateOf(0) }
    LaunchedEffect(reloadTick) {
        totalMl = runCatching { HydrationStore.total(viewModel.repo) }.getOrDefault(0.0)
        history = runCatching { HydrationStore.history(viewModel.repo, days = 7) }.getOrDefault(emptyList())
    }

    val log: (Int) -> Unit = { amount ->
        scope.launch {
            runCatching { HydrationStore.log(viewModel.repo, amount) }
            reloadTick += 1
        }
    }

    val fraction = if (goalMl > 0) (totalMl / goalMl).toFloat() else 0f
    val accent = hydrationAccent

    // PERF (#707): lazy scaffold — each top-level section is one `item { }`. Order + spacing unchanged
    // (LazyColumn reproduces the eager `spacedBy(20.dp)`); only on-screen cards compose + are
    // accessibility-walked. All children are unconditional, so every wrap is a bare `item { }`.
    LazyScreenScaffold(
        title = stringResource(R.string.hydration_title),
        subtitle = stringResource(R.string.hydration_subtitle),
    ) {
        // RING — total vs goal, in litres. GlowRing already shows the clean full-circle track with no
        // bloom on the light field; the blue accent keeps it on the reset palette (no gold).
        item {
        NoopCard(padding = 20.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    GlowRing(
                        fraction = fraction,
                        value = totalMl / 1000.0,
                        color = accent,
                        diameter = 184.dp,
                        lineWidth = 14.dp,
                        showsLabel = false,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            String.format(Locale.US, "%.1f", totalMl / 1000.0),
                            style = NoopType.title1.copy(fontWeight = FontWeight.Bold, fontSize = 40.sp),
                            color = Palette.textPrimary,
                        )
                        Text(
                            stringResource(
                                R.string.hydration_of_goal,
                                String.format(Locale.US, "%.1f", goalMl / 1000.0),
                            ),
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    }
                }
                Text(
                    stringResource(
                        R.string.hydration_percent_of_goal,
                        kotlin.math.min(100, (fraction * 100).toInt()),
                    ),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        }

        // LOG BUTTONS — Sip / Cup / Bottle, secondary style, equal width.
        item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            NoopButton(
                text = stringResource(R.string.hydration_sip),
                leadingIcon = Icons.Filled.WaterDrop,
                kind = NoopButtonKind.Secondary,
                modifier = Modifier.weight(1f),
            ) { log(HydrationGoal.SIP_ML) }
            NoopButton(
                text = stringResource(R.string.hydration_cup),
                leadingIcon = Icons.Filled.LocalDrink,
                kind = NoopButtonKind.Secondary,
                modifier = Modifier.weight(1f),
            ) { log(HydrationGoal.CUP_ML) }
            NoopButton(
                text = stringResource(R.string.hydration_bottle),
                leadingIcon = Icons.Filled.LocalDrink,
                kind = NoopButtonKind.Secondary,
                modifier = Modifier.weight(1f),
            ) { log(HydrationGoal.BOTTLE_ML) }
        }
        }
        item {
        Text(
            stringResource(
                R.string.hydration_amounts_caption,
                HydrationGoal.SIP_ML,
                HydrationGoal.CUP_ML,
                HydrationGoal.BOTTLE_ML,
            ),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        }

        // 7-DAY HISTORY — flat mini bars, today on the right.
        item {
        NoopCard(padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Overline(stringResource(R.string.hydration_last_7_days))
                HydrationHistoryBars(history = history, goalMl = goalMl, accent = accent)
            }
        }
        }

        // TODAY'S TOTAL as a single read-out row (the MVP "logged entries" — the day total is the running
        // sum the store banks; per-tap rows aren't separately persisted, so we show the honest day figure).
        item {
        NoopCard(padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Overline(stringResource(R.string.today_today))
                if (totalMl <= 0.0) {
                    Text(
                        stringResource(R.string.hydration_empty),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.WaterDrop,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.mind_logged_today),
                            style = NoopType.subhead,
                            color = Palette.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.hydration_logged_amount, totalMl.toInt()),
                            style = NoopType.headline.copy(fontWeight = FontWeight.SemiBold),
                            color = Palette.textPrimary,
                        )
                    }
                }
            }
        }
        }

        item {
        Text(
            stringResource(R.string.hydration_disclaimer),
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.Start,
        )
        }
    }
}

/**
 * The 7-day mini bar history: one flat rounded bar per day, height = day total ÷ goal (clamped to 1), with
 * the weekday initial beneath. Today's bar is the accent blue; prior days a muted accent. Empty days render
 * a faint track. Pure Compose Canvas + a row of labels (design-reset flat style, no gridlines/axes).
 */
@Composable
private fun HydrationHistoryBars(
    history: List<Pair<String, Double>>,
    goalMl: Int,
    accent: Color,
) {
    if (history.isEmpty()) {
        Text(stringResource(R.string.hydration_no_history), style = NoopType.footnote, color = Palette.textTertiary)
        return
    }
    val goal = goalMl.coerceAtLeast(1).toDouble()
    val track = Palette.textPrimary.copy(alpha = 0.10f)
    val priorBar = accent.copy(alpha = 0.45f)
    val lastIndex = history.lastIndex
    val maxMl = history.maxOf { it.second }
    // Scale the bars to the LARGER of the goal and the biggest day, so an over-goal day doesn't clip.
    val ceiling = kotlin.math.max(goal, maxMl).coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val n = history.size
            val gap = 10.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            val corner = 6.dp.toPx()
            history.forEachIndexed { i, (_, ml) ->
                val x = i * (barW + gap)
                // Track (full-height faint bar).
                drawRoundRectBar(x, 0f, barW, size.height, corner, track)
                val frac = (ml / ceiling).toFloat().coerceIn(0f, 1f)
                if (frac > 0f) {
                    val h = size.height * frac
                    val color = if (i == lastIndex) accent else priorBar
                    drawRoundRectBar(x, size.height - h, barW, h, corner, color)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            history.forEach { (dayKey, _) ->
                Text(
                    weekdayInitial(dayKey),
                    style = NoopType.overline.copy(letterSpacing = 0.2.sp),
                    color = Palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Draw one rounded-rect bar via the Canvas draw scope (small helper so the bar loop stays readable). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectBar(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    corner: Float,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
    )
}

/** The single-letter weekday for a yyyy-MM-dd key (M T W T F S S), or "·" when unparseable. */
private fun weekdayInitial(dayKey: String): String =
    runCatching {
        LocalDate.parse(dayKey).format(DateTimeFormatter.ofPattern("EEE", Locale.US)).take(1)
    }.getOrDefault("·")
