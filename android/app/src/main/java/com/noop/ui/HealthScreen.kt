package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.IllnessSignalEngine
import com.noop.analytics.V5HealthSignals
import com.noop.analytics.FitnessAgeEngine
import com.noop.analytics.VitalityEngine
import com.noop.analytics.FitnessAgeReadiness
import com.noop.analytics.FitnessReadinessItem
import com.noop.analytics.FitnessReadinessRole
import com.noop.analytics.FitnessReadinessStatus
import com.noop.analytics.VitalBands
import com.noop.ble.LiveState
import com.noop.data.DailyMetric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Health Monitor (ported from Strand/Screens/HealthView.swift)
//
// Live heart-rate hero (streaming HR + HR-zone read-out, derived from the strap's
// R-R stream when the HR field reads 0), then a uniform grid of the body's vital
// signs (respiratory rate, blood O2, resting HR, HRV, skin temp) as fixed-height
// StatTiles, each tinted and captioned with its in-range state. Re-skinned to the
// locked NOOP component system: every surface is a NoopCard/StatTile, every chart
// is a Canvas chart — no ad-hoc card heights or paddings.
//
// macOS parity note: live HR zone/%max reads the user's ProfileStore max heart rate,
// matching Settings/onboarding. SpO2 / respiratory / skin-temp are sleep-window
// aggregates, so the "Vital Signs" grid is sourced from today's DailyMetric.

@Composable
fun HealthScreen(
    vm: AppViewModel,
    onVitalClick: (String) -> Unit = {},
    onOpenLabBook: () -> Unit = {},
    onOpenFusedRecord: () -> Unit = {},
) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context.applicationContext) }
    val today by vm.today.collectAsStateWithLifecycle()
    // Full merged daily history — feeds the personal-baseline banding of the vitals grid.
    val days by vm.recentDays.collectAsStateWithLifecycle()
    // v5 skin-temp suite engine results (Cycle / Body clock / Illness heads-up), recomputed each
    // analytics pass and published by the ViewModel. Cycle awareness gates on its opt-in pref.
    val v5Signals by vm.v5Signals.collectAsStateWithLifecycle()
    val cycleEnabled by vm.cycleTrackingEnabled.collectAsStateWithLifecycle()
    val hrMax = profile.hrMax

    // Health Monitor shows live HR too, so it must keep the realtime stream on while it's visible —
    // otherwise leaving the Live page stopped the stream and this page froze (issue #18). Ref-counted
    // in the ViewModel, so handing off between Live and here never drops the stream.
    DisposableEffect(Unit) {
        vm.requestRealtimeHr()
        onDispose { vm.releaseRealtimeHr() }
    }

    // PERF (#scroll-jank): the BLE live state + smoothed bpm tick ~1Hz. Reading them in this body to
    // compute the empty-state gate recomposed the WHOLE Health screen on every HR tick. The body only
    // needs "is a live HR present" (null↔non-null), never the bpm number — so collapse the ticking
    // value to a stable boolean via derivedStateOf: a 72→73 bpm tick produces an EQUAL boolean and the
    // body is NOT recomposed; it only recomposes when live-HR presence actually flips. The live bpm
    // number is rendered in HeartRateSection / SyncStatusSection, which now scope their own collection.
    // Mirrors the shipped Today liveSnap fix. Appearance-preserving.
    val live by vm.live.collectAsStateWithLifecycle()
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val hasLiveHr by remember { derivedStateOf { displayHr(bpm, live) != null } }

    LazyScreenScaffold(
        title = stringResource(R.string.health_screen_title),
        subtitle = stringResource(R.string.health_screen_subtitle),
    ) {
        if (today == null && !hasLiveHr) {
            // Even with no history yet, a freshly-connected strap can be told to sync now (#364) — the
            // manual "Sync now" + honest status sits above the empty state so it's always reachable.
            item { SyncStatusSection(vm = vm, onSyncNow = { vm.syncNow() }) }
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            item { HealthEmptyState() }
        } else {
            // Manual "Sync now" + honest sync status (#364) — the first section so the strap-history
            // control is reachable above the live hero. Mirrors HealthView.swift's top Sync section.
            item { SyncStatusSection(vm = vm, onSyncNow = { vm.syncNow() }) }
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            // ScreenScaffold applies a 20dp arrangement gap between its direct children;
            // a small top-up reaches the section gap (28dp) used between macOS sections.
            item { HeartRateSection(vm = vm, hrMax = hrMax) }
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            item {
                VitalsSection(
                    title = stringResource(R.string.health_vitals_title),
                    overline = stringResource(R.string.health_vitals_overline_latest),
                    trailing = null,
                    vitals = latestVitals(days, UnitPrefs.temperature(LocalContext.current)),
                    onVitalClick = onVitalClick,
                    captionMode = VitalCaptionMode.AS_OF,
                )
            }
            // FITNESS AGE — the weekly Saturday number from the engine (resting HR + activity vs your
            // age), with an honest readiness checklist behind a tap. Authoritative value comes from the
            // metricSeries the IntelligenceEngine writes; readiness is derived from what this screen sees.
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            item { FitnessAgeSection(vm = vm, days = days, profile = profile) }
            item { VitalitySection(vm = vm, days = days, profile = profile) }
            // SKIN TEMPERATURE (v5 pillar) — Cycle awareness (opt-in), Body clock + an illness heads-up,
            // each from a pure engine RESULT the ViewModel publishes. A section of Health, never its own
            // destination (umbrella §2.4). Non-clinical observations about your own numbers.
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            item {
                SkinTempSuiteSection(
                    signals = v5Signals,
                    cycleEnabled = cycleEnabled,
                    onEnableCycle = { vm.setCycleTrackingEnabled(true) },
                )
            }
            // CONTRIBUTORS (README screen #5, recovery detail) — the signals behind recovery as
            // labelled progress bars in the shared stage/zone bar style, mirroring Today's section.
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            item { HealthContributorsSection(today) }
            // RECORDS & SOURCES (Swift parity) — deep-link rows into the local Lab Book and the
            // "Your Data, Fused" record, so both are discoverable from Health, not just the drawer.
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            item {
                RecordsAndSourcesSection(
                    onOpenLabBook = onOpenLabBook,
                    onOpenFusedRecord = onOpenFusedRecord,
                )
            }
        }
    }
}

// MARK: - Sync status + "Sync now" (#364)
//
// Manual "Sync now" control + honest sync status, mirroring HealthView.swift's SyncStatusSection (which
// itself mirrors this screen's Android Sync-now button). Reads only LiveState (connection + backfill +
// last-sync), so the ~1Hz HR hero doesn't drag it through re-renders. The button reaches the BLE engine's
// gated entry point (vm.syncNow → WhoopBleClient.syncNow) — a no-op when no strap is connected or a sync
// is already running, so it's safe regardless of state. The status line explains itself when no strap is
// connected; while a sync runs it shows the shared in-progress note + live chunk count; otherwise it
// shows when history last synced.

