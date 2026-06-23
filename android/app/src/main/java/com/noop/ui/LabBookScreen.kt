package com.noop.ui

import androidx.compose.ui.res.stringResource
import com.noop.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.analytics.LabBookProjection
import com.noop.analytics.LabMarkerCategory
import com.noop.analytics.MarkerCatalog
import com.noop.analytics.WindowedPair
import com.noop.data.LabMarkerRow
import com.noop.data.WhoopDao
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

// MARK: - Lab Book (Health Records pillar — v5) — Compose twin of LabBookView.swift
//
// "Your own logbook." A private place to KEEP the numbers you already get from your
// doctor or pharmacy — bloods, BP, body measurements — and SEE them next to your
// wearable signals, entirely on this phone. NOOP never tests you, never reads a result,
// and never tells you what a number means medically.
// (Spec: docs/superpowers/specs/2026-06-19-v5-health-records-design.md.)
//
// SELF-CONTAINED: reads/writes markers through `vm.repo` (the Lab Book DAO methods +
// metricSeries projection). Raw readings store under the strap device id ("my-whoop");
// every write also projects a daily series under LAB_BOOK_SOURCE_ID so Compare/Explore/
// Coach see markers unchanged. The "Compare with a signal" surface reuses the same
// Pearson idiom + restrained copy as the Compare screen.
//
// NON-CLINICAL (load-bearing): no word here asserts a clinical judgement; any reference
// range shown is exactly what the user typed from their own report; correlation copy says
// "association, not a medical finding".

/** The strap device id the markers are stored under (matches the rest of the app + Swift test). */
private const val LAB_STRAP_DEVICE_ID = "my-whoop"

/** Reading-count floor below which NO conclusion sentence renders (spec default 4). */
private const val LAB_FLOOR = 4

