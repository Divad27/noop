package com.noop.ui

import androidx.compose.ui.res.stringResource
import com.noop.R

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.noop.data.DataBackup
import com.noop.data.ImportSummary
import com.noop.ingest.AppleHealthImporter
import com.noop.ingest.HealthConnectImporter
import com.noop.ingest.HealthConnectWriter
import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.LiftingImporter
import com.noop.ingest.NutritionCsvImporter
import com.noop.ingest.XiaomiBandImporter
import com.noop.ingest.WhoopCsvImporter
import com.noop.ingest.WearableExportImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data Sources — ports the macOS DataSourcesView (Strand/Screens/DataSourcesView.swift)
 * onto the locked Android component system (ScreenScaffold / NoopCard / StatePill /
 * Overline / NoopType / Palette).
 *
 * The macOS screen is built around "bring your history in once, then it's yours": three
 * source cards (WHOOP Export, Apple Health, Live BLE) plus on-device file import. On
 * Android the on-device store is a single Room/SQLite file, and the real, working
 * migration path is whole-store export/import via [DataBackup] (a SAF document the user
 * picks). So this screen keeps the macOS structure but maps each card to what Android
 * actually has:
 *
 *   - WHOOP data    — live counts of the cached "my-whoop" history, plus a working import
 *                     of a WHOOP .zip/.csv export (app.whoop.com → Data Management) via
 *                     [com.noop.ingest.WhoopCsvImporter].
 *   - Apple Health  — live counts of cached "apple-health" data, plus a working streaming
 *                     import of an Apple Health export.zip/export.xml via
 *                     [com.noop.ingest.AppleHealthImporter].
 *   - Health Connect— native Android import (steps/HR/HRV/sleep/SpO₂/weight/workouts) via
 *                     [com.noop.ingest.HealthConnectImporter], gated on runtime permission.
 *   - Nutrition CSV — daily calories / macros / body weight from a nutrition CSV
 *                     (MyFitnessPal, Cronometer, or any date+columns spreadsheet) via
 *                     [com.noop.ingest.NutritionCsvImporter], stored as metricSeries rows
 *                     under source "nutrition-csv".
 *   - WHOOP Strap   — the live BLE bond/stream status, straight from the LiveState flow.
 *   - Backup        — Export / Import the whole on-device database through [DataBackup],
 *                     wired to ActivityResult document launchers.
 */