@Composable
private fun SyncStatusSection(vm: AppViewModel, onSyncNow: () -> Unit) {
    // PERF (#scroll-jank): collect the BLE live state HERE, inside the leaf, instead of receiving it
    // from the screen body. The live object identity changes ~1Hz with each HR tick; reading it at body
    // scope recomposed the whole Health screen. Scoping the collection to this section confines that
    // ~1Hz churn to the (cheap) sync card alone. The fields read below are slow-changing; only this
    // leaf re-runs per tick. Appearance + behaviour identical.
    val live by vm.live.collectAsStateWithLifecycle()
    // The strap link is usable for a manual offload kick (matches WhoopBleClient.syncNow's own gate).
    val canSync = live.connected && live.bonded && !live.backfilling
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.health_sync_title),
            overline = stringResource(R.string.health_sync_overline),
            trailing = if (live.connected) {
                if (live.bonded) stringResource(R.string.health_sync_connected)
                else stringResource(R.string.health_sync_pairing)
            } else stringResource(R.string.health_sync_offline),
        )

        NoopCard(tint = Palette.chargeColor) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Status line: an in-progress note while syncing (with the live chunk count), an honest
                // "not connected" pill, a last-synced read-out, else a "ready to sync"/"pairing" pill.
                when {
                    live.backfilling -> SyncingHistoryNote(chunks = live.syncChunksThisSession)
                    !live.connected -> StatePill(
                        title = stringResource(R.string.health_sync_no_strap),
                        tone = StrandTone.Neutral,
                        showsDot = false,
                    )
                    live.lastSyncAt != null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                    ) {
                        StatePill(title = stringResource(R.string.health_sync_history_synced), tone = StrandTone.Positive)
                        Text(
                            relativeAgo(live.lastSyncAt!!),
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                        )
                    }
                    else -> StatePill(
                        title = if (live.bonded) stringResource(R.string.health_sync_ready)
                        else stringResource(R.string.health_sync_pairing),
                        tone = StrandTone.Accent,
                        showsDot = true,
                        pulsing = !live.bonded,
                    )
                }

                // "Sync now" — routed through the unified NoopButton (Secondary, full-width) so the label
                // sits centred at the standard control height like every other primary control, matching
                // HealthView.swift's `NoopButton(..., kind: .secondary, fullWidth: true)`. Disabled unless
                // connected+bonded and not already syncing; the gated BLE entry point is a safe no-op
                // otherwise. (Total pending records are unknowable from the protocol, so no progress %.)
                // Hoisted above the semantics{} lambda — stringResource is @Composable-only.
                val syncCd = if (canSync) {
                    stringResource(R.string.health_sync_now_cd_ready)
                } else if (live.backfilling) {
                    stringResource(R.string.health_sync_now_cd_in_progress)
                } else {
                    stringResource(R.string.health_sync_now_cd_connect)
                }
                NoopButton(
                    text = if (live.backfilling) stringResource(R.string.health_sync_syncing)
                    else stringResource(R.string.health_sync_now),
                    leadingIcon = Icons.Filled.Sync,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    enabled = canSync,
                    modifier = Modifier.semantics {
                        contentDescription = syncCd
                    },
                    onClick = onSyncNow,
                )

                Text(
                    syncHelperText(live),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** The helper line below the Sync-now button: explains the current state (syncing / offline / pairing /
 *  ready), copy-matched to HealthView.swift's SyncStatusSection.helperText. */
@Composable
private fun syncHelperText(live: LiveState): String = when {
    live.backfilling -> stringResource(R.string.health_sync_helper_syncing)
    !live.connected -> stringResource(R.string.health_sync_helper_offline)
    !live.bonded -> stringResource(R.string.health_sync_helper_pairing)
    else -> stringResource(R.string.health_sync_helper_ready)
}

// MARK: - Records & sources (Swift parity) — discoverable deep-links into the on-device records
//
// Mirrors the Swift Health screen's "Records & sources" section: two clickable rows that route into
// the Lab Book (your own bloods / BP / body numbers) and the fused multi-source record ("Your Data,
// Fused"). Both live entirely on this phone, so the overline says so. Plain navigation rows in the
// house NoopCard style with an icon, a title/subtitle and a trailing chevron, each carrying a single
// combined contentDescription for screen readers.

@Composable
private fun RecordsAndSourcesSection(
    onOpenLabBook: () -> Unit,
    onOpenFusedRecord: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.health_records_title),
            overline = stringResource(R.string.health_records_overline),
        )
        RecordRow(
            icon = Icons.Filled.MenuBook,
            tint = Palette.metricCyan,
            title = stringResource(R.string.health_records_lab_book_title),
            subtitle = stringResource(R.string.health_records_lab_book_subtitle),
            onClick = onOpenLabBook,
        )
        RecordRow(
            icon = Icons.AutoMirrored.Filled.CompareArrows,
            tint = Palette.accent,
            title = stringResource(R.string.health_records_fused_title),
            subtitle = stringResource(R.string.health_records_fused_subtitle),
            onClick = onOpenFusedRecord,
        )
    }
}

/** One navigation row in the Records & sources section: a tinted glyph, a title + subtitle, and a
 *  trailing chevron, wrapped in a clickable NoopCard with a combined accessibility label. */
@Composable
private fun RecordRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val rowCd = stringResource(R.string.health_record_acc, title, subtitle)
    NoopCard(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = rowCd },
        padding = Metrics.space16,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(Metrics.cornerSm))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
                Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// MARK: - Skin-temperature suite (v5 pillar) — a Health section
//
// Composes the locked SkinTempCardsScreen cards from the engine RESULTS the ViewModel publishes:
//   • Cycle awareness — OPT-IN (default OFF). Shows the opt-in card until enabled, then the result card.
//   • Body clock — rendered only when the engine returned a phase estimate (the activity-bin input pipe
//     is a future source; until then it's silently absent rather than a faked card).
//   • Illness heads-up — rendered only when the engine returned a non-quiet level, mirroring the existing
//     amber-alert treatment; never a diagnosis.
// Every card carries its own privacy + non-clinical copy; the section header keeps the umbrella framing.

@Composable
private fun SkinTempSuiteSection(
    signals: V5HealthSignals.Snapshot?,
    cycleEnabled: Boolean,
    onEnableCycle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.health_skin_temp_title),
            overline = stringResource(R.string.health_skin_temp_overline),
        )

        // Illness heads-up first when it has something to say (it's the most time-sensitive card).
        signals?.illness?.let { illness ->
            if (illness.level != IllnessSignalEngine.Level.QUIET) {
                HeadsUpCard(result = illness)
            }
        }

        // Cycle awareness: the opt-in card until enabled, then the live result.
        if (!cycleEnabled) {
            CycleAwarenessOptInCard(onEnable = onEnableCycle)
        } else {
            signals?.cycle?.let { CycleAwarenessCard(result = it) }
        }

        // Body clock: only when the engine produced an estimate (no faked card while the input pipe is empty).
        signals?.bodyClock?.let { BodyClockCard(estimate = it) }

        Text(
            stringResource(R.string.health_cycle_phase_body_clock_and_illness),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
    }
}

// MARK: - Contributors (README screen #5) — labelled progress bars on the health detail
//
// "CONTRIBUTORS" — the signals that drive recovery (HRV / Resting HR / Sleep / Respiratory), each as a
// labelled progress bar in the shared stage/zone bar style (inset track, round-capped metric-hue fill,
// right-aligned read-out). Per the Titanium & Gold recovery detail, HRV + Resting HR read on the gold
// recovery world and Sleep + Respiratory on the blue sleep world. A SOLID/CALIBRATING pill states data
// confidence. Fractions are presentation-only normalisations of today's row to typical adult spans —
// no scoring change. Mirrors the Today RecoveryContributorsSection so the two screens read identically.

@Composable
private fun HealthContributorsSection(day: DailyMetric?) {
    val hrv = day?.avgHrv
    val rhr = day?.restingHr?.toDouble()
    val sleepMin = day?.totalSleepMin
    val resp = day?.respRateBpm
    if (hrv == null && rhr == null && sleepMin == null && resp == null) return

    // SOLID once recovery has been scored from these signals; CALIBRATING while the baseline seeds.
    val solid = day?.recovery != null
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader(
                    stringResource(R.string.health_contributors_title),
                    overline = stringResource(R.string.health_contributors_overline),
                )
            }
            StatePill(
                title = if (solid) stringResource(R.string.health_pill_solid)
                else stringResource(R.string.health_pill_calibrating),
                tone = if (solid) StrandTone.Accent else StrandTone.Neutral,
            )
        }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                // Recovery-world tints, matched to HealthView.swift's RecoveryContributorsSection (NO gold):
                // HRV = teal (metricCyan), Resting HR = WHOOP green (chargeColor, the recovery contributor
                // hue), Sleep + Respiratory share the blue sleep world (sleepLight). Each bar reveals with
                // a staggered fade+rise, mirroring iOS `.staggeredAppear(index:)`.
                ContributorBar(
                    label = stringResource(R.string.health_contributor_hrv),
                    readout = hrv?.let { stringResource(R.string.health_contributor_value_ms, it.roundToInt()) } ?: "—",
                    fraction = hrv?.let { (it - 20.0) / 100.0 },
                    color = Palette.metricCyan,
                    modifier = Modifier.staggeredAppear(0),
                )
                ContributorBar(
                    label = stringResource(R.string.health_contributor_resting_hr),
                    readout = rhr?.let { stringResource(R.string.health_contributor_value_bpm, it.roundToInt()) } ?: "—",
                    fraction = rhr?.let { 1.0 - ((it - 40.0) / 40.0) },
                    color = Palette.chargeColor,
                    modifier = Modifier.staggeredAppear(1),
                )
                ContributorBar(
                    label = stringResource(R.string.health_contributor_sleep),
                    readout = sleepMin?.let { sleepHoursText(it) } ?: "—",
                    fraction = sleepMin?.let { (it / 60.0) / 8.0 },
                    color = Palette.sleepLight,
                    modifier = Modifier.staggeredAppear(2),
                )
                ContributorBar(
                    label = stringResource(R.string.health_contributor_respiratory),
                    readout = resp?.let {
                        stringResource(R.string.health_contributor_value_rpm, String.format(Locale.US, "%.1f", it))
                    } ?: "—",
                    fraction = resp?.let { 1.0 - ((it - 12.0) / 8.0) },
                    color = Palette.sleepLight,
                    modifier = Modifier.staggeredAppear(3),
                )
                Text(
                    stringResource(R.string.health_contributors_footnote),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** "Hh Mm" for sleep minutes, matching the Today Rest read-out. */
@Composable
private fun sleepHoursText(totalMin: Double): String {
    val t = totalMin.roundToInt()
    return stringResource(R.string.health_sleep_hours, t / 60, t % 60)
}

/** One labelled contributor bar: a label + right-aligned read-out over the NOOP signature segmented
 *  [PipBar] (metric-hue pips that cascade up to the strength on appear/change), mirroring
 *  HealthView.swift's `ContributorBar` / `PipBar(value:tint:)`. A null fraction renders an empty
 *  (calibrating) bar — no fabricated fill. */
@Composable
private fun ContributorBar(
    label: String,
    readout: String,
    fraction: Double?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // PipBar takes a 0…100 value; map the presentation fraction up onto that span (null → empty bar).
    val strength = fraction?.coerceIn(0.0, 1.0)?.let { (it * 100.0).toFloat() } ?: 0f
    val barCd = stringResource(R.string.health_contributor_acc, label, readout)
    Column(
        modifier = modifier.semantics { contentDescription = barCd },
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(label, modifier = Modifier.weight(1f))
            Text(readout, style = NoopType.captionNumber, color = Palette.textPrimary)
        }
        PipBar(value = strength, tint = color)
    }
}