@Composable
fun LabBookScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()

    var markers by remember { mutableStateOf<List<LabMarkerRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadSeq by remember { mutableStateOf(0) }

    // Sheets.
    var showEditor by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var detailKey by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val all = mutableListOf<LabMarkerRow>()
        for (category in LabMarkerCategory.entries) {
            all += vm.repo.labMarkersByCategory(LAB_STRAP_DEVICE_ID, category.raw)
        }
        markers = all.sortedBy { it.takenAt }
        loaded = true
    }

    LaunchedEffect(reloadSeq) { reload() }

    // PERF (#707): lazy scaffold — each top-level section is one `item { }` so only on-screen cards
    // compose + are accessibility-walked on scroll. Order/spacing unchanged (no standalone Spacers; the
    // LazyColumn reproduces the eager `spacedBy(20.dp)`). The category/marker list stays inside the single
    // `when {}` item (it's a user-entered, bounded set, not unbounded history), so its appearance is
    // byte-identical; the sheets below the scaffold are untouched.
    // Hoisted out of the non-composable `semantics {}` lambdas below.
    val whatLabBookIsCd = stringResource(R.string.lab_book_cd_what_lab_book_is_and)
    val readFullCd = stringResource(R.string.lab_book_cd_read_the_full_lab_book)

    LazyScreenScaffold(
        title = stringResource(R.string.lab_book_title),
        subtitle = stringResource(R.string.lab_book_subtitle),
    ) {
        // Header card: count + scope + add action.
        item {
        NoopCard(padding = 18.dp, tint = Palette.metricCyan) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Palette.metricCyan.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MenuBook, contentDescription = null, tint = Palette.metricCyan, modifier = Modifier.size(16.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(countLine(markers), style = NoopType.headline, color = Palette.textPrimary)
                        Text(
                            stringResource(R.string.lab_book_all_stays_on_this_phone),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showDisclaimer = true }
                            .semantics { contentDescription = whatLabBookIsCd },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    stringResource(R.string.lab_book_it_s_a_notebook_not_a),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                PrimaryActionButton(stringResource(R.string.lab_book_add_a_reading), Icons.Filled.Add) { showEditor = true }
            }
        }
        }

        // Import entry — reuses the Data Sources import-card idiom. A bulk "Markers CSV" import is a
        // Phase-2 engine; until it lands the card honestly points the user at Data Sources rather
        // than fabricating a flow.
        item {
        NoopCard(padding = 18.dp, tint = Palette.metricAmber) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Palette.metricAmber.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = null, tint = Palette.metricAmber, modifier = Modifier.size(16.dp))
                    }
                    Text(stringResource(R.string.lab_book_import_readings), style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    StatePill(stringResource(R.string.lab_book_coming_soon), tone = StrandTone.Neutral, showsDot = false)
                }
                Text(
                    stringResource(R.string.lab_book_a_bulk_markers_csv_import_date),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        }
        }

        item {
        when {
            !loaded -> {
                Text(stringResource(R.string.lab_book_reading_your_logbook), style = NoopType.subhead, color = Palette.textTertiary)
            }
            markers.isEmpty() -> {
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.lab_book_keep_your_own_numbers_here), style = NoopType.headline, color = Palette.textPrimary)
                        Text(
                            stringResource(R.string.lab_book_type_in_a_blood_pressure_reading),
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    }
                }
            }
            else -> {
                for (category in orderedCategories(markers)) {
                    val keys = markerKeys(markers, category)
                    SectionHeader(
                        title = category.displayName,
                        overline = if (keys.size == 1) stringResource(R.string.lab_book_one_marker_label)
                        else stringResource(R.string.lab_book_n_markers_label, keys.size),
                    )
                    for (key in keys) {
                        MarkerRow(key = key, readings = readingsFor(markers, key)) { detailKey = key }
                    }
                }
            }
        }
        }

        // Always-visible disclaimer footnote + link.
        item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.lab_book_lab_book_is_a_private_notebook),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
            Text(
                stringResource(R.string.lab_book_read_the_full_note),
                style = NoopType.footnote,
                color = Palette.accent,
                modifier = Modifier
                    .clickable { showDisclaimer = true }
                    .semantics { contentDescription = readFullCd },
            )
        }
        }
    }

    // --- Sheets ---
    if (showEditor) {
        MarkerEditorScreen(
            onDismiss = { showEditor = false },
            onSave = { drafts ->
                scope.launch {
                    vm.repo.upsertLabMarkers(drafts)
                    reloadSeq++
                }
                showEditor = false
            },
        )
    }

    if (showDisclaimer) {
        LabBookDisclaimerSheet(onDismiss = { showDisclaimer = false })
    }

    detailKey?.let { key ->
        MarkerDetailSheet(
            vm = vm,
            markerKey = key,
            readings = readingsFor(markers, key),
            onDelete = { id ->
                scope.launch {
                    vm.repo.deleteLabMarker(id)
                    reloadSeq++
                }
            },
            onDismiss = { detailKey = null },
        )
    }
}

// MARK: - Marker list row