@Composable
fun DataSourcesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by vm.live.collectAsStateWithLifecycle()
    val hrBroadcast by vm.hrBroadcast.collectAsStateWithLifecycle()
    val hrBroadcastAdvertising by vm.hrBroadcastAdvertising.collectAsStateWithLifecycle()
    val hrBroadcastSubscribers by vm.hrBroadcastSubscribers.collectAsStateWithLifecycle()
    val hrBroadcastStatus by vm.hrBroadcastStatus.collectAsStateWithLifecycle()
    val hcAutoSync by vm.hcAutoSync.collectAsStateWithLifecycle()
    val hcSyncHours by vm.hcSyncHours.collectAsStateWithLifecycle()
    val hcLastSync by vm.hcLastSync.collectAsStateWithLifecycle()
    val hcWriteback by vm.hcWriteback.collectAsStateWithLifecycle()

    // Cached-store counts, loaded once from the repo (newest data is fine to recount).
    var whoopDays by remember { mutableStateOf<Int?>(null) }
    var whoopWorkouts by remember { mutableStateOf<Int?>(null) }
    var whoopHasHr by remember { mutableStateOf(false) }
    var appleDays by remember { mutableStateOf<Int?>(null) }
    var appleWorkouts by remember { mutableStateOf<Int?>(null) }
    // Health Connect has its OWN source ("health-connect"), counted separately from an Apple Health
    // export so each card reflects its own data rather than both showing under Apple Health (issue #34).
    var hcDays by remember { mutableStateOf<Int?>(null) }
    var hcWorkouts by remember { mutableStateOf<Int?>(null) }
    // Nutrition CSV writes long-format metricSeries rows under its own source ("nutrition-csv"),
    // so its card counts days-with-calories and weigh-ins straight off that table.
    var nutritionDays by remember { mutableStateOf<Int?>(null) }
    var nutritionWeighIns by remember { mutableStateOf<Int?>(null) }
    // Imported lifting (Hevy / Liftosaur) writes workouts under its own source ("lifting").
    var liftingWorkouts by remember { mutableStateOf<Int?>(null) }
    // Imported workout files (GPX / TCX / FIT) write workouts under their own source ("activity-file").
    var activityFiles by remember { mutableStateOf<Int?>(null) }
    var xiaomiDays by remember { mutableStateOf<Int?>(null) }
    // Imported Oura / Fitbit / Garmin exports write daily metrics under their own per-brand source.
    var wearableDays by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis() / 1000
        whoopDays = vm.repo.days("my-whoop").size
        whoopWorkouts = vm.repo.workouts("my-whoop", 0L, now).size
        whoopHasHr = vm.repo.latestHrSampleTs("my-whoop") != null
        appleDays = vm.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31").size
        appleWorkouts = vm.repo.workouts("apple-health", 0L, now).size
        hcDays = vm.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31").size
        hcWorkouts = vm.repo.workouts("health-connect", 0L, now).size
        nutritionDays = vm.repo.metricSeries(NutritionCsvImporter.SOURCE_ID, "calories_in", "0000-01-01", "9999-12-31").size
        nutritionWeighIns = vm.repo.metricSeries(NutritionCsvImporter.SOURCE_ID, "weight", "0000-01-01", "9999-12-31").size
        xiaomiDays = vm.repo.metricSeries(XiaomiBandImporter.DEFAULT_DEVICE_ID, "steps", "0000-01-01", "9999-12-31").size
        liftingWorkouts = vm.repo.workouts(LiftingImporter.SOURCE_ID, 0L, now).size
        activityFiles = vm.repo.workouts(ActivityFileImporter.SOURCE_ID, 0L, now).size
        wearableDays = WearableExportImporter.Brand.values().sumOf {
            vm.repo.metricSeries(it.sourceId, "rhr", "0000-01-01", "9999-12-31").size +
                vm.repo.metricSeries(it.sourceId, "sleep_total_min", "0000-01-01", "9999-12-31").size
        }
    }

    // Whole-store backup: export to a user-created document; import from a picked one.
    var busy by remember { mutableStateOf(false) }
    var restartNeeded by remember { mutableStateOf(false) }
    // ah-delete (#616): drives the "Remove Apple Health imported data" confirm dialog.
    var confirmDeleteApple by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { DataBackup.exportTo(context, uri) }
                    .fold(
                        { context.getString(R.string.datasources_backup_saved) },
                        { context.getString(R.string.datasources_backup_failed, it.message ?: "") },
                    )
            }
            busy = false
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { DataBackup.importFrom(context, uri) }
            busy = false
            when (result) {
                is DataBackup.ImportResult.NeedsRestart -> {
                    restartNeeded = true
                    Toast.makeText(
                        context,
                        context.getString(R.string.datasources_backup_imported_restart),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is DataBackup.ImportResult.Failed ->
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun refreshCounts() {
        val nowS = System.currentTimeMillis() / 1000
        whoopDays = vm.repo.days("my-whoop").size
        whoopWorkouts = vm.repo.workouts("my-whoop", 0L, nowS).size
        whoopHasHr = vm.repo.latestHrSampleTs("my-whoop") != null
        appleDays = vm.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31").size
        appleWorkouts = vm.repo.workouts("apple-health", 0L, nowS).size
        hcDays = vm.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31").size
        hcWorkouts = vm.repo.workouts("health-connect", 0L, nowS).size
        nutritionDays = vm.repo.metricSeries(NutritionCsvImporter.SOURCE_ID, "calories_in", "0000-01-01", "9999-12-31").size
        nutritionWeighIns = vm.repo.metricSeries(NutritionCsvImporter.SOURCE_ID, "weight", "0000-01-01", "9999-12-31").size
        liftingWorkouts = vm.repo.workouts(LiftingImporter.SOURCE_ID, 0L, nowS).size
        activityFiles = vm.repo.workouts(ActivityFileImporter.SOURCE_ID, 0L, nowS).size
        xiaomiDays = vm.repo.metricSeries(XiaomiBandImporter.DEFAULT_DEVICE_ID, "steps", "0000-01-01", "9999-12-31").size
        wearableDays = WearableExportImporter.Brand.values().sumOf {
            vm.repo.metricSeries(it.sourceId, "rhr", "0000-01-01", "9999-12-31").size +
                vm.repo.metricSeries(it.sourceId, "sleep_total_min", "0000-01-01", "9999-12-31").size
        }
    }

    // Run an importer off the main thread, refresh the counts, then toast the result.
    fun runImport(block: suspend () -> ImportSummary) {
        busy = true
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse {
                    ImportSummary.failure("Import", it.message ?: context.getString(R.string.datasources_import_failed))
                }
            }
            // Mirror the import into the SAME exported strap log the WHOOP path uses (issue #421 parity),
            // so a tester's file import is captured in a shared debug bundle. On success: brand label +
            // per-table COUNTS only (e.g. "dailyMetric=120, sleepSession=88"). On a zero-row/failed import:
            // the brand label + the human reason from the summary. Never a file name, a path, or any health
            // value. Prefixed "Import: " so it's distinguishable from WHOOP / generic-HR lines. The Swift
            // twin logs the same in DataSourcesView's import handlers.
            if (summary.totalRows > 0) {
                val countsText = summary.counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
                vm.ble.externalLog("Import ${summary.source}: $countsText")
            } else {
                vm.ble.externalLog("Import ${summary.source} failed: ${summary.message}")
            }
            refreshCounts()
            busy = false
            Toast.makeText(context, summary.message, Toast.LENGTH_LONG).show()
        }
    }

    // SAF pickers — the importers auto-detect zip vs csv/xml from the file's content.
    val whoopImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { WhoopCsvImporter.importZip(context, uri, vm.repo) } }

    val appleImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { AppleHealthImporter.importExport(context, uri, vm.repo) } }

    val xiaomiImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { XiaomiBandImporter.importExport(context, uri, vm.repo) } }

    val nutritionImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { NutritionCsvImporter.importCsv(context, uri, vm.repo) } }

    // Lifting: imported workouts also need the Workouts list to reload (runImport only re-counts).
    val liftingImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) runImport {
            LiftingImporter.importExport(context, uri, vm.repo).also { vm.loadWorkouts() }
        }
    }

    // Oura / Fitbit / Garmin own-data export: daily metrics + sleep sessions under the brand's source.
    val wearableImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { WearableExportImporter.importExport(context, uri, vm.repo) } }

    // Workout file (GPX / TCX / FIT): one imported activity → one workout; reload the Workouts list too.
    val activityFileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) runImport {
            ActivityFileImporter.importExport(context, uri, vm.repo).also { vm.loadWorkouts() }
        }
    }

    // Health Connect permission request → import once granted.
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.any { it in HealthConnectImporter.PERMISSIONS }) {
            runImport { HealthConnectImporter.import(context, vm.repo) }
        } else {
            Toast.makeText(context, context.getString(R.string.datasources_hc_access_denied), Toast.LENGTH_LONG).show()
        }
    }

    val healthConnectAvailable = remember {
        HealthConnectImporter.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    // Hoisted content descriptions (the semantics{} lambdas below aren't @Composable scopes).
    val hcAutoSyncCd = stringResource(R.string.datasources_hc_autosync_cd)
    val hcWritebackCd = stringResource(R.string.datasources_hc_writeback_cd)
    val broadcastHrCd = stringResource(R.string.data_sources_cd_broadcast_heart_rate_as_a)

    // "Broadcast heart rate": flip the toggle on only AFTER the BLUETOOTH_ADVERTISE (+ CONNECT) runtime
    // permission is granted on Android 12+ — otherwise advertising silently no-ops. On grant (or pre-12,
    // where it's install-time) the VM starts the HR peripheral.
    val requestAdvertise = rememberRequestAdvertise(onGranted = { vm.setHrBroadcast(true) })

    // Import directly if permissions already granted, otherwise request them first.
    fun startHealthConnect() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            if (granted.any { it in HealthConnectImporter.PERMISSIONS }) {
                runImport { HealthConnectImporter.import(context, vm.repo) }
            } else {
                hcPermissionLauncher.launch(HealthConnectImporter.PERMISSIONS)
            }
        }
    }

    // Writeback (computed metrics → Health Connect): WRITE permissions, requested only when the
    // user opts in. Denial flips the toggle back off so the UI never claims it's writing.
    val hcWritePermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) {
            vm.writebackHealthConnectNow()
        } else {
            vm.setHcWriteback(false)
            Toast.makeText(context, context.getString(R.string.datasources_hc_write_access_denied), Toast.LENGTH_LONG).show()
        }
    }

    // Write immediately if the write permissions are already granted, otherwise request them first.
    fun startWriteback() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            // Gate on vitals AND exercise perms so a user who enabled writeback before exercise
            // writeback shipped (vitals-only grant) still gets re-prompted for WRITE_EXERCISE/
            // WRITE_DISTANCE — otherwise their workouts silently never reach Health Connect (#412).
            if (granted.containsAll(HealthConnectWriter.PERMISSIONS + HealthConnectWriter.EXERCISE_PERMISSIONS)) {
                vm.writebackHealthConnectNow()
            } else {
                // Request vitals + exercise-session write perms together so GPS workouts can write
                // back too (the launcher-result handler stays keyed on the vital PERMISSIONS, so
                // exercise writeback is opt-in + non-fatal if the user declines it). v1.71 / #412.
                hcWritePermissionLauncher.launch(HealthConnectWriter.PERMISSIONS + HealthConnectWriter.EXERCISE_PERMISSIONS)
            }
        }
    }

    // PERF (#707): lazy scaffold — each SourceCard is an unconditional top-level child, so each becomes one
    // `item { }` in the same order. There are no standalone Spacers (the eager column relied on
    // `spacedBy(20.dp)`, which the LazyColumn reproduces), so spacing is byte-identical. Only the on-screen
    // cards now compose + get accessibility-walked on scroll — this list of 11 source cards is long. The
    // confirm dialogs below the scaffold are untouched.
    LazyScreenScaffold(
        title = stringResource(R.string.datasources_title),
        subtitle = stringResource(R.string.datasources_subtitle),
    ) {
        // --- WHOOP data (cached history) ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_whoop_title),
            icon = Icons.Filled.MonitorHeart,
            subtitle = stringResource(R.string.datasources_whoop_subtitle),
        ) {
            StatePill(
                title = if (whoopHasHr) stringResource(R.string.datasources_whoop_streaming) else stringResource(R.string.datasources_whoop_no_samples),
                tone = if (whoopHasHr) StrandTone.Positive else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = whoopDays?.let { stringResource(R.string.datasources_days, it) } ?: "—",
                secondary = whoopWorkouts?.let { stringResource(R.string.datasources_workouts_stored, it) } ?: stringResource(R.string.datasources_counting),
            )
            BackupButton(
                label = stringResource(R.string.datasources_whoop_import_button),
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { whoopImportLauncher.launch(arrayOf("*/*")) }
        }
        }

        // --- Apple Health ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_apple_title),
            icon = Icons.Filled.FavoriteBorder,
            tint = Palette.metricCyan,
            subtitle = stringResource(R.string.datasources_apple_subtitle),
        ) {
            val hasApple = (appleDays ?: 0) > 0 || (appleWorkouts ?: 0) > 0
            StatePill(
                title = if (hasApple) stringResource(R.string.datasources_imported) else stringResource(R.string.datasources_nothing_imported),
                tone = if (hasApple) StrandTone.Accent else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = appleDays?.let { stringResource(R.string.datasources_days, it) } ?: "—",
                secondary = appleWorkouts?.let { stringResource(R.string.datasources_workouts, it) } ?: stringResource(R.string.datasources_counting),
            )
            BackupButton(
                label = stringResource(R.string.datasources_apple_import_button),
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                tint = Palette.metricCyan,
                modifier = Modifier.fillMaxWidth(),
            ) { appleImportLauncher.launch(arrayOf("*/*")) }
            // ah-delete (#616): a destructive "Remove imported data" action wired to
            // DeviceRegistry.deleteDeviceData("apple-health") (via vm.deletePairedDeviceData), mirroring
            // the Swift card. Shown only once there's something to remove; a confirm dialog gates it.
            if (hasApple) {
                BackupButton(
                    label = stringResource(R.string.datasources_remove_imported),
                    icon = Icons.Filled.DeleteOutline,
                    enabled = !busy,
                    tint = Palette.statusCritical,
                    modifier = Modifier.fillMaxWidth(),
                ) { confirmDeleteApple = true }
            }
        }
        }

        // --- Health Connect (native Android health data) ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_hc_title),
            icon = Icons.Filled.MonitorHeart,
            subtitle = stringResource(R.string.datasources_hc_subtitle),
        ) {
            val hasHc = (hcDays ?: 0) > 0 || (hcWorkouts ?: 0) > 0
            if (hasHc) {
                StatePill(title = stringResource(R.string.datasources_imported), tone = StrandTone.Accent, showsDot = true)
                CountLine(
                    primary = hcDays?.let { stringResource(R.string.datasources_days, it) } ?: "—",
                    secondary = hcWorkouts?.let { stringResource(R.string.datasources_workouts, it) } ?: stringResource(R.string.datasources_counting),
                )
            }
            if (healthConnectAvailable) {
                BackupButton(
                    label = stringResource(R.string.datasources_hc_import_button),
                    icon = Icons.Filled.FileUpload,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { startHealthConnect() }

                // Auto-sync: pull new Health Connect data when you open NOOP, if it's been longer than
                // the chosen interval — no manual taps. On-open only (no background worker): it avoids a
                // sensitive background-health permission and is reliable, and opening the app is enough.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.datasources_hc_autosync_label), style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            stringResource(R.string.datasources_hc_autosync_desc),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = hcAutoSync,
                        onCheckedChange = { on ->
                            vm.setHcAutoSync(on)
                            // Ensure permissions (and an immediate first sync) when turning it on.
                            if (on) startHealthConnect()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = hcAutoSyncCd
                        },
                    )
                }
                if (hcAutoSync) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.datasources_hc_every), style = NoopType.footnote, color = Palette.textSecondary)
                        val hcHourLabels = LinkedHashMap<Int, String>()
                        for (h in listOf(6, 12, 24)) hcHourLabels[h] = stringResource(R.string.datasources_hc_hours, h)
                        SegmentedPillControl(
                            items = listOf(6, 12, 24),
                            selection = hcSyncHours,
                            label = { hcHourLabels[it] ?: "" },
                            onSelect = { vm.setHcSyncHours(it) },
                        )
                    }
                    Text(
                        stringResource(
                            R.string.datasources_hc_last_sync,
                            if (hcLastSync == 0L) stringResource(R.string.datasources_hc_last_sync_never)
                            else DateUtils.getRelativeTimeSpanString(hcLastSync).toString(),
                        ),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }

                // Writeback: the inverse direction. Opt-in, default OFF, computed metrics only.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.datasources_hc_writeback_label), style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            stringResource(R.string.data_sources_write_the_metrics_noop_computes),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = hcWriteback,
                        onCheckedChange = { on ->
                            vm.setHcWriteback(on)
                            // Ensure write permissions (and an immediate first write) when turning on.
                            if (on) startWriteback()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = hcWritebackCd
                        },
                    )
                }
            } else {
                RoadmapNote(stringResource(R.string.datasources_hc_not_available))
            }
        }
        }

        // --- Nutrition CSV (calories / macros / body weight) ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_nutrition_title),
            icon = Icons.Filled.Restaurant,
            tint = Palette.metricAmber,
            subtitle = stringResource(R.string.datasources_nutrition_subtitle),
        ) {
            val hasNutrition = (nutritionDays ?: 0) > 0 || (nutritionWeighIns ?: 0) > 0
            StatePill(
                title = if (hasNutrition) stringResource(R.string.datasources_imported) else stringResource(R.string.datasources_nothing_imported),
                tone = if (hasNutrition) StrandTone.Accent else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = nutritionDays?.let { stringResource(R.string.datasources_days_logged, it) } ?: "—",
                secondary = nutritionWeighIns?.let { stringResource(R.string.datasources_weighins, it) } ?: stringResource(R.string.datasources_counting),
            )
            BackupButton(
                label = stringResource(R.string.datasources_nutrition_import_button),
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { nutritionImportLauncher.launch(arrayOf("*/*")) }
        }
        }

        // --- Xiaomi Mi Band (Mi Fitness on-device DB) — #35 ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_xiaomi_title),
            icon = Icons.Filled.Watch,
            tint = Palette.metricPurple,
            subtitle = stringResource(R.string.datasources_xiaomi_subtitle),
        ) {
            val hasXiaomi = (xiaomiDays ?: 0) > 0
            StatePill(
                title = if (hasXiaomi) stringResource(R.string.datasources_imported) else stringResource(R.string.datasources_nothing_imported),
                tone = if (hasXiaomi) StrandTone.Accent else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = xiaomiDays?.let { stringResource(R.string.datasources_days_imported, it) } ?: "—",
                secondary = if (xiaomiDays == null) stringResource(R.string.datasources_counting) else stringResource(R.string.datasources_miband_models),
            )
            BackupButton(
                label = stringResource(R.string.datasources_xiaomi_import_button),
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { xiaomiImportLauncher.launch(arrayOf("*/*")) }
        }
        }

        // --- Lifting log (Hevy CSV / Liftosaur JSON) ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_lifting_title),
            icon = Icons.Filled.FitnessCenter,
            tint = DomainTheme.Effort.color,
            subtitle = stringResource(R.string.datasources_lifting_subtitle),
        ) {
            val hasLifting = (liftingWorkouts ?: 0) > 0
            StatePill(
                title = if (hasLifting) stringResource(R.string.datasources_imported) else stringResource(R.string.datasources_nothing_imported),
                tone = if (hasLifting) StrandTone.Accent else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = liftingWorkouts?.let { stringResource(R.string.datasources_workouts, it) } ?: "—",
                secondary = stringResource(R.string.datasources_lifting_volume_note),
            )
            BackupButton(
                label = stringResource(R.string.datasources_lifting_import_button),
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { liftingImportLauncher.launch(arrayOf("*/*")) }
        }
        }

        // --- Workout file (GPX / TCX / FIT) — any brand, on-device ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_activity_title),
            icon = Icons.Filled.Map,
            tint = Palette.metricAmber,
            subtitle = stringResource(R.string.datasources_activity_subtitle),
        ) {
            val hasFiles = (activityFiles ?: 0) > 0
            StatePill(
                title = if (hasFiles) stringResource(R.string.datasources_imported) else stringResource(R.string.datasources_nothing_imported),
                tone = if (hasFiles) StrandTone.Accent else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = activityFiles?.let { stringResource(R.string.datasources_workouts, it) } ?: "—",
                secondary = stringResource(R.string.datasources_gpx_one_per_file),
            )
            BackupButton(
                label = stringResource(R.string.datasources_activity_import_button),
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { activityFileImportLauncher.launch(arrayOf("*/*")) }
        }
        }

        // --- Oura / Fitbit / Garmin own-data export — on-device ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_wearable_title),
            icon = Icons.Filled.Watch,
            tint = Palette.metricPurple,
            subtitle = stringResource(R.string.datasources_wearable_subtitle),
        ) {
            val hasDays = (wearableDays ?: 0) > 0
            StatePill(
                title = if (hasDays) stringResource(R.string.datasources_imported) else stringResource(R.string.datasources_nothing_imported),
                tone = if (hasDays) StrandTone.Accent else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = wearableDays?.let { stringResource(R.string.datasources_day_metrics, it) } ?: "—",
                secondary = stringResource(R.string.datasources_wearable_brands),
            )
            BackupButton(
                label = stringResource(R.string.datasources_wearable_import_button),
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { wearableImportLauncher.launch(arrayOf("*/*")) }
        }
        }

        // --- Broadcast heart rate (NOOP as a standard BLE HR peripheral) ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_broadcast_title),
            icon = Icons.Filled.MonitorHeart,
            tint = DomainTheme.Effort.color,
            subtitle = stringResource(R.string.datasources_broadcast_subtitle),
        ) {
            if (hrBroadcast) {
                val (label, tone) =
                    if (hrBroadcastAdvertising) stringResource(R.string.datasources_broadcast_broadcasting) to StrandTone.Positive
                    else stringResource(R.string.datasources_broadcast_starting) to StrandTone.Warning
                StatePill(title = label, tone = tone, showsDot = true, pulsing = !hrBroadcastAdvertising)
                CountLine(
                    primary = if (hrBroadcastAdvertising) stringResource(R.string.datasources_broadcast_sensor) else "—",
                    secondary = when {
                        hrBroadcastSubscribers > 0 ->
                            stringResource(R.string.datasources_broadcast_devices_reading, hrBroadcastSubscribers)
                        live.heartRate != null -> stringResource(R.string.datasources_broadcast_sharing_bpm, live.heartRate!!)
                        else -> stringResource(R.string.datasources_broadcast_no_hr)
                    },
                )
            } else {
                // Parity with the Swift card, which shows an explicit "Off" pill when the toggle is off
                // (DataSourcesView.broadcastHrCard: StatePill("Off", tone: .neutral, showsDot: false)).
                StatePill(title = stringResource(R.string.datasources_broadcast_off), tone = StrandTone.Neutral, showsDot = false)
            }
            hrBroadcastStatus?.let { note ->
                Text(note, style = NoopType.footnote, color = Palette.statusWarning)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_exp_broadcast_hr_cd), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        stringResource(R.string.data_sources_acts_as_a_standard_bluetooth_heart),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = hrBroadcast,
                    onCheckedChange = { on ->
                        // Turning ON requests BLUETOOTH_ADVERTISE first (the VM flips on once granted);
                        // turning OFF stops the peripheral immediately.
                        if (on) requestAdvertise() else vm.setHrBroadcast(false)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = broadcastHrCd
                    },
                )
            }

            // #573: leaving broadcast on keeps the radio advertising continuously, which drains the
            // battery faster — make that visible and persistent so it isn't left on by accident. Mirrors
            // the Swift broadcast-HR warning (SettingsView).
            if (hrBroadcast) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.SettingsInputAntenna,
                        contentDescription = null,
                        tint = Palette.statusWarning,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.data_sources_broadcast_hr_is_on_your_strap),
                        style = NoopType.caption,
                        color = Palette.statusWarning,
                    )
                }
            }
        }
        }

        // --- Live WHOOP strap over BLE ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_ble_title),
            icon = Icons.Filled.Bluetooth,
            subtitle = stringResource(R.string.datasources_ble_subtitle),
        ) {
            val (label, tone) = when {
                live.bonded -> stringResource(R.string.datasources_ble_bonded) to StrandTone.Positive
                live.connected -> stringResource(R.string.datasources_ble_connecting) to StrandTone.Warning
                else -> stringResource(R.string.datasources_ble_disconnected) to StrandTone.Critical
            }
            StatePill(title = label, tone = tone, showsDot = true, pulsing = live.connected && !live.bonded)
        }
        }

        // --- Whole-store backup (the real Android migration path) ---
        item {
        SourceCard(
            title = stringResource(R.string.datasources_backup_title),
            icon = Icons.Filled.FileDownload,
            subtitle = stringResource(R.string.datasources_backup_subtitle),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackupButton(
                    label = stringResource(R.string.datasources_backup_export),
                    icon = Icons.Filled.FileDownload,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { exportLauncher.launch("noop-backup-${java.time.LocalDate.now()}.noopbak") }
                BackupButton(
                    label = stringResource(R.string.datasources_backup_import),
                    icon = Icons.Filled.FileUpload,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { importLauncher.launch(arrayOf("*/*")) }
            }
            if (busy) {
                Text(stringResource(R.string.datasources_backup_working), style = NoopType.footnote, color = Palette.textTertiary)
            }
            if (restartNeeded) {
                Text(
                    stringResource(R.string.datasources_backup_restart),
                    style = NoopType.subhead,
                    color = Palette.statusWarning,
                )
            }
        }
        }
    }

    // ah-delete (#616): strongly-worded confirm before purging the "apple-health" source. On confirm,
    // deletes every Apple-Health-sourced row (deviceId-keyed tables) in one transaction via the registry,
    // re-counts so the card flips back to "Nothing imported", and toasts the result.
    if (confirmDeleteApple) {
        // Hoisted out of the coroutine below: stringResource is @Composable-only and can't be
        // called inside scope.launch { }.
        val removedToast = stringResource(R.string.datasources_ah_delete_toast)
        AlertDialog(
            onDismissRequest = { confirmDeleteApple = false },
            containerColor = Palette.surfaceOverlay,
            title = {
                Text(stringResource(R.string.datasources_ah_delete_title), style = NoopType.title2, color = Palette.textPrimary)
            },
            text = {
                Text(
                    stringResource(R.string.datasources_ah_delete_body),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteApple = false
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { vm.deletePairedDeviceData("apple-health") }
                        }
                        vm.ble.externalLog("Import apple-health: imported data removed")
                        refreshCounts()
                        vm.loadWorkouts()
                        busy = false
                        Toast.makeText(context, removedToast, Toast.LENGTH_LONG).show()
                    }
                }) {
                    Text(stringResource(R.string.devices_menu_remove), style = NoopType.body, color = Palette.statusCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteApple = false }) {
                    Text(stringResource(R.string.sleep_cancel), style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
    }
}