// MARK: - Fitness Age
//
// The on-device "Fitness Age": a weekly number (the engine keys it to each week's Saturday) that maps
// resting HR + recent activity against population norms for your age. The AUTHORITATIVE value is the
// latest "fitness_age" the IntelligenceEngine writes into metricSeries under the computed "-noop"
// source — this section only READS it; it never recomputes the headline. Honest framing throughout:
// it's a fitness comparison (± 5 yr band), never a biological age, and weight/height/waist live under
// "Unlocks your VO₂max", never as if they sharpen the age. When no value exists yet we show the
// readiness checklist instead, so the user knows exactly what's still needed.

/** The computed ("-noop") source the IntelligenceEngine persists fitness_age + vo2max_est under, the
 *  same convention every screen uses for on-device-computed series (imported is plain "my-whoop"). */
private const val COMPUTED_SOURCE = "my-whoop-noop"

@Composable
private fun FitnessAgeSection(vm: AppViewModel, days: List<DailyMetric>, profile: ProfileStore) {
    // Latest weekly value + its optional VO₂max companion, read once (metricSeries has no Flow, so we
    // re-read whenever the merged history changes — a fresh sync/import is what moves these).
    var fitnessAge by remember { mutableStateOf<Double?>(null) }
    var vo2max by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days) {
        val fa = runCatching {
            vm.repo.metricSeries(COMPUTED_SOURCE, "fitness_age", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList()).lastOrNull()?.value
        val vo2 = runCatching {
            vm.repo.metricSeries(COMPUTED_SOURCE, "vo2max_est", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList()).lastOrNull()?.value
        fitnessAge = fa
        vo2max = vo2
    }

    // Readiness from what THIS screen can see: the last 7 merged daily rows. RHR coverage drives the
    // age; activity (a scored strain day) is an enrichment signal; height/weight/waist sit under the
    // VO₂max role. Age/sex come from the profile. Approximate by design — the weekly value is the
    // authority; this just explains the gaps.
    val readiness = remember(days, profile.age, profile.sex, profile.waistCm) {
        val last7 = days.takeLast(7)
        val rhrDays = last7.count { it.restingHr != null }
        val activityDays = last7.count { it.strain != null }
        FitnessAgeEngine.assessReadiness(
            hasAge = profile.age > 0,
            hasSex = profile.sex.isNotBlank(),
            rhrDays = rhrDays,
            activityDays = activityDays,
            hasHeightWeight = profile.heightCm > 0 && profile.weightKg > 0,
            hasWaist = profile.waistCm > 0,
        )
    }

    var showChecklist by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.health_fitness_age_title),
            overline = stringResource(R.string.health_section_overline_weekly),
            trailing = stringResource(R.string.health_fitness_age_band),
        )
        val value = fitnessAge
        if (value != null) {
            FitnessAgeHero(
                fitnessAge = value,
                chronoAge = profile.age,
                vo2max = vo2max,
                onHowAccurate = { showChecklist = !showChecklist },
                checklistOpen = showChecklist,
            )
            if (showChecklist) {
                FitnessReadinessCard(readiness = readiness, headed = false)
            }
        } else {
            // No weekly value yet — surface the checklist directly so the user knows what's pending.
            FitnessReadinessCard(readiness = readiness, headed = true)
        }
    }
}

/** Vitality / Body Age: a weekly 0–100 wellness score + Body Age in years, computed by
 *  IntelligenceEngine from the mortality-hazard model and read from metricSeries. A wellness trend
 *  from your habits — NOT a clinical biological age. Recomputes the live best/worst factor for the why. */
@Composable
private fun VitalitySection(vm: AppViewModel, days: List<DailyMetric>, profile: ProfileStore) {
    var vitality by remember { mutableStateOf<Double?>(null) }
    var bodyAge by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days) {
        vitality = runCatching {
            vm.repo.metricSeries(COMPUTED_SOURCE, "vitality", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList()).lastOrNull()?.value
        bodyAge = runCatching {
            vm.repo.metricSeries(COMPUTED_SOURCE, "body_age", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList()).lastOrNull()?.value
    }
    val contributions = remember(days, profile.age) {
        val last7 = days.takeLast(7)
        val nights = last7.mapNotNull { it.totalSleepMin }.map { it / 60.0 }.filter { it > 0 }
        val hrvs = last7.mapNotNull { it.avgHrv }
        val rhrs = last7.mapNotNull { it.restingHr }.map { it.toDouble() }
        val steps = last7.mapNotNull { it.steps }.map { it.toDouble() }
        fun mean(a: List<Double>): Double? = if (a.isEmpty()) null else a.average()
        // Match the STORED headline's aggregation (IntelligenceEngine.medianOfDoubles): median resting HR +
        // HRV (robust to one outlier night), mean sleep + steps — so this "what's driving it" breakdown
        // reconciles with the Vitality / Body Age number it explains rather than drifting on the mean (review).
        fun median(a: List<Double>): Double? {
            if (a.isEmpty()) return null
            val s = a.sorted(); val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
        VitalityEngine.contributions(VitalityEngine.Inputs(
            chronoAge = profile.age.toDouble(), restingHR = median(rhrs), sleepHours = mean(nights),
            sleepConsistency = VitalityEngine.sleepConsistency(nights),
            rmssd = median(hrvs), rmssdNorm = VitalityEngine.rmssdNorm(profile.age.toDouble()), steps = mean(steps)))
    }
    val v = vitality; val ba = bodyAge
    if (v != null && ba != null) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(
                stringResource(R.string.health_vitality_overline),
                overline = stringResource(R.string.health_section_overline_weekly),
                trailing = stringResource(R.string.health_vitality_body_age_trailing, ba.roundToInt()),
            )
            VitalityHero(vitality = v, bodyAge = ba, chronoAge = profile.age, contributions = contributions)
        }
    }
}

@Composable
private fun VitalityHero(
    vitality: Double, bodyAge: Double, chronoAge: Int,
    contributions: List<VitalityEngine.Contribution>,
) {
    val delta = chronoAge - bodyAge.roundToInt()
    val younger = bodyAge < chronoAge
    val sorted = contributions.sortedBy { it.lnHazard }
    val best = sorted.firstOrNull()
    val worst = sorted.lastOrNull()
    NoopCard(tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(stringResource(R.string.health_vitality_overline))
                    // WHITE (textPrimary) headline that ticks up — matches HealthView.swift's reset: the
                    // synthesis number is neutral, not the charge/recovery hue.
                    CountUpText(
                        value = vitality,
                        format = { it.roundToInt().toString() },
                        style = NoopType.display(56f),
                        color = Palette.textPrimary,
                    )
                    Text(stringResource(R.string.health_vitality_out_of_100), style = NoopType.footnote, color = Palette.textTertiary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Overline(stringResource(R.string.health_vitality_body_age))
                    CountUpText(
                        value = bodyAge,
                        format = { it.roundToInt().toString() },
                        style = NoopType.number(34f),
                        color = Palette.textPrimary,
                    )
                    Text(
                        if (delta == 0) stringResource(R.string.health_vitality_about_your_age)
                        else if (younger) stringResource(R.string.health_body_age_years_younger, kotlin.math.abs(delta))
                        else stringResource(R.string.health_body_age_years_older, kotlin.math.abs(delta)),
                        style = NoopType.footnote,
                        color = if (delta == 0) Palette.textSecondary
                        else if (younger) Palette.statusPositive else Palette.statusWarning,
                    )
                }
            }
            if (best != null && best.lnHazard < 0) {
                Text(stringResource(R.string.health_vitality_helping_most, best.label), style = NoopType.footnote, color = Palette.statusPositive)
            }
            if (worst != null && worst.lnHazard > 0) {
                Text(stringResource(R.string.health_vitality_holding_back, worst.label), style = NoopType.footnote, color = Palette.statusWarning)
            }
            Text(
                stringResource(R.string.health_vitality_footnote),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

/** The hero tile: a big Fitness Age number on the gold Charge world, the younger/older read-out, an
 *  optional VO₂max chip, the honest ± band caption, and a "How accurate is this?" toggle. */
@Composable
private fun FitnessAgeHero(
    fitnessAge: Double,
    chronoAge: Int,
    vo2max: Double?,
    onHowAccurate: () -> Unit,
    checklistOpen: Boolean,
) {
    val shown = fitnessAge.roundToInt()
    // Delta vs the user's actual age: younger when the fitness age is below it. abs() drives the words.
    val deltaYears = (chronoAge - fitnessAge).roundToInt()
    val younger = fitnessAge < chronoAge
    val deltaWord = when {
        deltaYears == 0 -> stringResource(R.string.health_fitness_age_about_your_age)
        younger -> stringResource(R.string.health_fitness_age_years_younger, deltaYears)
        else -> stringResource(R.string.health_fitness_age_years_older, kotlin.math.abs(deltaYears))
    }

    NoopCard(tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(stringResource(R.string.health_fitness_age_title))
                    // The hero age is WHITE (textPrimary) and ticks up on appear / weekly refresh — the key
                    // iOS reset: the headline number is the neutral synthesis colour, NOT the recovery/charge
                    // hue. Mirrors HealthView.swift's `CountUpText(..., color: textPrimary)`.
                    CountUpText(
                        value = shown.toDouble(),
                        format = { it.roundToInt().toString() },
                        style = NoopType.display(56f),
                        color = Palette.textPrimary,
                    )
                    Text(
                        text = deltaWord,
                        style = NoopType.subhead,
                        color = if (deltaYears == 0) Palette.textSecondary
                        else if (younger) Palette.statusPositive else Palette.statusWarning,
                    )
                }
                if (vo2max != null) {
                    StatePill(
                        title = stringResource(R.string.health_fitness_age_vo2max_pill, vo2max.roundToInt()),
                        tone = StrandTone.Accent,
                        showsDot = false,
                    )
                }
            }

            Text(
                text = stringResource(R.string.health_fitness_age_caption),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )

            // "How accurate is this?" affordance — toggles the readiness checklist below the hero.
            val howAccurateCd = stringResource(R.string.health_fitness_age_how_accurate_cd)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Metrics.cornerSm))
                    .clickable(onClick = onHowAccurate)
                    .padding(vertical = Metrics.space4)
                    .semantics { contentDescription = howAccurateCd },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                Text(
                    stringResource(R.string.health_fitness_age_how_accurate),
                    style = NoopType.captionNumber,
                    color = Palette.accent,
                )
                Text(
                    if (checklistOpen) "▾" else "›",
                    style = NoopType.captionNumber,
                    color = Palette.accent,
                )
            }
        }
    }
}