@Composable
private fun MarkerRow(key: String, readings: List<LabMarkerRow>, onClick: () -> Unit) {
    val numeric = readings.mapNotNull { it.value }
    val latest = readings.lastOrNull()
    NoopCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName(key), style = NoopType.headline, color = Palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lastTakenCaption(latest), style = NoopType.footnote, color = Palette.textTertiary)
            }
            if (numeric.size > 1) {
                MiniSpark(values = numeric, color = Palette.metricCyan, modifier = Modifier.width(64.dp).height(28.dp))
            }
            Text(latestLabel(latest, key), style = NoopType.number(18f), color = Palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

// MARK: - Marker detail sheet (history + trend + compare with a signal)

@Composable
private fun MarkerDetailSheet(
    vm: AppViewModel,
    markerKey: String,
    readings: List<LabMarkerRow>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = displayName(markerKey)
    val unit = readings.lastOrNull()?.unit ?: MarkerCatalog.definition(markerKey)?.canonicalUnit ?: ""
    val numeric = readings.filter { it.value != null }

    var signal by remember { mutableStateOf<LabSignal?>(null) }
    var window by remember { mutableStateOf(LabWindow.FORTNIGHT) }
    var pairs by remember { mutableStateOf<List<WindowedPair>>(emptyList()) }
    var correlation by remember { mutableStateOf<LabCorrelation?>(null) }
    var computing by remember { mutableStateOf(false) }

    LaunchedEffect(signal, window) {
        val s = signal
        if (s == null) {
            pairs = emptyList(); correlation = null
            return@LaunchedEffect
        }
        computing = true
        val to = labDay(1)
        val from = labDay(-4000)
        val markerSeries = vm.repo.metricSeries(WhoopDao.LAB_BOOK_SOURCE_ID, markerKey, from, to).map { it.day to it.value }
        val wearable = vm.repo.resolvedSeries(s.key, s.source, from, to).values
        val built = LabBookProjection.pairMarkerToWearable(markerSeries, wearable, window.days)
        pairs = built
        correlation = if (built.size >= LAB_FLOOR) {
            pearson(LabBookProjection.correlationInput(built))
        } else {
            null
        }
        computing = false
    }

    NoopBottomSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            Text(name, style = NoopType.title2, color = Palette.textPrimary)
            val readingCountPhrase = if (readings.size == 1) {
                stringResource(R.string.lab_book_reading_singular, readings.size)
            } else {
                stringResource(R.string.lab_book_reading_plural, readings.size)
            }
            Text(
                stringResource(R.string.lab_book_reading_count_entries, readingCountPhrase),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )

            // Trend (descriptive arithmetic, never interpretation).
            SectionHeader(stringResource(R.string.lab_book_trend), overline = stringResource(R.string.lab_book_trend_overline))
            NoopCard(tint = Palette.metricCyan) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val nums = numeric.mapNotNull { it.value }
                    if (nums.size > 1) {
                        MiniSpark(values = nums, color = Palette.metricCyan, modifier = Modifier.fillMaxWidth().height(64.dp))
                    }
                    Text(trendSentence(markerKey, numeric, unit), style = NoopType.subhead, color = Palette.textSecondary)
                    latestReferenceText(readings)?.let { ref ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SourceBadge(stringResource(R.string.lab_book_from_your_report), tint = Palette.textTertiary)
                            Text(ref, style = NoopType.footnote, color = Palette.textSecondary)
                        }
                    }
                }
            }

            // Compare with a signal (reuses the Pearson idiom + restrained copy).
            if (numeric.isNotEmpty()) {
                SectionHeader(
                    stringResource(R.string.lab_book_compare_with_a_signal),
                    overline = stringResource(R.string.lab_book_compare_overline, window.phrase),
                )
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SignalPicker(selected = signal) { signal = it }
                            Spacer(Modifier.weight(1f))
                            SegmentedPillControl(
                                items = LabWindow.entries.toList(),
                                selection = window,
                                label = { it.label },
                                onSelect = { window = it },
                            )
                        }
                        CorrelationResult(
                            markerName = name,
                            signal = signal,
                            window = window,
                            pairs = pairs,
                            correlation = correlation,
                            computing = computing,
                        )
                    }
                }
            }

            // History table.
            SectionHeader(stringResource(R.string.lab_book_history), overline = stringResource(R.string.lab_book_history_overline))
            NoopCard {
                Column {
                    val reversed = readings.reversed()
                    reversed.forEachIndexed { idx, row ->
                        HistoryRow(markerKey, row, onDelete)
                        if (idx < reversed.size - 1) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.lab_book_these_are_your_own_numbers_shown),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun CorrelationResult(
    markerName: String,
    signal: LabSignal?,
    window: LabWindow,
    pairs: List<WindowedPair>,
    correlation: LabCorrelation?,
    computing: Boolean,
) {
    val n = pairs.size
    when {
        signal == null -> Text(
            stringResource(R.string.lab_book_pick_a_wearable_signal, window.phrase),
            style = NoopType.subhead,
            color = Palette.textTertiary,
        )
        computing -> Text(stringResource(R.string.lab_book_lining_them_up), style = NoopType.subhead, color = Palette.textTertiary)
        n < LAB_FLOOR -> Text(
            if (n == 0) {
                stringResource(R.string.lab_book_no_overlap_yet, signal.title.lowercase())
            } else {
                val readingPhrase = if (n == 1) {
                    stringResource(R.string.lab_book_reading_singular, n)
                } else {
                    stringResource(R.string.lab_book_reading_plural, n)
                }
                stringResource(R.string.lab_book_n_line_up_so_far, readingPhrase, LAB_FLOOR)
            },
            style = NoopType.subhead,
            color = Palette.textTertiary,
        )
        correlation != null -> {
            val r = correlation.r
            val tint = correlationColor(r)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Pure symbol concat of two dynamic marker/signal names (no translatable copy).
                    Text("$markerName ↔ ${signal.title}", style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f), maxLines = 2)
                    TrendChip(text = signedR(r), color = tint)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.lab_book_r_equals, signedR(r)), style = NoopType.number(18f), color = tint)
                }
                Text(insightSentence(markerName, signal.title, r), style = NoopType.subhead, color = Palette.textSecondary)
                Text(
                    stringResource(R.string.lab_book_n_readings_used, n, strengthWord(r), directionWord(r)),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        else -> Text(
            stringResource(R.string.lab_book_not_enough_variation, n),
            style = NoopType.subhead,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun SignalPicker(selected: LabSignal?, onSelect: (LabSignal?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val chooseSignalCd = stringResource(R.string.lab_book_cd_choose_a_wearable_signal_to)
    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .semantics { contentDescription = chooseSignalCd },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(16.dp))
            Text(selected?.title ?: stringResource(R.string.lab_book_choose_a_signal), style = NoopType.subhead, color = Palette.accent)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LAB_SIGNALS.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(s.title, style = NoopType.body, color = Palette.textPrimary) },
                    onClick = { onSelect(s); expanded = false },
                )
            }
            if (selected != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.lab_book_clear), style = NoopType.body, color = Palette.textSecondary) },
                    onClick = { onSelect(null); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(markerKey: String, row: LabMarkerRow, onDelete: (String) -> Unit) {
    val deleteReadingCd = stringResource(R.string.lab_book_cd_delete_this_reading)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(valueLabel(markerKey, row), style = NoopType.number(16f), color = Palette.textPrimary)
            Text(labDayLabel(row.takenAt), style = NoopType.footnote, color = Palette.textTertiary)
            row.note?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = NoopType.footnote, color = Palette.textSecondary)
            }
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onDelete(row.id) }
                .semantics { contentDescription = deleteReadingCd },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = Palette.statusCritical, modifier = Modifier.size(15.dp))
        }
    }
}