// MARK: - Source card (mirrors the macOS private `card(...)` builder)

@Composable
private fun SourceCard(
    title: String,
    icon: ImageVector,
    subtitle: String,
    tint: Color = Palette.accent,
    content: @Composable () -> Unit,
) {
    // A frosted, domain-tinted card: a tinted source glyph chip + title, the explainer line, then
    // the source's status pill + connect/import action(s). Replaces the old flat surface.
    NoopCard(padding = 18.dp, tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
            }
            Text(subtitle, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

// MARK: - "N days · N workouts stored" footnote line (mirrors the macOS counts line)

@Composable
private fun CountLine(primary: String, secondary: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(primary, style = NoopType.captionNumber, color = Palette.textSecondary)
        Text("  ·  ", style = NoopType.footnote, color = Palette.textTertiary)
        Text(secondary, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun RoadmapNote(text: String) {
    Text(text, style = NoopType.footnote, color = Palette.textTertiary)
}

// MARK: - Backup action button (matches the accent fill used by CoachPrimaryButton)

@Composable
private fun BackupButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Palette.accent,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val ink = if (enabled) tint else tint.copy(alpha = Palette.disabledOpacity)
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, ink.copy(alpha = 0.4f), shape)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = NoopType.headline, color = ink)
    }
}