/** The readiness checklist card: each input as a ✓ / ⚠ / ○ glyph + its detail, grouped by role into
 *  "Drives your Fitness Age" and "Unlocks your VO₂max". When [headed] (no value yet) it leads with a
 *  "a few more days" heading and floats the required-missing items to the top of their group. */
@Composable
private fun FitnessReadinessCard(readiness: FitnessAgeReadiness, headed: Boolean) {
    val drivesAge = readiness.items
        .filter { it.role == FitnessReadinessRole.DRIVES_AGE }
        .sortedBy { if (headed) readinessSortKey(it) else 0 }
    val unlocksVo2 = readiness.items
        .filter { it.role == FitnessReadinessRole.UNLOCKS_VO2MAX }
        .sortedBy { if (headed) readinessSortKey(it) else 0 }

    NoopCard(tint = if (headed) Palette.chargeColor else null) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            if (headed) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Text(
                        stringResource(R.string.health_readiness_few_more_days),
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                    )
                    Text(
                        stringResource(R.string.health_readiness_few_more_days_body),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }

            ReadinessGroup(title = stringResource(R.string.health_readiness_group_drives_age), items = drivesAge)
            ReadinessGroup(title = stringResource(R.string.health_readiness_group_unlocks_vo2max), items = unlocksVo2)

            Text(
                stringResource(R.string.health_readiness_footnote),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** Sort key for the headed (no-value-yet) state: required-missing first, then partial, then the rest. */
private fun readinessSortKey(item: FitnessReadinessItem): Int = when {
    item.required && item.status == FitnessReadinessStatus.MISSING -> 0
    item.status == FitnessReadinessStatus.MISSING -> 1
    item.status == FitnessReadinessStatus.PARTIAL -> 2
    else -> 3
}

@Composable
private fun ReadinessGroup(title: String, items: List<FitnessReadinessItem>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Overline(title)
        items.forEach { ReadinessRow(it) }
    }
}

@Composable
private fun ReadinessRow(item: FitnessReadinessItem) {
    val glyph = when (item.status) {
        FitnessReadinessStatus.SATISFIED -> "✓"
        FitnessReadinessStatus.PARTIAL -> "⚠"
        FitnessReadinessStatus.MISSING -> "○"
    }
    val glyphColor = when (item.status) {
        FitnessReadinessStatus.SATISFIED -> Palette.chargeColor
        FitnessReadinessStatus.PARTIAL -> Palette.statusWarning
        FitnessReadinessStatus.MISSING -> Palette.textTertiary
    }
    val rowCd = stringResource(R.string.health_readiness_acc, item.label, item.detail)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = rowCd },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        Text(
            glyph,
            style = NoopType.captionNumber,
            color = glyphColor,
            modifier = Modifier.width(16.dp),
        )
        Text(
            item.label,
            style = NoopType.subhead,
            color = Palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            item.detail,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun VitalSignsScreen(vm: AppViewModel, onVitalClick: (String) -> Unit = {}) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var selectedDayOffset by remember { mutableIntStateOf(0) }
    val selectedDay = remember(selectedDayOffset) { LocalDate.now().minusDays(selectedDayOffset.toLong()) }
    val selectedDayKey = remember(selectedDay) { selectedDay.toString() }
    val selectedMetric = remember(days, selectedDayKey) { days.lastOrNull { it.day == selectedDayKey } }
    val tempUnit = UnitPrefs.temperature(LocalContext.current)
    val vitals = remember(selectedMetric, days, tempUnit) {
        selectedMetric?.let { vitalsFor(it, days, tempUnit) }.orEmpty()
    }

    ScreenScaffold(
        title = stringResource(R.string.health_vitals_title),
        subtitle = stringResource(R.string.health_vitalsigns_subtitle),
    ) {
        RecentDaySelectorBar(selectedOffset = selectedDayOffset, onSelect = { selectedDayOffset = it })
        if (selectedMetric == null || vitals.all { it.value == null }) {
            DataPendingNote(
                title = missingVitalsTitle(selectedDayOffset),
                body = stringResource(R.string.health_vitalsigns_pending_body),
            )
        } else {
            VitalsSection(
                title = stringResource(R.string.health_vitals_title),
                overline = selectedDayLabel(selectedDayOffset),
                trailing = stringResource(R.string.health_asof_date, selectedMetric.day),
                vitals = vitals,
                onVitalClick = onVitalClick,
                footer = false,
                captionMode = VitalCaptionMode.RANGE,
            )
        }
    }
}

// MARK: - Derived live HR
//
// HR to display: the reported value when > 0, else derived from the latest R-R
// interval in milliseconds (the strap streams R-R even when its HR field reads 0).

private fun displayHr(bpm: Int?, live: LiveState): Int? {
    // #39: prefer the spike-filtered median (AppViewModel.bpm) over raw live.heartRate, which carries
    // PPG harmonic spikes (real ~92 read as 170+). Raw / R-R are last-resort fallbacks.
    if (bpm != null && bpm > 0) return bpm
    live.heartRate?.let { if (it > 0) return it }
    val lastRr = live.rr.lastOrNull()
    if (lastRr != null && lastRr > 0) return (60_000.0 / lastRr).roundToInt()
    return null
}

private fun hrIsDerived(live: LiveState): Boolean =
    (live.heartRate ?: 0) <= 0 && live.rr.isNotEmpty()

/** HR as a fraction of HR-max (0..1). */
private fun hrFraction(hr: Int?, hrMax: Int): Double {
    if (hr == null || hrMax <= 0) return 0.0
    return (hr.toDouble() / hrMax).coerceIn(0.0, 1.0)
}

/** Current zone 1..5 from %HR-max (WHOOP/Karvonen-style bands: 50/60/70/80/90). */
private fun hrZone(fraction: Double): Int = when {
    fraction < 0.60 -> 1
    fraction < 0.70 -> 2
    fraction < 0.80 -> 3
    fraction < 0.90 -> 4
    else -> 5
}

/** One streamed live-HR reading with the wall-clock time it arrived (epoch millis). Carrying the
 *  time — not a bare bpm — is what lets the hero render a real time x-axis (#198). */
data class LiveHrSample(val timeMs: Long, val bpm: Double)

/** A short, time-stamped HR series for the hero chart. Prefers the accumulated live-HR history
 *  (which moves over time); falls back to per-beat HR from R-R, then to a flat pair while the
 *  buffer fills. The old version derived ONLY from R-R, which is sparse on WHOOP 4, so it sat on a
 *  flat 2-point line even while HR was clearly changing (issue #18). The R-R / flat fallbacks have
 *  no real per-sample timestamps, so we synthesise a 1 Hz trailing window ending "now" — the x-axis
 *  still reads as clock time and scrolls, matching the live buffer (#198). */
private fun hrSeries(history: List<LiveHrSample>, live: LiveState, hr: Int?): List<LiveHrSample> {
    if (history.size > 1) return history
    val beats = live.rr.takeLast(60).mapNotNull { rr ->
        if (rr > 0) 60_000.0 / rr else null
    }
    if (beats.size > 1) return synthesiseSeries(beats)
    if (hr != null) return synthesiseSeries(listOf(hr.toDouble(), hr.toDouble()))
    return emptyList()
}

/** Wrap a bare value series in trailing 1 Hz timestamps ending "now", so the fallbacks chart on the
 *  same time x-axis as the live buffer. */
private fun synthesiseSeries(values: List<Double>): List<LiveHrSample> {
    val now = System.currentTimeMillis()
    val n = values.size
    return values.mapIndexed { i, v ->
        LiveHrSample(timeMs = now + (i - (n - 1)) * 1000L, bpm = v)
    }
}

// MARK: - Heart rate hero (live)

@Composable
private fun HeartRateSection(vm: AppViewModel, hrMax: Int) {
    // PERF (#scroll-jank): collect the BLE live state + smoothed bpm HERE, in the HR hero leaf, instead
    // of receiving them from the screen body. Both tick ~1Hz; reading them at body scope recomposed the
    // whole Health screen on every heartbeat. Scoping the collection to this section confines the ~1Hz
    // re-render to the HR hero alone — the rest of the screen no longer recomposes per beat. Mirrors the
    // shipped Today fix (HeartRateTrendCard scopes its own collection). Appearance + behaviour identical.
    val live by vm.live.collectAsStateWithLifecycle()
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val displayHr = displayHr(bpm, live)
    val hasLiveHr = displayHr != null
    val derived = hrIsDerived(live)
    val fraction = hrFraction(displayHr, hrMax)
    val zone = hrZone(fraction)
    // Accumulate the streamed HR over time so the hero chart actually moves (issue #18 — it used to
    // derive from sparse R-R and flat-line). Each sample now carries its arrival time so the hero can
    // render a real time x-axis (#198). Lives in UI state; resets when you leave the screen.
    val hrHistory = remember { mutableStateListOf<LiveHrSample>() }
    LaunchedEffect(displayHr) {
        displayHr?.let { if (it in 30..220) {
            hrHistory.add(LiveHrSample(timeMs = System.currentTimeMillis(), bpm = it.toDouble()))
            if (hrHistory.size > 180) hrHistory.removeAt(0)
        } }
    }
    val series = hrSeries(hrHistory, live, displayHr)
    val zoneColor = Palette.hrZoneColor(zone)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            title = stringResource(R.string.health_heart_rate_title),
            overline = stringResource(R.string.health_heart_rate_overline_live),
            trailing = if (derived) stringResource(R.string.health_heart_rate_from_rr) else null,
        )

        // The live HR hero is Apple-flat — a plain card tinted rose (heart-rate's metric accent) over a
        // SUBTLE time-of-day backdrop, NOT a scenic starfield/bloom. Mirrors HealthView.swift's reset:
        // "No scenic starfield / bloom: fill contrast carries the edge (Apple-flat)."
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Metrics.cardRadius))
                .timeOfDayBackground(),
        ) {
            NoopCard(padding = Metrics.space18, tint = Palette.metricRose) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Card header: title + subtitle on the left, live bpm read-out right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.health_heart_rate_title), style = NoopType.headline, color = Palette.textPrimary)
                        Text(
                            text = when {
                                derived -> stringResource(R.string.health_heart_rate_estimated_rr)
                                hasLiveHr -> stringResource(R.string.health_heart_rate_streaming_live)
                                else -> stringResource(R.string.health_heart_rate_awaiting_strap)
                            },
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                        )
                    }
                    Text(
                        text = if (hasLiveHr) stringResource(R.string.health_hr_bpm_value, displayHr!!) else "—",
                        style = NoopType.metricInline,
                        color = if (hasLiveHr) zoneColor else Palette.textTertiary,
                    )
                }

                // Hero chart: a tall HR line tinted to the current zone, with a status
                // pill floated top-trailing. Falls back to a big number when R-R is sparse.
                val chartCd = if (hasLiveHr) {
                    stringResource(R.string.health_hr_chart_cd, displayHr!!, zone)
                } else {
                    stringResource(R.string.health_hr_chart_cd_no_data)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Metrics.chartHeight)
                        .semantics {
                            contentDescription = chartCd
                        },
                ) {
                    if (series.size > 1) {
                        LiveHrTimeChart(
                            samples = series,
                            color = zoneColor,
                            modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // The big fallback numeral ticks up to the live value (mirrors HealthView.swift's
                            // CountUpText); a crisp em-dash when there's no HR yet.
                            if (displayHr != null) {
                                CountUpText(
                                    value = displayHr.toDouble(),
                                    format = { it.roundToInt().toString() },
                                    style = NoopType.display(72f),
                                    color = zoneColor,
                                )
                            } else {
                                Text(
                                    text = "—",
                                    style = NoopType.display(72f),
                                    color = Palette.textTertiary,
                                )
                            }
                            Text(stringResource(R.string.liveworkout_bpm), style = NoopType.subhead, color = Palette.textTertiary)
                        }
                    }

                    StatePill(
                        title = zoneLabel(hasLiveHr, zone, fraction),
                        tone = if (hasLiveHr) StrandTone.Accent else StrandTone.Neutral,
                        showsDot = hasLiveHr,
                        pulsing = hasLiveHr,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }

                // Footer read-out row: Zone · % Max · Max HR · State.
                HeartRateFooter(
                    zone = if (hasLiveHr) stringResource(R.string.health_footer_zone_value, zone) else "—",
                    percentMax = if (hasLiveHr) stringResource(R.string.health_footer_percent_value, (fraction * 100).roundToInt()) else "—",
                    maxHr = "$hrMax",
                    state = if (hasLiveHr) stringResource(R.string.health_footer_state_streaming) else stringResource(R.string.health_footer_state_idle),
                )
            }
            }
        }
    }
}