// MARK: - Disclaimer sheet

@Composable
private fun LabBookDisclaimerSheet(onDismiss: () -> Unit) {
    NoopBottomSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lab_book_about_lab_book), style = NoopType.title2, color = Palette.textPrimary)
            Text(stringResource(R.string.lab_book_a_private_notebook_not_a_medical), style = NoopType.subhead, color = Palette.textSecondary)
            DisclaimerBullet(stringResource(R.string.lab_book_disclaimer_stores))
            DisclaimerBullet(stringResource(R.string.lab_book_disclaimer_association))
            DisclaimerBullet(stringResource(R.string.lab_book_disclaimer_never_decides))
            DisclaimerBullet(stringResource(R.string.lab_book_disclaimer_never_leave))
            DisclaimerBullet(stringResource(R.string.lab_book_disclaimer_rely_on_doctor))
            PrimaryActionButton(stringResource(R.string.lab_book_got_it), Icons.Filled.Check, onClick = onDismiss)
        }
    }
}

@Composable
private fun DisclaimerBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.metricCyan),
        )
        Text(text, style = NoopType.subhead, color = Palette.textSecondary)
    }
}

// MARK: - Trailing window control (7 / 14 / 30 days)

enum class LabWindow(val label: String, val days: Int, val phrase: String) {
    WEEK("7d", 7, "7 days"),
    FORTNIGHT("14d", 14, "14 days"),
    MONTH("30d", 30, "30 days"),
}

// MARK: - Wearable signals offered for correlation

data class LabSignal(val key: String, val title: String, val source: String)

/** The pickable wearable metrics, mirroring the Swift LabBookSignals.options list. */
private val LAB_SIGNALS = listOf(
    LabSignal("rhr", "Resting Heart Rate", "my-whoop"),
    LabSignal("hrv", "Heart Rate Variability", "my-whoop"),
    LabSignal("recovery", "Charge", "my-whoop"),
    LabSignal("sleep_performance", "Rest", "my-whoop"),
    LabSignal("sleep_total_min", "Asleep Time", "my-whoop"),
    LabSignal("strain", "Effort", "my-whoop"),
    LabSignal("skin_temp", "Skin Temperature", "my-whoop"),
    LabSignal("steps", "Steps", "apple-health"),
    LabSignal("weight", "Weight", "apple-health"),
)

// MARK: - Helpers (display, formatting, trend, correlation)

@Composable
private fun countLine(markers: List<LabMarkerRow>): String {
    val keys = markers.map { it.markerKey }.toSet().size
    val markerPhrase = if (keys == 1) {
        stringResource(R.string.lab_book_count_marker_singular, keys)
    } else {
        stringResource(R.string.lab_book_count_marker_plural, keys)
    }
    val readingPhrase = if (markers.size == 1) {
        stringResource(R.string.lab_book_reading_singular, markers.size)
    } else {
        stringResource(R.string.lab_book_reading_plural, markers.size)
    }
    return stringResource(R.string.lab_book_count_line, markerPhrase, readingPhrase)
}

private fun displayName(key: String): String =
    MarkerCatalog.definition(key)?.displayName ?: key.replace("_", " ").replaceFirstChar { it.uppercase() }

private fun readingsFor(markers: List<LabMarkerRow>, key: String): List<LabMarkerRow> =
    markers.filter { it.markerKey == key }

private fun orderedCategories(markers: List<LabMarkerRow>): List<LabMarkerCategory> {
    val present = markers.map { LabMarkerCategory.fromRaw(it.category) }.toSet()
    val order = listOf(
        LabMarkerCategory.BLOOD_PANEL, LabMarkerCategory.BLOOD_PRESSURE, LabMarkerCategory.BODY_MEASUREMENT,
        LabMarkerCategory.IMAGING, LabMarkerCategory.APPOINTMENT_NOTE, LabMarkerCategory.OTHER,
    )
    return order.filter { present.contains(it) }
}

private fun markerKeys(markers: List<LabMarkerRow>, category: LabMarkerCategory): List<String> =
    markers.filter { it.category == category.raw }.map { it.markerKey }.toSet().sortedBy { displayName(it) }

private fun valueLabel(key: String, row: LabMarkerRow): String =
    row.value?.let { "${formatValue(it, key)} ${row.unit}" } ?: (row.valueText ?: "—")

private fun latestLabel(row: LabMarkerRow?, key: String): String {
    if (row == null) return "—"
    return row.value?.let { "${formatValue(it, key)} ${row.unit}" } ?: (row.valueText ?: "—")
}

@Composable
private fun lastTakenCaption(row: LabMarkerRow?): String =
    if (row == null) stringResource(R.string.lab_book_no_readings_yet)
    else stringResource(R.string.lab_book_last_taken, labDayLabel(row.takenAt))

private fun formatValue(v: Double, key: String): String {
    val decimals = MarkerCatalog.definition(key)?.decimals ?: 1
    return if (decimals == 0) Math.round(v).toString() else java.lang.String.format(Locale.US, "%.${decimals}f", v)
}

private fun latestReferenceText(readings: List<LabMarkerRow>): String? =
    readings.lastOrNull { !it.referenceText.isNullOrEmpty() }?.referenceText

/** "Your last 3 readings: 3.4 → 3.1 → 2.9 mmol/L, trending down." — descriptive only. */
@Composable
private fun trendSentence(key: String, numeric: List<LabMarkerRow>, unit: String): String {
    val last = numeric.lastOrNull()?.value
        ?: return numeric.lastOrNull()?.valueText?.let { stringResource(R.string.lab_book_latest_entry, it) }
            ?: stringResource(R.string.lab_book_no_numeric_readings)
    if (numeric.size < 2) {
        return stringResource(R.string.lab_book_one_reading_so_far, "${formatValue(last, key)} $unit")
    }
    val shown = numeric.takeLast(3).mapNotNull { it.value }
    val arrowed = shown.joinToString(" → ") { formatValue(it, key) }
    val first = shown.first()
    val direction = when {
        last > first -> stringResource(R.string.lab_book_trending_up)
        last < first -> stringResource(R.string.lab_book_trending_down)
        else -> stringResource(R.string.lab_book_holding_steady)
    }
    return stringResource(R.string.lab_book_your_last_n_readings, shown.size, arrowed, unit, direction)
}

private fun signedR(r: Double): String = (if (r >= 0) "+" else "−") + java.lang.String.format(Locale.US, "%.2f", abs(r))