@Composable
private fun zoneLabel(hasLiveHr: Boolean, zone: Int, fraction: Double): String {
    if (!hasLiveHr) return stringResource(R.string.health_hr_zone_idle)
    return stringResource(R.string.health_hr_zone_label, zone, (fraction * 100).roundToInt())
}

@Composable
private fun HeartRateFooter(zone: String, percentMax: String, maxHr: String, state: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = Metrics.space4)) {
        FooterStat(stringResource(R.string.health_footer_zone), zone, Modifier.weight(1f))
        FooterStat(stringResource(R.string.health_footer_percent_max), percentMax, Modifier.weight(1f))
        FooterStat(stringResource(R.string.health_footer_max_hr), maxHr, Modifier.weight(1f))
        FooterStat(stringResource(R.string.health_footer_state), state, Modifier.weight(1f))
    }
}

@Composable
private fun FooterStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Overline(label)
        Text(value, style = NoopType.captionNumber, color = Palette.textPrimary)
    }
}

// MARK: - Live HR time chart
//
// The live HR hero plotted over a real TIME x-axis (HH:mm:ss), so the trace visibly scrolls as new
// samples arrive (#198). Replaces the axis-less LineChart on this hero — a phone user has no hover,
// so the visible clock axis is the fix. A local Canvas chart (not the shared LineChart, which has no
// axis): x is time-proportional, y auto-fits with headroom, the zone colour drives line + soft fill.

private val liveHrAxisFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault())