@Composable
private fun strengthWord(r: Double): String = when {
    abs(r) < 0.1 -> stringResource(R.string.lab_book_strength_negligible)
    abs(r) < 0.3 -> stringResource(R.string.lab_book_strength_weak)
    abs(r) < 0.5 -> stringResource(R.string.lab_book_strength_moderate)
    abs(r) < 0.7 -> stringResource(R.string.lab_book_strength_strong)
    else -> stringResource(R.string.lab_book_strength_very_strong)
}

@Composable
private fun directionWord(r: Double): String =
    if (abs(r) < 0.1) "" else if (r >= 0) stringResource(R.string.lab_book_direction_positive) else stringResource(R.string.lab_book_direction_negative)

@Composable
private fun insightSentence(markerName: String, signalName: String, r: Double): String {
    if (abs(r) < 0.3) {
        return stringResource(R.string.lab_book_insight_independent, markerName, signalName.lowercase())
    }
    return if (r < 0) {
        stringResource(R.string.lab_book_insight_lower, markerName, signalName.lowercase())
    } else {
        stringResource(R.string.lab_book_insight_higher, markerName, signalName.lowercase())
    }
}

@Composable
private fun correlationColor(r: Double): Color {
    val base = if (r >= 0) Palette.statusPositive else Palette.statusCritical
    return base.copy(alpha = (0.55f + 0.45f * min(abs(r), 1.0)).toFloat())
}

/** Pearson r over the pairs — value-for-value with CompareScreen's engine (null when <3 or zero variance). */
private data class LabCorrelation(val r: Double, val n: Int)

private fun pearson(xy: List<Pair<Double, Double>>): LabCorrelation? {
    val n = xy.size
    if (n < 3) return null
    val nD = n.toDouble()
    var sumX = 0.0
    var sumY = 0.0
    for (p in xy) { sumX += p.first; sumY += p.second }
    val meanX = sumX / nD
    val meanY = sumY / nD
    var sxx = 0.0
    var syy = 0.0
    var sxy = 0.0
    for (p in xy) {
        val dx = p.first - meanX
        val dy = p.second - meanY
        sxx += dx * dx
        syy += dy * dy
        sxy += dx * dy
    }
    if (sxx <= 0.0 || syy <= 0.0) return null
    var r = sxy / (sqrt(sxx) * sqrt(syy))
    if (r > 1.0) r = 1.0
    if (r < -1.0) r = -1.0
    return LabCorrelation(r, n)
}

private val labDayFmt = SimpleDateFormat("d MMM yyyy", Locale.US)
private fun labDayLabel(epochSeconds: Long): String = labDayFmt.format(Date(epochSeconds * 1000L))

/** "yyyy-MM-dd" for today offset by [deltaDays], fixed UTC (matches CompareScreen.todayDay). */
internal fun labDay(deltaDays: Int): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.add(java.util.Calendar.DAY_OF_YEAR, deltaDays)
    val y = cal.get(java.util.Calendar.YEAR)
    val m = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    return java.lang.String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
}

// MARK: - Shared Lab Book primitives (sheet wrapper, primary button, mini sparkline)

/** A scrollable frosted bottom sheet in the house style — used by the marker detail, the
 *  marker editor and the disclaimer. Mirrors the WorkoutDetailSheet idiom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoopBottomSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.surfaceOverlay,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            content()
        }
    }
}

/** The accent primary CTA, matching the .noopPrimary button style on Apple. */
@Composable
internal fun PrimaryActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    val container = if (enabled) Palette.accent else Palette.accent.copy(alpha = Palette.disabledOpacity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            // A crisp, subtle NEUTRAL elevation (soft dark lift, no bloom) — matching the iOS
            // .noopPrimary refresh, which trades any cast-glow for a clean neutral shadow.
            .let { if (enabled) it.shadow(elevation = 4.dp, shape = shape, clip = false) else it }
            .clip(shape)
            .background(container)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.surfaceBase, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = NoopType.body, color = Palette.surfaceBase)
    }
}

/** A tiny inline sparkline for a short numeric series, sized by the modifier (width × height). */
@Composable
internal fun MiniSpark(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val pad = 3f
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val pts = values.mapIndexed { i, v ->
            val x = pad + (i.toFloat() / (values.size - 1)) * w
            val y = pad + (1f - ((v - lo) / span).toFloat()) * h
            Offset(x, y)
        }
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(path, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