@Composable
private fun LiveHrTimeChart(
    samples: List<LiveHrSample>,
    color: Color,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().clipToBounds()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (samples.size < 2 || size.width <= 0f || size.height <= 0f) {
                drawHrBaseline()
                return@Canvas
            }

            val strokePx = 2.5f
            val topPad = strokePx + 4f
            // Reserve a strip at the bottom for the time labels.
            val axisHeight = 26f
            val plotBottom = (size.height - axisHeight).coerceAtLeast(1f)
            val usableH = (plotBottom - topPad).coerceAtLeast(1f)

            val tMin = samples.first().timeMs
            val tMax = samples.last().timeMs
            val tSpan = (tMax - tMin).coerceAtLeast(1L)

            val values = samples.map { it.bpm }
            val vMin = values.min()
            val vMax = values.max()
            val vSpan = (vMax - vMin)
            // A little y-headroom so the trace never kisses the plot edges.
            val pad = if (vSpan > 0.0) vSpan * 0.12 else 5.0
            val lo = vMin - pad
            val hi = vMax + pad
            val span = (hi - lo).coerceAtLeast(0.0001)

            fun xFor(t: Long): Float = ((t - tMin).toFloat() / tSpan.toFloat()) * size.width
            fun yFor(v: Double): Float {
                val norm = ((v - lo) / span).toFloat()
                return topPad + (1f - norm) * usableH
            }

            val pts = samples.map { Offset(xFor(it.timeMs), yFor(it.bpm)) }

            // Soft gradient fill under the curve (down to the plot baseline, above the axis strip).
            val fillPath = Path().apply {
                moveTo(pts.first().x, plotBottom)
                lineTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                lineTo(pts.last().x, plotBottom)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = StrandAlpha.chartFillStrong),
                        color.copy(alpha = StrandAlpha.chartFillSoft),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = plotBottom,
                ),
            )

            // The line itself.
            val linePath = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Time x-axis: a faint baseline + evenly-spaced clock labels across the time span.
            drawLine(
                color = Palette.hairline.copy(alpha = 0.4f),
                start = Offset(0f, plotBottom),
                end = Offset(size.width, plotBottom),
                strokeWidth = 1f,
                cap = StrokeCap.Round,
            )
            val tickCount = 4
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = 24f
                    this.color = Palette.textTertiary.toArgb()
                }
                val baselineY = size.height - 6f
                for (i in 0 until tickCount) {
                    val frac = i.toFloat() / (tickCount - 1)
                    val t = tMin + (tSpan * frac).toLong()
                    val label = liveHrAxisFormatter.format(Instant.ofEpochMilli(t))
                    val labelWidth = paint.measureText(label)
                    // Keep the first/last labels inside the plot bounds.
                    val rawX = frac * size.width
                    val x = rawX.coerceIn(0f, (size.width - labelWidth).coerceAtLeast(0f))
                    drawText(label, x, baselineY, paint)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHrBaseline() {
    val y = size.height / 2f
    drawLine(
        color = Palette.hairline.copy(alpha = StrandAlpha.subtleLine),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
        cap = StrokeCap.Round,
    )
}

// MARK: - Vitals grid (uniform StatTiles)

@Composable
private fun VitalsSection(
    title: String,
    overline: String,
    trailing: String? = null,
    vitals: List<Vital>,
    onVitalClick: (String) -> Unit,
    footer: Boolean = true,
    captionMode: VitalCaptionMode = VitalCaptionMode.AS_OF,
) {
    // Temperature display preference (D#103). Skin temp is stored in °C; the toggle re-labels it to °F.
    // Display-only — banding still runs on the stored °C value.
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(title = title, overline = overline, trailing = trailing)

        // A uniform 2-column grid of fixed-height tiles. The macOS LazyVGrid is
        // adaptive(min: 168); on phones two columns is the faithful equivalent.
        vitals.chunked(2).forEach { rowVitals ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                rowVitals.forEach { v ->
                    val tileCd = vitalAccessibilityText(v)
                    val stateCaption = vitalStateCaption(v.banding)
                    val asOf = asOfLabel(v.readingDay)
                    val rangeCaption = v.rangeBounds?.let { (min, max, unit) ->
                        stringResource(R.string.health_range_within, min, max, unit)
                    }
                    VitalTile(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onVitalClick(v.key) }
                            .semantics { contentDescription = tileCd },
                        vital = v,
                        value = v.formattedValue ?: "—",
                        caption = when (captionMode) {
                            VitalCaptionMode.AS_OF -> asOf ?: stateCaption
                            VitalCaptionMode.RANGE -> rangeCaption ?: stateCaption
                        },
                        accent = v.accent,
                    )
                }
                // Pad an odd final row so the tile keeps half-width, matching the grid.
                if (rowVitals.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (footer) {
            Text(
                text = stringResource(R.string.health_vitals_footnote),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Vital model

private data class Vital(
    val key: String,
    val label: String,
    val unit: String,
    val value: Double?,
    val format: (Double) -> String,
    val deltaText: String? = null,
    /** The reading's day key; the "as of …" label is resolved (and localized) at render time. */
    val readingDay: String? = null,
    /** Pre-formatted (min, max, unit) for the "within … – … unit" caption; the wrapper text is
     *  localized at render time via health_range_within. Number formatting stays Locale.US here. */
    val rangeBounds: Triple<String, String, String>? = null,
    /** Personal-baseline banding (population fallback until 14 trusted nights). */
    val banding: VitalBands.Result,
    /** The metric's category colour (used only when in range). */
    val metricColor: Color,
    /** Trailing values (oldest → newest) for the tile's metric-tinted sparkline trail, matching
     *  Today's Key-Metrics tiles. Presentation-only; defaulted so existing call sites compile. */
    val sparkline: List<Double> = emptyList(),
) {
    /** Value with its unit appended, or null when no data. */
    val formattedValue: String? = value?.let { "${format(it)} $unit" }

    /** Colour communicates state: in-range = the metric's category colour,
     *  out-of-range = warning amber, no data = tertiary. */
    val accent: Color = when (banding.band) {
        VitalBands.Band.NO_DATA -> Palette.textTertiary
        VitalBands.Band.IN_RANGE -> metricColor
        VitalBands.Band.OUT_OF_RANGE -> Palette.statusWarning
    }

}

private enum class VitalCaptionMode {
    AS_OF,
    RANGE,
}

// MARK: - Vital display resolvers (localized presentation; English specs above stay canonical)
//
// The Vital model is built off the UI thread (vitalsFor / latestVitals / buildVitalDetail are not
// @Composable), so its label / state-caption / a11y text are kept English-canonical for logic and
// mirror the existing vitalLabel/metricTitle convention: a @Composable resolver keyed on the metric
// key/banding turns them into the localized strings at render time.

/** Localized tile label for a vital, keyed on its logic key (English label stays canonical). */
@Composable
private fun vitalLabel(key: String): String = when (key) {
    "resp" -> stringResource(R.string.health_label_resp)
    "spo2" -> stringResource(R.string.health_label_spo2)
    "rhr" -> stringResource(R.string.health_contributor_resting_hr)
    "hrv" -> stringResource(R.string.health_contributor_hrv)
    "skin" -> stringResource(R.string.health_label_skin)
    else -> key
}

/** Localized in-range / out-of-range caption, keyed on the banding result. */
@Composable
private fun vitalStateCaption(banding: VitalBands.Result): String = when {
    banding.band == VitalBands.Band.NO_DATA -> stringResource(R.string.health_state_no_data)
    banding.basis == VitalBands.Basis.PERSONAL ->
        if (banding.band == VitalBands.Band.IN_RANGE) stringResource(R.string.health_state_in_your_range)
        else stringResource(R.string.health_state_off_baseline)
    else ->
        if (banding.band == VitalBands.Band.IN_RANGE) stringResource(R.string.health_state_in_typical)
        else stringResource(R.string.health_state_outside_typical)
}

/** Localized combined accessibility text for a vital tile, mirroring Vital.accessibilityText. */
@Composable
private fun vitalAccessibilityText(v: Vital): String {
    val label = vitalLabel(v.key)
    val value = v.formattedValue
    return if (value != null) {
        listOfNotNull(
            stringResource(R.string.health_vital_acc, label, value),
            asOfLabel(v.readingDay),
            vitalStateCaption(v.banding),
        ).joinToString(", ")
    } else {
        stringResource(R.string.health_acc_no_data, label)
    }
}

/** Build the vitals, banded against the user's OWN trailing baseline once 14 trusted
 *  nights exist (population ranges before that — VitalBands does the deciding). */
private fun vitalsFor(
    d: DailyMetric?,
    days: List<DailyMetric>,
    tempUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
): List<Vital> {
    val todayKey = d?.day
    // History strictly before the displayed day, oldest→newest (recentDays is already
    // oldest→newest); calendar-padded so wear gaps count as missing nights (a stale
    // baseline then falls back to the population range).
    val history = days.filter { row -> todayKey == null || row.day < todayKey }
    fun series(selector: (DailyMetric) -> Double?): List<Double?> =
        VitalBands.calendarSeries(history.map { it.day to selector(it) })
    fun previous(selector: (DailyMetric) -> Double?): Double? =
        history.asReversed().asSequence().mapNotNull(selector).firstOrNull()
    fun deltaText(current: Double?, previous: Double?, decimals: Int = 1): String? {
        if (current == null || previous == null) return null
        val diff = current - previous
        val sign = if (diff >= 0.0) "+" else "-"
        val mag = kotlin.math.abs(diff)
        val num = if (decimals == 0) mag.roundToInt().toString()
        else String.format(Locale.US, "%.${decimals}f", mag)
        return "($sign$num)"
    }
    fun rangeBounds(allValues: List<Double>, unit: String, format: (Double) -> String): Triple<String, String, String>? {
        val min = allValues.minOrNull() ?: return null
        val max = allValues.maxOrNull() ?: return null
        return Triple(format(min), format(max), unit)
    }
    // Trailing values (oldest → newest) feeding each tile's sparkline trail. Built from the same
    // history already gathered for banding, including the displayed day's value. Presentation-only.
    fun trail(current: Double?, window: Int = 14, selector: (DailyMetric) -> Double?): List<Double> =
        (history.mapNotNull(selector) + listOfNotNull(current)).takeLast(window)

    // Skin temp is bimodal: CSV imports store ABSOLUTE °C, the on-device pipeline a ±°C
    // DEVIATION — partition the history to the displayed value's kind and pick the matching
    // config + population fallback (±0.6 °C mirrors the illness watch's flag threshold).
    // This also fixes the live bug where a strap-computed +0.2 °C deviation read
    // "Out of range" against the 33–36 absolute band.
    val skin = d?.skinTempDevC
    // Track which kind the value is so the temperature converter picks the right rule: an ABSOLUTE
    // reading uses the full C→F formula (×9/5 + 32); a ±DEVIATION must omit the offset.
    val skinIsAbsolute = skin?.let { VitalBands.isAbsoluteSkinTemp(it) } ?: true
    val skinResult: VitalBands.Result = if (skin == null) {
        VitalBands.Result(VitalBands.Band.NO_DATA, VitalBands.Basis.POPULATION, 0)
    } else {
        VitalBands.band(
            value = skin,
            history = VitalBands.skinTempHistory(skin, series { it.skinTempDevC }),
            populationRange = if (skinIsAbsolute) 33.0..36.0 else -0.6..0.6,
            cfg = if (skinIsAbsolute) Baselines.metricCfg.getValue("skin_temp") else VitalBands.skinTempDeviationCfg,
        )
    }
    // Resolve the skin-temp label + converter once, honouring the °C/°F preference. `Vital.formattedValue`
    // appends `unit`, so strip the trailing " °C/°F" the formatter adds.
    val skinUnitLabel = UnitFormatter.temperatureUnit(tempUnit)
    val skinFormat: (Double) -> String = { c ->
        val full = if (skinIsAbsolute) {
            UnitFormatter.temperatureFromCelsius(c, tempUnit, decimals = 1)
        } else {
            UnitFormatter.temperatureDeltaFromCelsius(c, tempUnit, decimals = 1)
        }
        full.removeSuffix(" $skinUnitLabel")
    }
    val previousSkin = history.asReversed().asSequence()
        .mapNotNull { row -> row.skinTempDevC?.takeIf { VitalBands.isAbsoluteSkinTemp(it) == skinIsAbsolute } }
        .firstOrNull()
    val respRangeBounds = rangeBounds(days.mapNotNull { it.respRateBpm }, "rpm") { String.format(Locale.US, "%.1f", it) }
    val spo2RangeBounds = rangeBounds(days.mapNotNull { it.spo2Pct }, "%") { String.format(Locale.US, "%.0f", it) }
    val rhrRangeBounds = rangeBounds(days.mapNotNull { it.restingHr?.toDouble() }, "bpm") { it.roundToInt().toString() }
    val hrvRangeBounds = rangeBounds(days.mapNotNull { it.avgHrv }, "ms") { it.roundToInt().toString() }
    val skinRangeBounds = rangeBounds(
        days.mapNotNull { row ->
            row.skinTempDevC?.takeIf { VitalBands.isAbsoluteSkinTemp(it) == skinIsAbsolute }
        },
        skinUnitLabel,
        skinFormat,
    )
    return listOf(
        Vital(
            key = "resp", label = "Resp Rate", unit = "rpm",
            value = d?.respRateBpm, format = { String.format("%.1f", it) },
            deltaText = deltaText(d?.respRateBpm, previous { it.respRateBpm }),
            readingDay = todayKey,            rangeBounds = respRangeBounds,
            banding = VitalBands.band(d?.respRateBpm, series { it.respRateBpm }, 12.0..20.0, Baselines.respCfg),
            metricColor = Palette.metricCyan,
            sparkline = trail(d?.respRateBpm) { it.respRateBpm },
        ),
        Vital(
            key = "spo2", label = "Blood O₂", unit = "%",
            value = d?.spo2Pct, format = { String.format("%.0f", it) },
            deltaText = deltaText(d?.spo2Pct, previous { it.spo2Pct }, decimals = 0),
            readingDay = todayKey,            rangeBounds = spo2RangeBounds,
            // Population-only on purpose: an absolute <95% floor is meaningful regardless
            // of personal baseline (no "spo2" MetricCfg exists).
            banding = VitalBands.band(d?.spo2Pct, emptyList(), 95.0..100.0, null),
            metricColor = Palette.metricCyan,
            sparkline = trail(d?.spo2Pct) { it.spo2Pct },
        ),
        Vital(
            key = "rhr", label = "Resting HR", unit = "bpm",
            value = d?.restingHr?.toDouble(), format = { it.roundToInt().toString() },
            deltaText = deltaText(d?.restingHr?.toDouble(), previous { it.restingHr?.toDouble() }, decimals = 0),
            readingDay = todayKey,            rangeBounds = rhrRangeBounds,
            banding = VitalBands.band(
                d?.restingHr?.toDouble(), series { it.restingHr?.toDouble() }, 40.0..60.0,
                Baselines.restingHRCfg,
            ),
            metricColor = Palette.metricRose,
            sparkline = trail(d?.restingHr?.toDouble()) { it.restingHr?.toDouble() },
        ),
        Vital(
            key = "hrv", label = "HRV", unit = "ms",
            value = d?.avgHrv, format = { it.roundToInt().toString() },
            deltaText = deltaText(d?.avgHrv, previous { it.avgHrv }, decimals = 0),
            readingDay = todayKey,            rangeBounds = hrvRangeBounds,
            banding = VitalBands.band(d?.avgHrv, series { it.avgHrv }, 40.0..120.0, Baselines.hrvCfg),
            metricColor = Palette.metricPurple,
            sparkline = trail(d?.avgHrv) { it.avgHrv },
        ),
        Vital(
            key = "skin", label = "Skin Temp", unit = skinUnitLabel,
            value = skin, format = skinFormat,
            deltaText = deltaText(skin, previousSkin),
            readingDay = todayKey,            rangeBounds = skinRangeBounds,
            banding = skinResult, metricColor = Palette.metricAmber,
            // Keep the trail on the displayed value's kind — absolute °C and ±deviation must not mix.
            sparkline = trail(skin) { row ->
                row.skinTempDevC?.takeIf { VitalBands.isAbsoluteSkinTemp(it) == skinIsAbsolute }
            },
        ),
    )
}

@Composable
private fun VitalTile(
    vital: Vital,
    modifier: Modifier = Modifier,
    value: String = vital.formattedValue ?: "—",
    caption: String = vitalStateCaption(vital.banding),
    accent: Color = vital.accent,
) {
    // The tile borrows its accent as a faint card wash, so each vital reads as part of its colour
    // world while staying legible on the deep blue-black — matching Today's StatTile.
    NoopCard(modifier = modifier.height(Metrics.tileHeight), padding = Metrics.space14, tint = accent) {
        Column {
            Overline(vitalLabel(vital.key))
            Spacer(Modifier.weight(1f))
            Text(
                text = value,
                style = NoopType.tileValueLarge,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A metric-tinted sparkline trail with a glowing "now" end-cap, mirroring Today's tiles.
            // Hidden below two points so a sparse vital shows the caption with no flat trail.
            if (vital.sparkline.size > 1) {
                TileSparkline(
                    values = vital.sparkline,
                    color = vital.metricColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .padding(top = Metrics.space4),
                )
            }
            Text(
                text = caption,
                style = NoopType.footnote,
                color = Palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Metrics.space2),
            )
        }
    }
}

/**
 * A compact metric-tinted sparkline for a tile trail: a soft gradient fill under a coloured line,
 * capped with a glowing end-cap (a halo + white core) at the latest point so it reads as "now".
 * Built locally with Canvas + Palette colours (there is no shared tile-spark composable), mirroring
 * the Bevel chart end-cap used on the macOS sparkline and the Today HR chart. Decorative — the tile
 * already carries a combined contentDescription, so the spark is not separately announced.
 */
@Composable
private fun TileSparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.clipToBounds()) {
        if (values.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        val strokePx = 2f
        val pad = strokePx + 2f
        val usableH = (size.height - pad * 2).coerceAtLeast(1f)
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val n = values.size
        fun xFor(i: Int): Float = if (n > 1) size.width * i / (n - 1) else 0f
        fun yFor(v: Double): Float {
            val norm = ((v - lo) / span).toFloat().coerceIn(0f, 1f)
            return pad + (1f - norm) * usableH
        }
        val pts = values.mapIndexed { i, v -> Offset(xFor(i), yFor(v)) }

        // Soft gradient fill under the curve.
        val fillPath = Path().apply {
            moveTo(pts.first().x, size.height)
            lineTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            lineTo(pts.last().x, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    color.copy(alpha = StrandAlpha.chartFillSoft),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height,
            ),
        )

        // The line, tinted lighter → full at the leading edge so it reads as building toward "now".
        val linePath = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(
            path = linePath,
            brush = Brush.horizontalGradient(
                colors = listOf(color.copy(alpha = 0.5f), color),
                startX = 0f,
                endX = size.width,
            ),
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Glowing "now" end-cap at the latest point: a soft halo + white core.
        val end = pts.last()
        drawCircle(color = color.copy(alpha = 0.30f), radius = 6f, center = end)
        drawCircle(color = color.copy(alpha = 0.65f), radius = 3.5f, center = end)
        drawCircle(color = Palette.tipCore, radius = 1.6f, center = end)
    }
}

private data class VitalDetailModel(
    val key: String,
    val title: String,
    val unit: String,
    val color: Color,
    val points: List<Pair<String, Double>>,
    val format: (Double) -> String,
)

@Composable
fun VitalDetailScreen(vm: AppViewModel, key: String) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val tempUnit = UnitPrefs.temperature(LocalContext.current)
    val detail = remember(days, key, tempUnit) { buildVitalDetail(days, key, tempUnit) }
    var range by remember { mutableStateOf(VitalDetailRange.MONTH) }

    ScreenScaffold(
        title = detail?.let { vitalDetailTitle(it.key) } ?: stringResource(R.string.nav_vital_signs),
        subtitle = stringResource(R.string.health_detail_subtitle),
    ) {
        if (detail == null || detail.points.size < 2) {
            DataPendingNote(
                title = stringResource(R.string.sleep_detail_not_enough_title),
                body = stringResource(R.string.health_detail_pending_body),
            )
            return@ScreenScaffold
        }

        val filteredPoints = remember(detail, range) { filterVitalPoints(detail.points, range) }
        if (filteredPoints.size < 2) {
            DataPendingNote(
                title = stringResource(R.string.health_detail_no_range_title),
                body = stringResource(R.string.health_detail_no_range_body),
            )
            return@ScreenScaffold
        }

        val values = filteredPoints.map { it.second }
        val latest = filteredPoints.last()
        val min = values.minOrNull()
        val max = values.maxOrNull()
        val avg = values.average()

        SectionHeader(
            vitalDetailTitle(detail.key),
            overline = stringResource(R.string.nav_vital_signs),
            trailing = stringResource(R.string.health_detail_readings, filteredPoints.size),
        )
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Overline(stringResource(R.string.health_detail_latest))
                        Text(
                            text = "${detail.format(latest.second)} ${detail.unit}".trim(),
                            style = NoopType.chartValueLarge,
                            color = detail.color,
                        )
                        Text(
                            text = stringResource(R.string.health_detail_as_of, latest.first),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                }
                SegmentedPillControl(
                    items = VitalDetailRange.entries,
                    selection = range,
                    label = { it.label },
                    onSelect = { range = it },
                )
                LineChart(
                    values = values,
                    modifier = Modifier.height(Metrics.chartHeight),
                    color = detail.color,
                    fill = true,
                    selectionEnabled = true, // the Vital Signs detail chart is meant to be tappable
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Metrics.divider)
                        .background(Palette.hairline),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        "Min" to min,
                        "Avg" to avg,
                        "Max" to max,
                    ).forEach { (label, metric) ->
                        Column(modifier = Modifier.weight(1f)) {
                            Overline(label, color = Palette.textTertiary)
                            Text(
                                text = metric?.let { "${detail.format(it)} ${detail.unit}".trim() } ?: "—",
                                style = NoopType.bodyNumber,
                                color = Palette.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentDaySelectorBar(selectedOffset: Int, onSelect: (Int) -> Unit) {
    ThreeDaySelectorBar(selectedOffset = selectedOffset, onSelect = onSelect)
}

private fun latestVitals(days: List<DailyMetric>, tempUnit: TemperatureUnit): List<Vital> {
    val emptyByKey = vitalsFor(null, days, tempUnit).associateBy { it.key }
    return listOf(
        latestVital("resp", days, tempUnit, emptyByKey) { it.respRateBpm != null },
        latestVital("spo2", days, tempUnit, emptyByKey) { it.spo2Pct != null },
        latestVital("rhr", days, tempUnit, emptyByKey) { it.restingHr != null },
        latestVital("hrv", days, tempUnit, emptyByKey) { it.avgHrv != null },
        latestVital("skin", days, tempUnit, emptyByKey) { it.skinTempDevC != null },
    )
}

private fun latestVital(
    key: String,
    days: List<DailyMetric>,
    tempUnit: TemperatureUnit,
    emptyByKey: Map<String, Vital>,
    hasValue: (DailyMetric) -> Boolean,
): Vital {
    val row = days.asReversed().firstOrNull(hasValue)
    return row
        ?.let { latestRow -> vitalsFor(latestRow, days, tempUnit).firstOrNull { it.key == key } }
        ?: emptyByKey.getValue(key)
}

@Composable
private fun selectedDayLabel(offset: Int): String = when (offset) {
    0 -> stringResource(R.string.health_day_today)
    1 -> stringResource(R.string.health_day_yesterday)
    else -> stringResource(R.string.health_day_two_days_ago)
}

@Composable
private fun missingVitalsTitle(offset: Int): String = when (offset) {
    0 -> stringResource(R.string.health_missing_today)
    1 -> stringResource(R.string.health_missing_yesterday)
    else -> stringResource(R.string.health_missing_two_days_ago)
}

@Composable
private fun asOfLabel(day: String?): String? {
    if (day.isNullOrBlank()) return null
    val date = runCatching { LocalDate.parse(day) }.getOrNull()
        ?: return stringResource(R.string.health_asof_date, day)
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.health_asof_today)
        today.minusDays(1) -> stringResource(R.string.health_asof_yesterday)
        else -> stringResource(R.string.health_asof_date, date.format(DateTimeFormatter.ofPattern("d MMM", Locale.US)))
    }
}

private enum class VitalDetailRange(val label: String, val days: Long?) {
    WEEK("W", 7),
    MONTH("M", 30),
    THREE_MONTH("3M", 90),
    SIX_MONTH("6M", 180),
    YEAR("1Y", 365),
    ALL("ALL", null),
}

private fun filterVitalPoints(
    points: List<Pair<String, Double>>,
    range: VitalDetailRange,
): List<Pair<String, Double>> {
    val windowDays = range.days ?: return points
    val latestDate = points.lastOrNull()?.first?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return points.takeLast(windowDays.toInt())
    val cutoff = latestDate.minusDays(windowDays - 1)
    val filtered = points.filter { (day, _) ->
        runCatching { LocalDate.parse(day) }.getOrNull()?.let { !it.isBefore(cutoff) } ?: false
    }
    return filtered.ifEmpty { points.takeLast(windowDays.toInt()) }
}

// Resolves the localized display title for a vital from its stable key. The English
// VitalDetailModel.title strings stay as canonical fallbacks; this is the render-time resolver.
@Composable
private fun vitalDetailTitle(key: String): String = when (key) {
    "resp" -> stringResource(R.string.health_detail_title_resp)
    "spo2" -> stringResource(R.string.health_detail_title_spo2)
    "rhr" -> stringResource(R.string.health_detail_title_rhr)
    "hrv" -> stringResource(R.string.health_detail_title_hrv)
    "skin" -> stringResource(R.string.health_detail_title_skin)
    else -> stringResource(R.string.nav_vital_signs)
}

private fun buildVitalDetail(
    days: List<DailyMetric>,
    key: String,
    tempUnit: TemperatureUnit,
): VitalDetailModel? {
    return when (key) {
    "resp" -> VitalDetailModel(
        key = key,
        title = "Respiratory Rate",
        unit = "rpm",
        color = Palette.metricCyan,
        points = days.mapNotNull { it.respRateBpm?.let { value -> it.day to value } },
        format = { String.format(Locale.US, "%.1f", it) },
    )
    "spo2" -> VitalDetailModel(
        key = key,
        title = "Blood Oxygen",
        unit = "%",
        color = Palette.metricCyan,
        points = days.mapNotNull { it.spo2Pct?.let { value -> it.day to value } },
        format = { String.format(Locale.US, "%.0f", it) },
    )
    "rhr" -> VitalDetailModel(
        key = key,
        title = "Resting Heart Rate",
        unit = "bpm",
        color = Palette.metricRose,
        points = days.mapNotNull { it.restingHr?.toDouble()?.let { value -> it.day to value } },
        format = { it.roundToInt().toString() },
    )
    "hrv" -> VitalDetailModel(
        key = key,
        title = "Heart Rate Variability",
        unit = "ms",
        color = Palette.metricPurple,
        points = days.mapNotNull { it.avgHrv?.let { value -> it.day to value } },
        format = { it.roundToInt().toString() },
    )
    "skin" -> {
        val latest = days.asReversed().asSequence().mapNotNull { it.skinTempDevC }.firstOrNull() ?: return null
        val absolute = VitalBands.isAbsoluteSkinTemp(latest)
        val unit = UnitFormatter.temperatureUnit(tempUnit)
        val format: (Double) -> String = { c ->
            val full = if (absolute) {
                UnitFormatter.temperatureFromCelsius(c, tempUnit, decimals = 1)
            } else {
                UnitFormatter.temperatureDeltaFromCelsius(c, tempUnit, decimals = 1)
            }
            full.removeSuffix(" $unit")
        }
        VitalDetailModel(
            key = key,
            title = "Skin Temperature",
            unit = unit,
            color = Palette.metricAmber,
            points = days.mapNotNull { row ->
                row.skinTempDevC
                    ?.takeIf { VitalBands.isAbsoluteSkinTemp(it) == absolute }
                    ?.let { value -> row.day to value }
            },
            format = format,
        )
    }
    else -> null
    }
}

// MARK: - Empty state

@Composable
private fun HealthEmptyState() {
    DataPendingNote(
        title = stringResource(R.string.health_empty_title),
        body = stringResource(R.string.health_empty_body),
    )
}
