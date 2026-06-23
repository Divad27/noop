package com.noop.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.BuildConfig
import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.Zones
import com.noop.ble.PuffinExperiment
import com.noop.ble.WhoopModel
import com.noop.data.DataBackup
import com.noop.ingest.RawSensorExport
import com.noop.ingest.WhoopCsvExporter
import com.noop.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// MARK: - Settings (ported from Strand/Screens/SettingsView.swift)
//
// Profile (the numbers that power HR zones / calories / recovery baselines), a
// Backup & restore section wiring DataBackup export/import through the Storage
// Access Framework, and an About section with version + attribution + a Support
// link. Re-skinned to the locked NOOP component system: every surface is a
// NoopCard, every status uses StatePill, the two-column form feel is preserved.
//
// macOS parity notes:
//  - macOS persisted the profile in a ProfileStore (ObservableObject on disk). The
//    Android equivalent is SharedPreferences; this screen owns the only profile
//    store in the app, so HealthScreen's age-agnostic HR-max default can later read
//    from it. Values persist immediately on every change.
//  - macOS used native +/- Steppers; Compose has no Stepper, so each numeric field
//    is a tabular value flanked by round −/+ buttons (same intent, same ranges).
//  - The strap "Re-scan / Disconnect" controls map to the ViewModel's connect() /
//    disconnect() pass-throughs.
//  - Backup export/import run through SAF (CreateDocument / OpenDocument); the macOS
//    alert is mirrored by a Toast. DataBackup.exportTo already checkpoints the WAL,
//    so no separate repo checkpoint call is needed.

// MARK: - Profile store (SharedPreferences-backed; the macOS ProfileStore equivalent)

/**
 * The user's body profile — age / sex / weight / height plus an optional manual
 * HR-max override. Persisted to SharedPreferences so the values survive restarts
 * and other screens (HealthScreen, Coach zones) can read the same source of truth.
 *
 * Mirrors the macOS `ProfileStore` fields and ranges exactly. `hrMaxOverride == 0`
 * means "auto" — fall back to the Tanaka estimate from [age].
 */
class ProfileStore(private val prefs: SharedPreferences) {

    var age: Int
        get() = prefs.getInt(KEY_AGE, 30).coerceIn(AGE_MIN, AGE_MAX)
        set(v) = prefs.edit().putInt(KEY_AGE, v.coerceIn(AGE_MIN, AGE_MAX)).apply()

    /** "male" | "female" | "nonbinary" — matches the macOS tag values. */
    var sex: String
        get() = prefs.getString(KEY_SEX, "male") ?: "male"
        set(v) = prefs.edit().putString(KEY_SEX, v).apply()

    var weightKg: Double
        get() = prefs.getFloat(KEY_WEIGHT, 75f).toDouble().coerceIn(WEIGHT_MIN, WEIGHT_MAX)
        set(v) = prefs.edit().putFloat(KEY_WEIGHT, v.coerceIn(WEIGHT_MIN, WEIGHT_MAX).toFloat()).apply()

    var heightCm: Double
        get() = prefs.getFloat(KEY_HEIGHT, 178f).toDouble().coerceIn(HEIGHT_MIN, HEIGHT_MAX)
        set(v) = prefs.edit().putFloat(KEY_HEIGHT, v.coerceIn(HEIGHT_MIN, HEIGHT_MAX).toFloat()).apply()

    /**
     * Waist circumference in cm; 0 = unset (the Fitness Age VO₂max estimate is hidden until a waist
     * is entered). Optional — it only unlocks the VO₂max read-out and never moves the headline Fitness
     * Age (the engine's body term cancels). No coercion floor (0 has to remain a sentinel for "unset");
     * the upper bound is clamped so a fat-fingered entry can't run away.
     */
    var waistCm: Double
        get() = prefs.getFloat(KEY_WAIST, 0f).toDouble().coerceIn(0.0, WAIST_MAX)
        set(v) = prefs.edit().putFloat(KEY_WAIST, v.coerceIn(0.0, WAIST_MAX).toFloat()).apply()

    /** Manual max-heart-rate override in bpm; 0 = automatic (Tanaka). */
    var hrMaxOverride: Int
        get() = prefs.getInt(KEY_HRMAX, 0).coerceIn(0, 230)
        set(v) = prefs.edit().putInt(KEY_HRMAX, v.coerceIn(0, 230)).apply()

    /**
     * Step-calibration divisor (#139/#132): counter ticks per real step for the @57 motion
     * counter. 1.0 = raw pass-through (default — no behavior change). Clamped 0.5–30.0
     * (WHOOP 5/MG motion-counter overcount can reach ~24×, so the ceiling has to be high).
     */
    var stepTicksPerStep: Double
        get() = prefs.getFloat(KEY_STEP_SCALE, 1f).toDouble().coerceIn(STEP_SCALE_MIN, STEP_SCALE_MAX)
        set(v) = prefs.edit()
            .putFloat(KEY_STEP_SCALE, v.coerceIn(STEP_SCALE_MIN, STEP_SCALE_MAX).toFloat())
            .apply()

    // ── Steps ESTIMATE calibration (WHOOP 4.0; StepsEstimateEngine) ─────────────────────────────
    // Mirror of the macOS ProfileStore fields: the engine writes the auto-fit each analytics pass and
    // the Settings/Steps screen reads them. [stepsManualCoefficient] is the ONLY user-settable field
    // (0 = auto-fit / null to the engine; > 0 = manual override fed into calibrate()); the other three
    // are fitted outputs surfaced read-only.
    /** Fitted (or manually-set) steps-per-unit-of-motion coefficient last persisted by the engine. */
    var stepsCalibrationCoefficient: Double
        get() = prefs.getFloat(KEY_STEPS_COEFF, 0f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_STEPS_COEFF, v.toFloat()).apply()

    /** How many calibration days fed the last auto-fit (0 when purely manual / not yet fit). */
    var stepsCalibrationSampleDays: Int
        get() = prefs.getInt(KEY_STEPS_SAMPLE_DAYS, 0)
        set(v) = prefs.edit().putInt(KEY_STEPS_SAMPLE_DAYS, v).apply()

    /** 0–1 trust in the last fit (1.0 for a manual coefficient). */
    var stepsCalibrationConfidence: Double
        get() = prefs.getFloat(KEY_STEPS_CONFIDENCE, 0f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_STEPS_CONFIDENCE, v.toFloat()).apply()

    /** True when the persisted coefficient came from the user's manual override, not an auto-fit. */
    var stepsCalibrationManual: Boolean
        get() = prefs.getBoolean(KEY_STEPS_MANUAL_FLAG, false)
        set(v) = prefs.edit().putBoolean(KEY_STEPS_MANUAL_FLAG, v).apply()

    /** User-set manual coefficient. 0 = auto-fit (null to the engine); > 0 = manual override. */
    var stepsManualCoefficient: Double
        get() = prefs.getFloat(KEY_STEPS_MANUAL_COEFF, 0f).toDouble().coerceAtLeast(0.0)
        set(v) = prefs.edit().putFloat(KEY_STEPS_MANUAL_COEFF, v.coerceAtLeast(0.0).toFloat()).apply()

    /** The manual override to feed into `StepsEstimateEngine.calibrate(points, manualOverride)`:
     *  null when 0 (auto-fit), the positive value otherwise. */
    val stepsManualOverride: Double? get() = stepsManualCoefficient.takeIf { it > 0 }

    /** The auto (Tanaka) HR-max for the current age. */
    val hrMaxAuto: Int get() = Zones.hrMaxTanaka(age)

    /** Effective HR-max: the manual override if set, else the Tanaka estimate. */
    val hrMax: Int get() = if (hrMaxOverride > 0) hrMaxOverride else hrMaxAuto

    companion object {
        private const val PREFS = "noop_profile"
        private const val KEY_AGE = "age"
        private const val KEY_SEX = "sex"
        private const val KEY_WEIGHT = "weight_kg"
        private const val KEY_HEIGHT = "height_cm"
        private const val KEY_WAIST = "waist_cm"
        private const val KEY_HRMAX = "hr_max_override"
        private const val KEY_STEP_SCALE = "step_ticks_per_step"
        private const val KEY_STEPS_COEFF = "steps_calibration_coefficient"
        private const val KEY_STEPS_SAMPLE_DAYS = "steps_calibration_sample_days"
        private const val KEY_STEPS_CONFIDENCE = "steps_calibration_confidence"
        private const val KEY_STEPS_MANUAL_FLAG = "steps_calibration_manual"
        private const val KEY_STEPS_MANUAL_COEFF = "steps_manual_coefficient"

        private const val AGE_MIN = 13
        private const val AGE_MAX = 100
        private const val WEIGHT_MIN = 30.0
        private const val WEIGHT_MAX = 250.0
        private const val HEIGHT_MIN = 120.0
        private const val HEIGHT_MAX = 230.0
        private const val WAIST_MAX = 200.0
        private const val STEP_SCALE_MIN = 0.5
        private const val STEP_SCALE_MAX = 30.0

        /**
         * Variable step for the calibration stepper so high values stay reachable: fine near the
         * 1.0 default (where most people land), coarse up at the 20s+ a 5/MG needs. A flat 0.1 step
         * from 0.5 to 30 would be ~295 taps — unusable. Mirrors macOS `ProfileStore.stepScaleIncrement`.
         *  - `< 2.0` → 0.1   (precision around the default)
         *  - `2.0–5.0` → 0.5
         *  - `>= 5.0` → 1.0   (ballpark the ~24× overcount in ~19 taps)
         */
        fun stepScaleIncrement(value: Double): Double = when {
            value < 2.0 -> 0.1
            value < 5.0 -> 0.5
            else -> 1.0
        }

        /**
         * One increment/decrement of the calibration divisor, snapped to the increment grid and
         * clamped to [STEP_SCALE_MIN]..[STEP_SCALE_MAX]. Decrement uses the increment for the
         * *target* band so the up/down sequence is symmetric at band boundaries (e.g. 5.0 −1 → 4.0,
         * 4.0 +0.5 → 4.5). Mirrors macOS `ProfileStore.steppedStepScale`.
         */
        fun steppedStepScale(value: Double, up: Boolean): Double {
            val delta = if (up) stepScaleIncrement(value) else stepScaleIncrement(value - 0.0001)
            val next = Math.round((value + if (up) delta else -delta) / delta) * delta
            return next.coerceIn(STEP_SCALE_MIN, STEP_SCALE_MAX)
        }

        fun from(context: Context): ProfileStore =
            ProfileStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

// MARK: - Screen

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by vm.live.collectAsStateWithLifecycle()

    // The profile store is stable for the lifetime of this screen; a version counter
    // forces recomposition after each mutating write (SharedPreferences isn't reactive).
    val profile = remember { ProfileStore.from(context) }
    var rev by remember { mutableStateOf(0) }
    fun mutate(block: () -> Unit) { block(); rev++ }

    var backupBusy by remember { mutableStateOf(false) }

    // Re-scan must request the runtime Bluetooth permission before scanning — without this the
    // button calls connect() directly and silently no-ops on Android 12+ when the permission was
    // denied/revoked (issue #1). Shared with Live's Connect via the one rememberRequestScan gate.
    val requestScan = rememberRequestScan { vm.connect() }

    // "What's New" changelog sheet, reachable any time from About (mirrors the macOS
    // Settings → About "What's new" button). Persistence/gating lives in NoopRoot; this
    // is a manual re-open and writes nothing.
    var showWhatsNew by remember { mutableStateOf(false) }

    // "How your scores work" explainer sheet, reachable any time from About (macOS/iOS parity).
    var showScoringGuide by remember { mutableStateOf(false) }

    // "How NOOP works" primer sheet (COMPONENT 5 of the explainability layer), reachable any time
    // from About — the plain-English tour of sleep sorting, scores, recording and provenance.
    var showHowNoopWorks by remember { mutableStateOf(false) }

    // "WHOOP 4.0 vs 5.0/MG: what each can read and why" explainer (FI-2 / #490), reachable from the
    // Strap section by BOTH model owners. Clears up which features each strap supports — e.g. why the
    // strap-firmware broadcast-out is 5/MG-only while NOOP's own re-broadcast works on any strap.
    var showModelComparison by remember { mutableStateOf(false) }

    // "Recalibrate Charge baseline" confirm dialog (Charge advanced). Writes now-seconds to BOTH the
    // noop.hrvBaselineEpoch and noop.recoveryBaselineEpoch prefs so foldHistory re-seeds every baseline
    // that feeds Charge from tonight onward; the standing analyze loop picks it up on its next pass.
    // Fixes a baseline poisoned by a bad first week (worn sick, or early nights that anchored too high).
    var showRecalibrateConfirm by remember { mutableStateOf(false) }

    // Steps-estimate calibration screen (WHOOP 4.0), reached from the Profile card's "Steps estimate"
    // tap-through. Mirrors the macOS StepsCalibrationSheet: honest explainer + current fit + a recent
    // estimated-vs-phone table + a manual coefficient override. Full-screen Dialog like the guide above.
    var showStepsCalibration by remember { mutableStateOf(false) }

    // EXPERIMENTAL WHOOP 5/MG protocol probes (off by default). Mirrors the macOS @AppStorage toggle;
    // SharedPreferences isn't reactive, so the Switch drives a local mutableState that the store reads.
    val puffinExperiment = remember { PuffinExperiment.from(context) }
    var puffinExperiments by remember { mutableStateOf(puffinExperiment.isEnabled) }
    var puffinCapture by remember { mutableStateOf(puffinExperiment.isCaptureEnabled) }
    var deepData by remember { mutableStateOf(puffinExperiment.isDeepDataEnabled) }
    var broadcastHr by remember { mutableStateOf(puffinExperiment.broadcastHr) }
    // Opt-in "Experimental sleep staging (V2)" (off by default). Model-agnostic, so it lives outside the
    // 5/MG-only card — it works on WHOOP 4 and 5. Re-stages detected nights with SleepStagerV2; V1 default.
    var experimentalSleepV2 by remember { mutableStateOf(puffinExperiment.experimentalSleepV2) }

    // Whether to surface the WHOOP 5/MG-only probes (puffin / R22 / broadcast-HR / frame-capture). Gated
    // so a confident 4.0 owner never sees 5/MG controls that can't touch their strap (#22). The model
    // preference DEFAULTS to WHOOP4, so we deliberately do NOT hide on the raw default alone — the same
    // "noop.selectedWhoopModel" key is rewritten to the family that actually advertised when a strap
    // connects (WhoopBleClient.persistSelectedModel, PR#195), so a real 5/MG owner who never opened the
    // model picker still flips this true once their strap is discovered. We also show it whenever a 5/MG
    // is live-detected this session. Hide only when the user is confidently on a 4.0 (pref says WHOOP4
    // AND nothing 5/MG is connected). Mirrors the macOS SettingsView `showFiveMGControls` gate.
    val selectedModelName = remember(rev) {
        context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE)
            .getString("noop.selectedWhoopModel", null)
    }
    val showFiveMGControls = selectedModelName == WhoopModel.WHOOP5_MG.name || live.whoop5Detected

    // "Keep connected in the background" — drives WhoopConnectionService (foreground service). Default
    // on. SharedPreferences isn't reactive, so the Switch mirrors into a local state.
    var backgroundConnection by remember { mutableStateOf(NoopPrefs.backgroundConnection(context)) }

    // "Continuous HRV capture" — hold the dense realtime stream armed 24/7 (better overnight HRV) at the
    // cost of more battery. Default OFF; only does anything with background connection on. Local mirror.
    var continuousHrv by remember { mutableStateOf(NoopPrefs.continuousHrv(context)) }

    // "Debug logging" — mirror the strap log to logcat (adb). Default OFF so normal users don't.
    var debugLogging by remember { mutableStateOf(NoopPrefs.debugLogging(context)) }

    // --- v5 Health & wellness toggle group. All SharedPreferences-backed (not reactive), so each Switch
    // drives a local mirror that writes straight through to the same keys the v5 engine readers use.
    // Illness watch routes through the ViewModel so the banner recomputes live; the rest are pref writes
    // the engines pick up on the next analytics pass / offload. All opt-in / safe-default per spec.
    var illnessWatch by remember { mutableStateOf(NoopPrefs.illnessWatch(context)) }
    var cycleTracking by remember { mutableStateOf(NoopPrefs.cycleTracking(context)) }
    var hydrationTracking by remember { mutableStateOf(NoopPrefs.hydrationTracking(context)) }
    var stressCheckIn by remember { mutableStateOf(BiofeedbackPrefs.checkInEnabled(context)) }
    var stressAutoNudge by remember { mutableStateOf(BiofeedbackPrefs.autoNudge(context)) }
    var rhythmEnabled by remember { mutableStateOf(RhythmConsent.isEnabled(context)) }
    var coachSignals by remember { mutableStateOf(NoopPrefs.coachSignals(context)) }
    var autoDetectWorkouts by remember { mutableStateOf(NoopPrefs.autoDetectWorkouts(context)) }

    // Scheduled debug export (#510) — the daily auto-export toggle + time-of-day. The settings object is
    // its own SharedPreferences store; SharedPreferences isn't reactive, so the Switch + TimeChip mirror
    // into local state and write straight through, then (re)schedule via DebugExportScheduler.
    val debugExportSettings = remember { DebugExportSettings.from(context) }
    var debugExportEnabled by remember { mutableStateOf(debugExportSettings.enabled) }
    var debugExportMinutes by remember { mutableStateOf(debugExportSettings.timeMinutes) }

    // Imperial/Metric display preference (D#103). Display-only — stored data stays SI. The system drives
    // the profile fields below (imperial entry) too, so it's local state the whole screen reads.
    // `temperatureRaw` is "" (match the system) or a TemperatureUnit raw value. SharedPreferences isn't
    // reactive, so these mirror into local state like the toggles above.
    var unitSystem by remember { mutableStateOf(UnitPrefs.system(context)) }
    var temperatureRaw by remember {
        mutableStateOf(NoopPrefs.of(context).getString(NoopPrefs.KEY_TEMPERATURE_UNIT, "") ?: "")
    }
    // Effort display scale (#268) — show NOOP's native 0–100 Effort or WHOOP's 0–21 Day Strain axis.
    // Display-only; the stored value never changes. Mirrors into local state like the toggles above.
    var effortScale by remember { mutableStateOf(UnitPrefs.effortScale(context)) }

    // App icon (v3 "Titanium & Gold") — machined-titanium (.IconDefault) or blued-titanium (.IconNavy).
    // SharedPreferences isn't reactive, so the segmented control drives this local mirror; flipping it
    // enables exactly one launcher alias via PackageManager (see setAppIcon below).
    var appIconNavy by remember { mutableStateOf(NoopPrefs.appIconNavy(context)) }

    // Theme (System / Light / Dark) — drives NoopTheme; AppearancePrefs mirrors it in snapshot state.
    var themeMode by remember { mutableStateOf(AppearancePrefs.mode) }
    // Chart colours (Titanium / Classic) — re-colours gauges + charts; ChartStylePrefs mirrors it live.
    var chartStyle by remember { mutableStateOf(ChartStylePrefs.style) }
    // Day-cycle background (#698) — the time-of-day scene behind Today. Default ON. SharedPreferences
    // isn't reactive, so the Switch mirrors into local state; TodayScreen reads the same pref on entry.
    var showDayCycleBackground by remember { mutableStateOf(NoopPrefs.showDayCycleBackground(context)) }

    // SAF launchers — CreateDocument for export, OpenDocument for import.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) { backupBusy = false; return@rememberLauncherForActivityResult }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DataBackup.exportTo(context, uri) }
            }
            backupBusy = false
            result.fold(
                onSuccess = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_backup_exported_toast),
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = { e ->
                    Toast.makeText(context, context.getString(R.string.settings_backup_problem_toast, e.message), Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    // CSV export — the 4-CSV WHOOP-format zip NOOP's own importers re-import (Android + Mac).
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) { backupBusy = false; return@rememberLauncherForActivityResult }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WhoopCsvExporter.exportZip(context, uri, vm.repo) }
            }
            backupBusy = false
            result.fold(
                onSuccess = { msg ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_csv_exported_toast, msg),
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = { e ->
                    Toast.makeText(context, context.getString(R.string.settings_csv_export_problem_toast, e.message), Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) { backupBusy = false; return@rememberLauncherForActivityResult }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DataBackup.importFrom(context, uri)
            }
            backupBusy = false
            when (result) {
                is DataBackup.ImportResult.NeedsRestart -> Toast.makeText(
                    context,
                    context.getString(R.string.settings_backup_imported_toast),
                    Toast.LENGTH_LONG,
                ).show()
                is DataBackup.ImportResult.Failed -> Toast.makeText(
                    context, result.message, Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Modern Photo Picker for the optional profile photo (no READ_EXTERNAL_STORAGE permission needed).
    // Returns a single image Uri (or null if cancelled); we decode + downscale + persist off the main
    // thread via ProfileAvatarStore, which updates the live avatar everywhere. Stored only on this phone.
    val avatarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ProfileAvatarStore.setAvatarFromUri(context, uri)
            }
            if (!ok) {
                Toast.makeText(context, context.getString(R.string.settings_photo_pick_failed_toast), Toast.LENGTH_LONG).show()
            }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
    ) {
        // Read the revision counter so every profile write recomposes this subtree
        // (SharedPreferences is not observable; `mutate` bumps `rev` after each write).
        @Suppress("UNUSED_VARIABLE") val tick = rev

        // --- Profile photo (optional, on-device) ---
        // Split into its own section ahead of the body-numbers Profile card, mirroring the iOS
        // SettingsView `profilePhotoCard` (person.crop.circle, the offline blurb). A large avatar + a
        // Choose/Change button and, once set, a Remove. Local-only and honest: the picked image is
        // downscaled and kept on this phone, never uploaded. Reads ProfileAvatarStore.hasAvatar
        // (snapshot state) so the controls update the instant a photo is set or cleared.
        SettingsSection(
            icon = Icons.Outlined.AccountCircle,
            title = stringResource(R.string.settings_profile_photo_title),
            blurb = stringResource(R.string.settings_profile_photo_blurb),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfileAvatar(size = 64.dp, contentDescription = stringResource(R.string.settings_cd_profile_photo))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NoopButton(
                            text = if (ProfileAvatarStore.hasAvatar) stringResource(R.string.settings_change_photo) else stringResource(R.string.settings_choose_photo),
                            kind = NoopButtonKind.Secondary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                avatarPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        if (ProfileAvatarStore.hasAvatar) {
                            NoopButton(
                                text = stringResource(R.string.settings_remove_photo),
                                kind = NoopButtonKind.Tertiary,
                                modifier = Modifier.weight(1f),
                                onClick = { ProfileAvatarStore.clearAvatar(context) },
                            )
                        }
                    }
                }
            }
        }

        // --- Profile ---
        SettingsSection(
            icon = Icons.Outlined.Person,
            title = stringResource(R.string.settings_profile_title),
            blurb = stringResource(R.string.settings_profile_blurb),
        ) {
            Column {
                FormRow(label = stringResource(R.string.settings_age)) {
                    StepperField(
                        value = profile.age.toString(),
                        accessibility = stringResource(R.string.settings_age_a11y, profile.age),
                        // Bound to 13..100 to match iOS — and, since v4, age feeds the Fitness Age + Vitality
                        // engines which gate on age > 0, an unbounded stepper let an Android user drive age to
                        // 0/negative and silently switch both cards off with no explanation (code review).
                        onMinus = { mutate { profile.age = (profile.age - 1).coerceIn(13, 100) } },
                        onPlus = { mutate { profile.age = (profile.age + 1).coerceIn(13, 100) } },
                    )
                }
                RowDivider()
                FormRow(label = stringResource(R.string.settings_sex)) {
                    // Resolve each option's localized label up-front: SegmentedPillControl's `label`
                    // lambda is not @Composable, so stringResource can't be called inside it.
                    val sexLabels = SEX_OPTIONS.associate { it.tag to stringResource(it.label) }
                    SegmentedPillControl(
                        items = SEX_OPTIONS,
                        selection = SEX_OPTIONS.firstOrNull { it.tag == profile.sex } ?: SEX_OPTIONS[0],
                        label = { sexLabels[it.tag] ?: it.tag },
                        onSelect = { mutate { profile.sex = it.tag } },
                    )
                }
                RowDivider()
                FormRow(label = stringResource(R.string.settings_weight)) {
                    // Imperial mode steps in whole pounds and stores the kg equivalent; metric steps in
                    // 0.5 kg. The profile is always SI — only the entry unit changes.
                    if (unitSystem == UnitSystem.IMPERIAL) {
                        val lb = UnitFormatter.kgToPounds(profile.weightKg)
                        StepperField(
                            value = "%.0f".format(lb),
                            unit = "lb",
                            accessibility = stringResource(R.string.settings_weight_lb_a11y, lb.roundToInt()),
                            onMinus = { mutate { profile.weightKg = (lb - 1) / UnitFormatter.POUNDS_PER_KILOGRAM } },
                            onPlus = { mutate { profile.weightKg = (lb + 1) / UnitFormatter.POUNDS_PER_KILOGRAM } },
                        )
                    } else {
                        StepperField(
                            value = "%.1f".format(profile.weightKg),
                            unit = "kg",
                            accessibility = stringResource(R.string.settings_weight_kg_a11y),
                            onMinus = { mutate { profile.weightKg -= 0.5 } },
                            onPlus = { mutate { profile.weightKg += 0.5 } },
                        )
                    }
                }
                RowDivider()
                FormRow(label = stringResource(R.string.settings_height)) {
                    // Imperial mode steps in whole inches and stores the cm equivalent; metric steps in cm.
                    if (unitSystem == UnitSystem.IMPERIAL) {
                        val (ft, inch) = UnitFormatter.cmToFeetInches(profile.heightCm)
                        val totalInches = UnitFormatter.cmToInches(profile.heightCm).roundToInt()
                        StepperField(
                            value = "$ft′ $inch″",
                            accessibility = stringResource(R.string.settings_height_ftin_a11y, ft, inch),
                            onMinus = { mutate { profile.heightCm = (totalInches - 1) * UnitFormatter.CENTIMETERS_PER_INCH } },
                            onPlus = { mutate { profile.heightCm = (totalInches + 1) * UnitFormatter.CENTIMETERS_PER_INCH } },
                        )
                    } else {
                        StepperField(
                            value = "%.0f".format(profile.heightCm),
                            unit = "cm",
                            accessibility = stringResource(R.string.settings_height_cm_a11y),
                            onMinus = { mutate { profile.heightCm -= 1 } },
                            onPlus = { mutate { profile.heightCm += 1 } },
                        )
                    }
                }
                RowDivider()
                // Waist (optional): the one extra body measure that unlocks the Fitness Age VO₂max
                // estimate. Unset (0) by design — the headline Fitness Age never needs it — so it shows
                // "Add" until entered, then steps like Height (inches in imperial, cm in metric).
                // First tap from unset seeds a typical adult waist rather than 1 cm.
                FormRow(label = stringResource(R.string.settings_waist_optional)) {
                    Column(horizontalAlignment = Alignment.End) {
                        val hasWaist = profile.waistCm > 0.0
                        if (unitSystem == UnitSystem.IMPERIAL) {
                            val totalInches = UnitFormatter.cmToInches(profile.waistCm).roundToInt()
                            StepperField(
                                value = if (hasWaist) "%d″".format(totalInches) else stringResource(R.string.settings_waist_add),
                                accessibility = if (hasWaist) {
                                    stringResource(R.string.settings_waist_inches_a11y, totalInches)
                                } else {
                                    stringResource(R.string.settings_waist_unset_a11y)
                                },
                                valueColor = if (hasWaist) Palette.textPrimary else Palette.textTertiary,
                                onMinus = { mutate { profile.waistCm = waistInchesStep(profile.waistCm, up = false) } },
                                onPlus = { mutate { profile.waistCm = waistInchesStep(profile.waistCm, up = true) } },
                            )
                        } else {
                            StepperField(
                                value = if (hasWaist) "%.0f".format(profile.waistCm) else stringResource(R.string.settings_waist_add),
                                unit = if (hasWaist) "cm" else null,
                                accessibility = if (hasWaist) {
                                    stringResource(R.string.settings_waist_cm_a11y)
                                } else {
                                    stringResource(R.string.settings_waist_unset_a11y)
                                },
                                valueColor = if (hasWaist) Palette.textPrimary else Palette.textTertiary,
                                onMinus = { mutate { profile.waistCm = waistCmStep(profile.waistCm, up = false) } },
                                onPlus = { mutate { profile.waistCm = waistCmStep(profile.waistCm, up = true) } },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (hasWaist) stringResource(R.string.settings_waist_adds_vo2max) else stringResource(R.string.settings_waist_optional_adds_vo2max),
                            style = NoopType.footnote,
                            color = if (hasWaist) Palette.accent else Palette.textTertiary,
                        )
                    }
                }
                RowDivider()
                FormRow(label = stringResource(R.string.settings_max_heart_rate)) {
                    Column(horizontalAlignment = Alignment.End) {
                        StepperField(
                            value = if (profile.hrMaxOverride > 0) profile.hrMaxOverride.toString() else stringResource(R.string.settings_hr_max_auto),
                            unit = "bpm",
                            accessibility = if (profile.hrMaxOverride == 0) {
                                stringResource(R.string.settings_hr_max_override_auto_a11y)
                            } else {
                                stringResource(R.string.settings_hr_max_override_bpm_a11y, profile.hrMaxOverride)
                            },
                            valueColor = if (profile.hrMaxOverride > 0) Palette.textPrimary else Palette.textTertiary,
                            onMinus = { mutate { profile.hrMaxOverride -= 1 } },
                            onPlus = { mutate { profile.hrMaxOverride += 1 } },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (profile.hrMaxOverride > 0) {
                                stringResource(R.string.settings_hr_max_manual_override)
                            } else {
                                stringResource(R.string.settings_hr_max_auto_tanaka, profile.hrMaxAuto)
                            },
                            style = NoopType.footnote,
                            color = if (profile.hrMaxOverride > 0) Palette.accent else Palette.textTertiary,
                        )
                    }
                }
                RowDivider()
                // Step calibration (#139/#132): daily steps = @57 counter ticks ÷ this divisor.
                // 1.0 = raw pass-through until the true 5/MG tick rate is known. The divisor goes
                // up to 30 because a 5/MG motion counter can overcount by ~24×; the stepper uses a
                // variable increment (fine near 1.0, coarse up top) so high values stay reachable.
                FormRow(label = stringResource(R.string.settings_step_calibration)) {
                    StepperField(
                        value = "%.1f".format(profile.stepTicksPerStep),
                        accessibility = stringResource(R.string.settings_step_calibration_a11y, profile.stepTicksPerStep),
                        onMinus = { mutate { profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up = false) } },
                        onPlus = { mutate { profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up = true) } },
                    )
                }
                Text(
                    stringResource(R.string.settings_step_calibration_footnote),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                RowDivider()
                // Tap-through to the WHOOP 4.0 steps-ESTIMATE calibration (a SEPARATE thing from the 5/MG
                // @57 counter divisor above): a 4.0 sends no step count, so NOOP estimates steps from
                // motion and calibrates that to the phone. Opens the explainer + fit + comparison + manual
                // override screen. Mirrors the macOS Profile "Steps estimate" row.
                val stepsSummary = when {
                    profile.stepsManualCoefficient > 0 -> stringResource(R.string.settings_steps_manual)
                    profile.stepsCalibrationCoefficient > 0 ->
                        stringResource(R.string.settings_steps_auto_confidence, StepsCalibrationFormat.confidenceLabel(profile.stepsCalibrationConfidence))
                    else -> stringResource(R.string.settings_steps_not_calibrated)
                }
                val stepsEstimateCd = stringResource(R.string.settings_steps_estimate_cd, stepsSummary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showStepsCalibration = true }
                        .semantics {
                            contentDescription = stepsEstimateCd
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.settings_steps_estimate), style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    Text(
                        stepsSummary,
                        style = NoopType.footnote,
                        color = if (profile.stepsManualCoefficient > 0) Palette.accent else Palette.textTertiary,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    stringResource(R.string.settings_for_a_whoop_4_0_which),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }

        // --- Units ---
        // Imperial/Metric display toggle + a separate temperature override. Display-only — nothing
        // stored changes; NOOP keeps everything in SI and converts at the point of display. Mirrors the
        // macOS Settings → Units card.
        SettingsSection(
            icon = Icons.Filled.Straighten,
            title = stringResource(R.string.settings_units_title),
            blurb = stringResource(R.string.settings_units_blurb),
        ) {
            Column {
                FormRow(label = stringResource(R.string.settings_measurement_system)) {
                    val metricLabel = stringResource(R.string.settings_unit_metric)
                    val imperialLabel = stringResource(R.string.settings_unit_imperial)
                    SegmentedPillControl(
                        items = listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL),
                        selection = unitSystem,
                        label = { if (it == UnitSystem.METRIC) metricLabel else imperialLabel },
                        onSelect = {
                            unitSystem = it
                            NoopPrefs.setUnitSystem(context, it)
                        },
                    )
                }
                RowDivider()
                FormRow(label = stringResource(R.string.settings_temperature)) {
                    // Three-way: "Match" follows the system above; °C / °F pin it explicitly. Stored as an
                    // empty string ("match") or the TemperatureUnit raw value.
                    val matchLabel = stringResource(R.string.settings_temp_match)
                    SegmentedPillControl(
                        items = listOf("", TemperatureUnit.CELSIUS.raw, TemperatureUnit.FAHRENHEIT.raw),
                        selection = temperatureRaw,
                        label = {
                            when (it) {
                                TemperatureUnit.CELSIUS.raw -> "°C"
                                TemperatureUnit.FAHRENHEIT.raw -> "°F"
                                else -> matchLabel
                            }
                        },
                        onSelect = {
                            temperatureRaw = it
                            NoopPrefs.setTemperatureUnit(context, TemperatureUnit.fromRaw(it))
                        },
                    )
                }
                RowDivider()
                // Effort scale (#268) — NOOP's native 0–100 Effort or WHOOP's 0–21 Day Strain axis.
                // Display-only; the stored value never changes, so a flip just re-labels every read-out.
                FormRow(label = stringResource(R.string.settings_effort_scale)) {
                    SegmentedPillControl(
                        items = listOf(EffortScale.HUNDRED, EffortScale.WHOOP),
                        selection = effortScale,
                        label = { if (it == EffortScale.HUNDRED) "0–100" else "0–21" },
                        onSelect = {
                            effortScale = it
                            UnitPrefs.setEffortScale(context, it)
                        },
                    )
                }
            }
        }

        // --- Appearance (Theme) ---
        SettingsSection(
            icon = Icons.Filled.Brightness6,
            title = stringResource(R.string.settings_appearance_title),
            blurb = stringResource(R.string.settings_appearance_blurb_r7),
        ) {
            FormRow(label = stringResource(R.string.settings_theme)) {
                SegmentedPillControl(
                    items = listOf(AppearanceMode.SYSTEM, AppearanceMode.LIGHT, AppearanceMode.DARK),
                    selection = themeMode,
                    label = { it.label },
                    onSelect = { mode ->
                        themeMode = mode
                        AppearancePrefs.set(context, mode)
                    },
                )
            }
            FormRow(label = stringResource(R.string.settings_chart_colours)) {
                // Titanium = brand gold/amber/blue ramps; Classic = throwback red→green readiness scale
                // (cool→hot zones, green→red stress). Re-colours every gauge/chart, in both schemes.
                SegmentedPillControl(
                    items = listOf(ChartStyle.TITANIUM, ChartStyle.CLASSIC),
                    selection = chartStyle,
                    label = { it.label },
                    onSelect = { style ->
                        chartStyle = style
                        ChartStylePrefs.set(context, style)
                    },
                )
            }

            // Day-cycle background (#698): the time-of-day scene behind Today. On by default. Off swaps it
            // for a plain dark canvas for people who find the moving scene distracting. Takes effect next
            // time Today is opened (the pref is read once on entry, like the other Today-screen toggles).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_day_cycle_title),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                    )
                    Text(
                        stringResource(R.string.settings_day_cycle_detail),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = showDayCycleBackground,
                    onCheckedChange = {
                        showDayCycleBackground = it
                        NoopPrefs.setShowDayCycleBackground(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                )
            }
        }

        // --- App icon (v3 "Titanium & Gold") ---
        // Two staged launcher icons — machined titanium (default) and blued/dark-blue titanium. The
        // swap is done by enabling exactly one <activity-alias> (.IconDefault / .IconNavy) at runtime;
        // the launcher may take a beat (or briefly disappear/redraw) while it re-reads the icon.
        SettingsSection(
            icon = Icons.Filled.Palette,
            title = stringResource(R.string.settings_app_icon_title),
            blurb = stringResource(R.string.settings_app_icon_blurb),
        ) {
            FormRow(label = stringResource(R.string.settings_app_icon_label)) {
                val blueTitaniumLabel = stringResource(R.string.settings_app_icon_blue_titanium)
                val titaniumLabel = stringResource(R.string.settings_app_icon_titanium)
                SegmentedPillControl(
                    items = listOf(false, true),
                    selection = appIconNavy,
                    label = { if (it) blueTitaniumLabel else titaniumLabel },
                    onSelect = { navy ->
                        appIconNavy = navy
                        setAppIcon(context, navy)
                    },
                )
            }
        }

        // --- Strap ---
        SettingsSection(
            icon = Icons.Filled.Sensors,
            title = stringResource(R.string.settings_strap_title),
            blurb = stringResource(R.string.settings_strap_blurb),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatePill(
                        title = stringResource(strapStatusTitle(live.bonded, live.connected)),
                        tone = strapTone(live.bonded, live.connected),
                        pulsing = live.connected,
                    )
                    live.batteryPct?.let { pct ->
                        StatePill(
                            title = if (live.charging == true) {
                                stringResource(R.string.settings_battery_charging, pct.roundToInt())
                            } else {
                                stringResource(R.string.settings_battery, pct.roundToInt())
                            },
                            tone = batteryTone(pct),
                            showsDot = false,
                        )
                    }
                }
                Text(
                    stringResource(strapStatusDetail(live.bonded, live.connected, live.scanning)),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NoopButton(
                        text = if (live.scanning) stringResource(R.string.settings_strap_searching) else stringResource(R.string.settings_strap_rescan),
                        leadingIcon = Icons.Filled.Refresh,
                        kind = NoopButtonKind.Primary,
                        enabled = !live.scanning,
                        onClick = { requestScan() },
                    )

                    NoopButton(
                        text = stringResource(R.string.settings_strap_disconnect),
                        leadingIcon = Icons.Filled.Cancel,
                        kind = NoopButtonKind.Secondary,
                        enabled = live.connected || live.bonded,
                        onClick = { vm.disconnect() },
                    )
                }

                // Rename the strap's BLE advertising name (WHOOP 4.0 only). Writes the name to the strap
                // firmware (cmd 77); it reboots to apply, so the new name shows on the next connect. Handy
                // for a second-hand band stuck on the previous owner's name. Reversible.
                if (live.connected && !live.whoop5Detected) {
                    var nameDraft by remember(live.advertisingName) { mutableStateOf(live.advertisingName ?: "") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_strap_name), style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            stringResource(R.string.settings_strap_name_desc),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it.take(24) },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.settings_strap_name_placeholder), style = NoopType.body, color = Palette.textTertiary) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Palette.textPrimary,
                                unfocusedTextColor = Palette.textPrimary,
                                focusedBorderColor = Palette.accent,
                                unfocusedBorderColor = Palette.hairline,
                                cursorColor = Palette.accent,
                                focusedContainerColor = Palette.surfaceInset,
                                unfocusedContainerColor = Palette.surfaceInset,
                            ),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NoopButton(
                                text = stringResource(R.string.settings_strap_rename),
                                leadingIcon = Icons.Filled.Edit,
                                kind = NoopButtonKind.Primary,
                                enabled = live.bonded && nameDraft.isNotBlank(),
                                onClick = { vm.ble.renameStrap(nameDraft) },
                            )
                            live.renameStatus?.let {
                                Text(it, style = NoopType.footnote, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Keep streaming when the app is closed (Android foreground service). On Mac, NOOP
                // already keeps your strap connected from the menu bar — just close the window.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_keep_connected_title),
                            style = NoopType.subhead,
                            color = Palette.textPrimary,
                        )
                        Text(
                            stringResource(R.string.settings_keep_connected_desc),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = backgroundConnection,
                        onCheckedChange = {
                            backgroundConnection = it
                            vm.setBackgroundConnection(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                    )
                }

                // Continuous HRV capture: keep the dense beat-to-beat (R-R) stream armed even with no Live
                // screen open, so the strap banks far more data overnight for better HRV/recovery/sleep.
                // Honest battery framing — continuous HR streaming uses more battery. Needs background
                // connection on (there's no background link to stream over otherwise). Default OFF.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_continuous_hrv_title),
                            style = NoopType.subhead,
                            color = Palette.textPrimary,
                        )
                        Text(
                            stringResource(R.string.settings_continuous_hrv_desc),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = continuousHrv,
                        onCheckedChange = {
                            continuousHrv = it
                            vm.setContinuousHrv(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                    )
                }

                // Diagnostics: "Debug logging" mirrors the strap log to logcat (adb). Default OFF — a
                // normal user never needs to write the connection log to the system log; the in-app log
                // (and the "Share strap log" export below) work regardless. Developers flip this on to
                // watch the connection live over `adb logcat -s WhoopBleClient`.
                val debugLoggingCd = stringResource(R.string.settings_debug_logging_cd)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_debug_logging_title),
                            style = NoopType.subhead,
                            color = Palette.textPrimary,
                        )
                        Text(
                            stringResource(R.string.settings_debug_logging_desc),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = debugLogging,
                        onCheckedChange = {
                            debugLogging = it
                            vm.setDebugLogging(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = debugLoggingCd
                        },
                    )
                }

                // Diagnostics: export the strap connection log so people can attach it to a bug report.
                NoopButton(
                    text = stringResource(R.string.settings_share_strap_log),
                    leadingIcon = Icons.Filled.Upload,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = { LogExport.shareStrapLog(context, vm.ble.exportLogText()) },
                )

                // "WHOOP 4.0 vs 5.0/MG — what each can read and why" (FI-2 / #490). Shown to BOTH model
                // owners, so a 4.0 user understands their strap is fully supported (and why the firmware
                // broadcast-out is 5/MG-only while NOOP's own re-broadcast in Data Sources works on a 4.0).
                val modelComparisonCd = stringResource(R.string.settings_cd_whoop_4_0_versus_5)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.surfaceInset)
                        .border(1.dp, Palette.hairline, RoundedCornerShape(10.dp))
                        .clickable { showModelComparison = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { contentDescription = modelComparisonCd },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_whoop_4_0_vs_5), style = NoopType.headline, color = Palette.textPrimary)
                            Text(
                                stringResource(R.string.settings_what_each_strap_can_read_and),
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                        Text("›", style = NoopType.title2, color = Palette.accent)
                    }
                }
            }
        }

        // --- Experimental · WHOOP 5 / MG --- (hidden when the user is confidently on a 4.0, #22)
        if (showFiveMGControls) {
        SettingsSection(
            icon = Icons.Filled.Science,
            title = stringResource(R.string.settings_experimental_title),
            blurb = stringResource(R.string.settings_experimental_blurb),
        ) {
            val probesCd = stringResource(R.string.settings_exp_probes_switch_cd)
            val broadcastHrCd = stringResource(R.string.settings_exp_broadcast_hr_cd)
            val deepDataCd = stringResource(R.string.settings_exp_deep_data_cd)
            val rawCaptureCd = stringResource(R.string.settings_exp_raw_capture_cd)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_exp_probes_title),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = puffinExperiments,
                        onCheckedChange = {
                            puffinExperiments = it
                            puffinExperiment.isEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = probesCd
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_exp_probes_desc),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )

                // --- Broadcast heart rate (turn the strap into a standard BLE HR sensor). (#181) ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_exp_broadcast_hr_title),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = broadcastHr,
                        onCheckedChange = {
                            broadcastHr = it
                            puffinExperiment.broadcastHr = it
                            vm.ble.setBroadcastHr(it)
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
                Text(
                    stringResource(R.string.settings_exp_broadcast_hr_desc),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )

                // --- R22 deep-data unlock — the one probe that writes to the strap. (#174) ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_exp_deep_data_title),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = deepData,
                        onCheckedChange = {
                            deepData = it
                            puffinExperiment.isDeepDataEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = deepDataCd
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_exp_deep_data_desc),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
                if (deepData) {
                    NoopButton(
                        text = stringResource(R.string.settings_exp_deep_data_send),
                        leadingIcon = Icons.Filled.Bolt,
                        kind = NoopButtonKind.Primary,
                        enabled = live.encryptedBond && live.worn,
                        onClick = { vm.ble.enableWhoop5DeepData() },
                    )
                    Text(
                        if (!live.encryptedBond) stringResource(R.string.settings_exp_deep_data_needs_bond)
                        else if (!live.worn) stringResource(R.string.settings_exp_deep_data_needs_worn)
                        else stringResource(R.string.settings_exp_deep_data_ready),
                        style = NoopType.caption,
                        color = Palette.textTertiary,
                    )
                    // Live R22 telemetry (#174): proof of what the strap is doing right now.
                    if (live.r22FlagsAccepted > 0) {
                        Text(
                            if (live.r22FlagsAccepted >= 15) stringResource(R.string.settings_exp_r22_all_flags)
                            else stringResource(R.string.settings_exp_r22_flags_progress, live.r22FlagsAccepted),
                            style = NoopType.caption,
                            color = if (live.r22FlagsAccepted >= 15) Palette.statusPositive else Palette.textSecondary,
                        )
                    }
                    if (live.deepPacketsThisSession > 0) {
                        Text(
                            stringResource(R.string.settings_exp_deep_packets_seen, live.deepPacketsThisSession),
                            style = NoopType.caption,
                            color = Palette.textSecondary,
                        )
                    } else if (live.r22FlagsAccepted >= 15) {
                        Text(
                            stringResource(R.string.settings_flags_accepted_but_the_enable_sequence),
                            style = NoopType.caption,
                            color = Palette.textTertiary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_exp_raw_capture_title),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = puffinCapture,
                        onCheckedChange = {
                            puffinCapture = it
                            puffinExperiment.isCaptureEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = rawCaptureCd
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_exp_raw_capture_desc),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
                NoopButton(
                    text = stringResource(R.string.settings_exp_share_capture),
                    leadingIcon = Icons.Filled.Upload,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = { LogExport.shareWhoop5Capture(context, live.whoop5Detected) },
                )

                // One-tap "matched pair" export (#510): hands a reporter BOTH the raw capture file and
                // the strap log together (timestamped, same minute) so a protocol-mapping issue arrives
                // with the frames AND the context that produced them.
                NoopButton(
                    text = stringResource(R.string.settings_export_raw_log_matched_pair),
                    leadingIcon = Icons.Filled.IosShare,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = { LogExport.shareRawAndLog(context, vm.ble.exportLogText(), live.whoop5Detected) },
                )
            }
        }
        } // end if (showFiveMGControls)

        // --- Diagnostics (every model) --- the raw-sensor CSV export is split out of the 5/MG card so it
        // stays available on a WHOOP 4.0 too (#22): a 4.0 owner still needs it to share decoded streams.
        SettingsSection(
            icon = Icons.Filled.Science,
            title = stringResource(R.string.settings_diagnostics_title),
            blurb = stringResource(R.string.settings_diagnostics_blurb),
        ) {
            val sleepV2Cd = stringResource(R.string.settings_sleep_v2_cd)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // --- Experimental sleep staging (V2) — opt-in, default OFF, every model. (V7 Pillar 3b) ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_sleep_v2_title),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = experimentalSleepV2,
                        onCheckedChange = {
                            experimentalSleepV2 = it
                            puffinExperiment.experimentalSleepV2 = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = sleepV2Cd
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_sleep_v2_detail),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )

                // Diagnostics: dump the decoded per-sample sensor streams (last 24h) to one long-format
                // CSV so power users / external devs can prototype sleep/activity/VBT algorithms on real
                // data without a BLE stream (#308/#276/#322). On-device only; plain text, no BLE hex.
                NoopButton(
                    text = stringResource(R.string.settings_exp_export_raw_csv),
                    leadingIcon = Icons.Filled.Upload,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = { scope.launch { RawSensorExport.export(context, vm.repo) } },
                )
                Text(
                    stringResource(R.string.settings_exp_export_raw_csv_desc),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )

                // Haptic clock (#460): buzz the current time on the strap as a sequence of buzzes. No-ops
                // safely when disconnected, so it stays enabled regardless of connection (matches the
                // "Share strap log" row above, which also doesn't gate on a live strap). 12/24h follows the
                // phone's own clock setting.
                NoopButton(
                    text = stringResource(R.string.settings_buzz_the_time_on_your),
                    leadingIcon = Icons.Filled.Vibration,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = {
                        vm.ble.buzzTimeNow(is24h = android.text.format.DateFormat.is24HourFormat(context))
                    },
                )
                Text(
                    stringResource(R.string.settings_feel_the_current_time_as),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }
        }

        // --- Scheduled debug export (#510, maddognik) --- a daily, no-UI drop of the timestamped strap
        // log (+ raw .bin when a 5/MG capture exists) into the app's export folder at a time you choose, so
        // an intermittent overnight fault leaves a dated log waiting instead of needing a manual share. The
        // feature core lives in DebugExportScheduler/DebugExportSettings; this is just the controls. OFF by
        // default. SharedPreferences isn't reactive, so the Switch + time mirror into local state.
        SettingsSection(
            icon = Icons.Filled.Storage,
            title = stringResource(R.string.settings_scheduled_export_title),
            blurb = stringResource(R.string.settings_scheduled_export_blurb),
        ) {
            val dailyAutoExportCd = stringResource(R.string.settings_daily_auto_export_cd)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_daily_auto_export),
                            style = NoopType.subhead,
                            color = Palette.textPrimary,
                        )
                        Text(
                            stringResource(R.string.settings_writes_a_timestamped_strap_log),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = debugExportEnabled,
                        onCheckedChange = {
                            debugExportEnabled = it
                            debugExportSettings.enabled = it
                            DebugExportScheduler.reschedule(context)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = dailyAutoExportCd
                        },
                    )
                }

                if (debugExportEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_export_time), style = NoopType.subhead, color = Palette.textPrimary)
                            Text(
                                stringResource(R.string.settings_the_daily_export_runs_at),
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                            )
                        }
                        TimeChip(
                            minutes = debugExportMinutes,
                            accessibilityLabel = stringResource(R.string.settings_daily_export_time_a11y),
                            onPicked = {
                                debugExportMinutes = it
                                debugExportSettings.timeMinutes = it
                                DebugExportScheduler.applyTimeChange(context)
                            },
                        )
                    }
                }

                // "Export now" writes the dated file immediately (off the main thread, like the CSV export
                // above) and confirms with a Toast naming the folder, so the user sees the feature work
                // without waiting for the scheduled run.
                NoopButton(
                    text = stringResource(R.string.settings_export_now),
                    leadingIcon = Icons.Filled.SaveAlt,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = {
                        scope.launch {
                            val files = withContext(Dispatchers.IO) {
                                LogExport.writeScheduledExport(context, vm.ble.exportLogText())
                            }
                            Toast.makeText(
                                context,
                                if (files.isNotEmpty()) {
                                    if (files.size == 1) context.getString(R.string.settings_export_now_wrote_one, files.size)
                                    else context.getString(R.string.settings_export_now_wrote_many, files.size)
                                } else context.getString(R.string.settings_export_now_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
            }
        }

        // --- Trends report (#436) — shareable offline PDF over a date range. Self-contained
        // card (its own NoopCard + range picker + CTA), so it drops in without a SettingsSection wrapper.
        TrendsReportExportSection(vm)

        // --- Health & wellness (v5 opt-in toggles) ---
        SettingsSection(
            icon = Icons.Filled.Science,
            title = stringResource(R.string.settings_health_wellness_title),
            blurb = stringResource(R.string.settings_health_wellness_blurb),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ToggleRow(
                    title = stringResource(R.string.settings_illness_title),
                    detail = stringResource(R.string.settings_illness_detail),
                    checked = illnessWatch,
                    onCheckedChange = {
                        illnessWatch = it
                        vm.setIllnessWatchEnabled(it)
                    },
                )
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_cycle_title),
                    detail = stringResource(R.string.settings_cycle_detail),
                    checked = cycleTracking,
                    onCheckedChange = {
                        cycleTracking = it
                        vm.setCycleTrackingEnabled(it)
                    },
                )
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_hydration_title),
                    detail = stringResource(R.string.settings_hydration_detail),
                    checked = hydrationTracking,
                    onCheckedChange = {
                        hydrationTracking = it
                        NoopPrefs.setHydrationTracking(context, it)
                    },
                )
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_autodetect_title),
                    detail = stringResource(R.string.settings_autodetect_detail),
                    checked = autoDetectWorkouts,
                    onCheckedChange = {
                        autoDetectWorkouts = it
                        NoopPrefs.setAutoDetectWorkouts(context, it)
                    },
                )
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_stress_checkin_title),
                    detail = stringResource(R.string.settings_stress_checkin_detail),
                    checked = stressCheckIn,
                    onCheckedChange = {
                        stressCheckIn = it
                        BiofeedbackPrefs.setCheckInEnabled(context, it)
                        // Turning the master off also disarms the auto-nudge sub-toggle so it can't fire.
                        if (!it) { stressAutoNudge = false; BiofeedbackPrefs.setAutoNudge(context, false) }
                    },
                )
                if (stressCheckIn) {
                    ToggleRow(
                        title = stringResource(R.string.settings_stress_auto_title),
                        detail = stringResource(R.string.settings_stress_auto_detail),
                        checked = stressAutoNudge,
                        onCheckedChange = {
                            stressAutoNudge = it
                            BiofeedbackPrefs.setAutoNudge(context, it)
                        },
                    )
                }
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_rhythm_title),
                    detail = stringResource(R.string.settings_rhythm_detail),
                    checked = rhythmEnabled,
                    onCheckedChange = {
                        // Enabling here just un-gates the experimental item; the screen itself still shows
                        // its consent clickwrap on first open (and re-prompts on a version bump). Disabling
                        // clears the flag so the screen returns to its gate.
                        rhythmEnabled = it
                        if (it) {
                            NoopPrefs.of(context).edit().putBoolean(RhythmConsent.KEY_ENABLED, true).apply()
                        } else {
                            NoopPrefs.of(context).edit().putBoolean(RhythmConsent.KEY_ENABLED, false).apply()
                        }
                    },
                )
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_coach_signals_title),
                    detail = stringResource(R.string.settings_coach_signals_detail),
                    checked = coachSignals,
                    onCheckedChange = {
                        coachSignals = it
                        NoopPrefs.setCoachSignals(context, it)
                    },
                )
            }
        }

        // --- Charge (Recovery) advanced ---
        // A manual reset for the personal Charge baseline. If a bad first week poisons it — worn while
        // sick, or the first few nights read high (a common cold-start artefact) — the baseline anchors
        // off and holds your Charge wrong for a couple of weeks while the rolling average catches up.
        // Recalibrate re-learns it from tonight onward. Writes now-seconds to BOTH noop.hrvBaselineEpoch
        // and noop.recoveryBaselineEpoch (so HRV plus resting HR / respiration / skin temp re-anchor);
        // foldHistory drops every night before that epoch and re-seeds. Mirrors the iOS/Mac button.
        SettingsSection(
            icon = Icons.Filled.Favorite,
            title = stringResource(R.string.settings_charge_title),
            blurb = stringResource(R.string.settings_charge_blurb),
        ) {
            val recalibrateCd = stringResource(R.string.settings_recalibrate_charge_baseline)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.settings_recalibrate_charge_baseline), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        stringResource(R.string.settings_restarts_the_roughly_4_night),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                NoopButton(
                    text = stringResource(R.string.settings_recalibrate_charge_baseline),
                    leadingIcon = Icons.Filled.Autorenew,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    modifier = Modifier.semantics { contentDescription = recalibrateCd },
                    onClick = { showRecalibrateConfirm = true },
                )
            }
        }

        if (showRecalibrateConfirm) {
            AlertDialog(
                onDismissRequest = { showRecalibrateConfirm = false },
                containerColor = Palette.surfaceOverlay,
                title = { Text(stringResource(R.string.settings_recalibrate_your_charge_baseline), style = NoopType.title2, color = Palette.textPrimary) },
                text = {
                    Text(
                        stringResource(R.string.settings_this_restarts_the_roughly_4_night),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            // Re-anchor EVERY baseline that feeds Charge — HRV plus resting HR /
                            // respiration / skin temp — by writing now-seconds to BOTH shared epoch keys
                            // (the EXACT same keys the iOS/Mac button + Baselines.foldHistory use), via
                            // the single cross-platform source of truth. Stored as whole epoch SECONDS in
                            // a Long (SharedPreferences has no putDouble; the readers do getLong→toDouble),
                            // matching the "epoch SECONDS" the keys document. No stored day is deleted.
                            val nowSeconds = System.currentTimeMillis() / 1000L
                            val editor = NoopPrefs.of(context).edit()
                            Baselines.recalibrateRecoveryBaselines(editor, nowSeconds)
                            editor.apply()
                            showRecalibrateConfirm = false
                            // Nudge an immediate re-analyze so the change is felt now; the standing
                            // 15-min analyze loop also re-runs foldHistory regardless. No-ops cleanly
                            // when the strap isn't connected.
                            vm.syncNow()
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_charge_baseline_reset_toast),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    ) { Text(stringResource(R.string.settings_recalibrate), style = NoopType.body, color = Palette.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { showRecalibrateConfirm = false }) {
                        Text(stringResource(R.string.workouts_button_cancel), style = NoopType.body, color = Palette.textSecondary)
                    }
                },
            )
        }

        SettingsSection(
            icon = Icons.Filled.Storage,
            title = stringResource(R.string.settings_backup_title),
            blurb = stringResource(R.string.settings_backup_blurb),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Three equal-width buttons share the row (each takes a third via weight) — mirrors the
                // iOS Backup card's three fullWidth NoopButtonStyle buttons. The busy spinner sits BELOW
                // the row (not inside it) so it never steals a button's share of the width.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NoopButton(
                        text = stringResource(R.string.settings_backup_export),
                        kind = NoopButtonKind.Primary,
                        enabled = !backupBusy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            backupBusy = true
                            exportLauncher.launch("noop-backup-${java.time.LocalDate.now()}.noopbak")
                        },
                    )

                    NoopButton(
                        text = stringResource(R.string.settings_backup_import),
                        kind = NoopButtonKind.Secondary,
                        enabled = !backupBusy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            backupBusy = true
                            importLauncher.launch(arrayOf("*/*"))
                        },
                    )

                    NoopButton(
                        text = stringResource(R.string.settings_backup_export_csv),
                        kind = NoopButtonKind.Secondary,
                        enabled = !backupBusy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            backupBusy = true
                            csvExportLauncher.launch("noop-export-${java.time.LocalDate.now()}.zip")
                        },
                    )
                }

                if (backupBusy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Palette.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(R.string.datasources_backup_working), style = NoopType.footnote, color = Palette.textSecondary)
                    }
                }

                NoteRow(
                    icon = Icons.Filled.Info,
                    iconTint = Palette.textTertiary,
                    text = stringResource(R.string.settings_backup_note),
                )
            }
        }

        // --- About ---
        SettingsSection(
            icon = Icons.Filled.Info,
            title = stringResource(R.string.settings_about_title),
            blurb = stringResource(R.string.settings_about_blurb),
        ) {
            val projectHomeCd = stringResource(R.string.settings_cd_project_home_and_source_on)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("NOOP", style = NoopType.title2, color = Palette.textPrimary)
                    StatePill("v${BuildConfig.VERSION_NAME}", tone = StrandTone.Neutral, showsDot = false)
                }

                // Project home — NOOP's code, releases, issues and wiki live on GitHub
                // (canonical; noop.fans is kept as a mirror).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.accent.copy(alpha = 0.10f))
                        .border(1.dp, Palette.accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NoopApp/noop"))
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "github.com/NoopApp/noop", Toast.LENGTH_LONG).show()
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { contentDescription = projectHomeCd },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.settings_project_home_source), style = NoopType.body, color = Palette.textPrimary)
                        Text(
                            stringResource(R.string.settings_github_code_releases_issues_and_the),
                            style = NoopType.caption,
                            color = Palette.textTertiary,
                        )
                    }
                }

                // Check for updates — a single, user-initiated call to the project's public releases API (GitHub)
                // when the button is tapped. No background polling, no auto-update; nothing about you
                // is sent. Android already holds INTERNET (for the opt-in Coach), so this adds nothing.
                var updChecking by remember { mutableStateOf(false) }
                var updResult by remember { mutableStateOf<UpdateCheck.Result?>(null) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!updChecking) {
                                    updChecking = true
                                    updResult = null
                                    scope.launch {
                                        updResult = UpdateCheck.check(BuildConfig.VERSION_NAME)
                                        updChecking = false
                                    }
                                }
                            },
                            enabled = !updChecking,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
                        ) {
                            if (updChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp).padding(end = 6.dp),
                                    strokeWidth = 2.dp,
                                    color = Palette.accent,
                                )
                                Text(stringResource(R.string.settings_update_checking), style = NoopType.captionNumber)
                            } else {
                                Text(stringResource(R.string.settings_update_check), style = NoopType.captionNumber)
                            }
                        }
                        when (val r = updResult) {
                            is UpdateCheck.Result.UpToDate ->
                                Text(
                                    stringResource(R.string.settings_update_uptodate, r.version),
                                    style = NoopType.footnote, color = Palette.textSecondary,
                                )
                            UpdateCheck.Result.Failed ->
                                Text(
                                    stringResource(R.string.settings_update_failed),
                                    style = NoopType.footnote, color = Palette.statusWarning,
                                )
                            else -> {}
                        }
                    }

                    // Update available: show what's new, with a download straight to the release.
                    (updResult as? UpdateCheck.Result.Available)?.let { avail ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Palette.surfaceInset)
                                .border(1.dp, Palette.accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.settings_update_available, avail.version),
                                    style = NoopType.subhead, color = Palette.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                NoopButton(
                                    text = stringResource(R.string.settings_update_download),
                                    leadingIcon = Icons.Filled.Download,
                                    kind = NoopButtonKind.Primary,
                                    onClick = {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(avail.url)))
                                    },
                                )
                            }
                            if (avail.notes.isNotEmpty()) {
                                Text(
                                    avail.notes,
                                    style = NoopType.footnote, color = Palette.textSecondary,
                                    modifier = Modifier
                                        .heightIn(max = 160.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }

                    Text(
                        stringResource(R.string.settings_update_check_note),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }

                Text(
                    stringResource(R.string.settings_about_body),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )

                // What's new — re-open the changelog sheet any time (macOS About parity).
                val whatsNewCd = stringResource(R.string.settings_whats_new_cd, AppChangelog.CURRENT_VERSION)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.surfaceInset)
                        .border(1.dp, Palette.hairline, RoundedCornerShape(10.dp))
                        .clickable { showWhatsNew = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { contentDescription = whatsNewCd },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Campaign,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_whats_new_title), style = NoopType.headline, color = Palette.textPrimary)
                            Text(
                                stringResource(R.string.settings_whats_new_subtitle),
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                        Text("›", style = NoopType.title2, color = Palette.accent)
                    }
                }

                // How your scores work — the honest explainer for Charge/Effort/Rest + the
                // confidence labels, opened any time (macOS/iOS About parity).
                val scoresCd = stringResource(R.string.settings_scores_title)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.surfaceInset)
                        .border(1.dp, Palette.hairline, RoundedCornerShape(10.dp))
                        .clickable { showScoringGuide = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { contentDescription = scoresCd },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Science,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_scores_title), style = NoopType.headline, color = Palette.textPrimary)
                            Text(
                                stringResource(R.string.settings_scores_subtitle),
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                        Text("›", style = NoopType.title2, color = Palette.accent)
                    }
                }

                // How NOOP works — the plain-English primer (COMPONENT 5 of the explainability layer):
                // how sleep is sorted, how scores + calibration work, what recording means, and where
                // each number comes from. The one "?" entry point into the primer (macOS/iOS parity).
                val howNoopWorksCd = stringResource(R.string.how_noop_works_how_noop_works)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.surfaceInset)
                        .border(1.dp, Palette.hairline, RoundedCornerShape(10.dp))
                        .clickable { showHowNoopWorks = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { contentDescription = howNoopWorksCd },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.MenuBook,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.how_noop_works_how_noop_works), style = NoopType.headline, color = Palette.textPrimary)
                            Text(
                                stringResource(R.string.settings_sleep_sorting_scores_recording_and_where),
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                        Text("›", style = NoopType.title2, color = Palette.accent)
                    }
                }

                // Medical disclaimer — inset well with a warning-tinted hairline.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.surfaceInset)
                        .border(1.dp, Palette.statusWarning.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = Palette.statusWarning,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.settings_medical_disclaimer),
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                }

                RowDivider()

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Overline(stringResource(R.string.settings_built_on))
                    AttributionRow(repo = "my-whoop", note = stringResource(R.string.settings_built_on_whoop4_protocol))
                    AttributionRow(repo = "goose", note = stringResource(R.string.settings_built_on_whoop5_protocol))
                }
                Text(
                    stringResource(R.string.settings_built_on_thanks),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )

                RowDivider()

                // Support link — opens the project's contact email (same address the
                // Support screen lists). NOOP is anonymous, so email is the support channel.
                val supportCd = stringResource(R.string.settings_support_cd, SUPPORT_EMAIL)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.accent.copy(alpha = 0.10f))
                        .border(1.dp, Palette.accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .clickable {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:$SUPPORT_EMAIL")
                                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_support_email_subject))
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, context.getString(R.string.settings_support_email_fallback, SUPPORT_EMAIL), Toast.LENGTH_LONG).show()
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { contentDescription = supportCd },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_support_title), style = NoopType.headline, color = Palette.textPrimary)
                            Text(
                                stringResource(R.string.settings_support_body, SUPPORT_EMAIL),
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                        Text("›", style = NoopType.title2, color = Palette.accent)
                    }
                }
            }
        }

        // What's new sheet, opened from the About row above. Full-screen Dialog so it
        // covers the whole screen like the macOS .sheet; closing just hides it.
        if (showWhatsNew) {
            Dialog(
                onDismissRequest = { showWhatsNew = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                    WhatsNewSheet(onClose = { showWhatsNew = false })
                }
            }
        }

        // Scoring guide sheet, opened from the About row above. Same full-screen Dialog idiom.
        if (showScoringGuide) {
            Dialog(
                onDismissRequest = { showScoringGuide = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                    ScoringGuideScreen(onClose = { showScoringGuide = false })
                }
            }
        }

        // "How NOOP works" primer sheet, opened from the About row above. Same full-screen Dialog idiom.
        if (showHowNoopWorks) {
            Dialog(
                onDismissRequest = { showHowNoopWorks = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                    HowNoopWorksScreen(onClose = { showHowNoopWorks = false })
                }
            }
        }

        // "WHOOP 4.0 vs 5.0/MG" explainer sheet (FI-2 / #490), opened from the Strap section. Same idiom.
        if (showModelComparison) {
            Dialog(
                onDismissRequest = { showModelComparison = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                    WhoopModelComparisonScreen(onClose = { showModelComparison = false })
                }
            }
        }

        // Steps-estimate calibration, opened from the Profile card's "Steps estimate" row. Same
        // full-screen Dialog idiom; a manual-coefficient write bumps `rev` so the Profile summary
        // row reflects the new state on dismiss.
        if (showStepsCalibration) {
            Dialog(
                onDismissRequest = { showStepsCalibration = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                    StepsCalibrationScreen(
                        vm = vm,
                        profile = profile,
                        onProfileChanged = { rev++ },
                        onClose = { showStepsCalibration = false },
                    )
                }
            }
        }
    }
}

private const val SUPPORT_EMAIL = "thenoopapp@gmail.com"

// MARK: - App icon swap (v3 "Titanium & Gold")

/**
 * The two launcher-icon aliases declared in AndroidManifest.xml. Exactly one is ever enabled — the
 * enabled one is the app's home-screen entry point and supplies the launcher icon.
 */
private const val ALIAS_DEFAULT = "com.noop.IconDefault" // machined titanium
private const val ALIAS_NAVY = "com.noop.IconNavy"       // blued / dark-blue titanium

/**
 * Persist the chosen launcher icon and flip the manifest aliases so exactly one is enabled:
 * [navy] true enables `.IconNavy` and disables `.IconDefault`, false does the inverse. We use
 * DONT_KILL_APP so the toggle doesn't tear down our own process. The home launcher may briefly hide
 * and redraw the icon (or take a few seconds) while it re-reads the component state — that's expected
 * and is the only user-visible side effect.
 */
private fun setAppIcon(context: Context, navy: Boolean) {
    NoopPrefs.setAppIconNavy(context, navy)
    val pm = context.packageManager
    pm.setComponentEnabledSetting(
        ComponentName(context, ALIAS_NAVY),
        if (navy) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
    pm.setComponentEnabledSetting(
        ComponentName(context, ALIAS_DEFAULT),
        if (navy) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}

// MARK: - Waist stepper (optional VO₂max input)

/** A typical adult waist (cm) used as the first value when stepping up from "unset" (0), so the field
 *  jumps to a sensible starting point rather than 1 cm. ~34" — the rough population midpoint. */
private const val WAIST_SEED_CM = 86.0

/** Step the waist by one centimetre, seeding [WAIST_SEED_CM] when starting from unset (0). Stepping
 *  down from the seed cannot go below the seed (it never silently re-enters the "unset" sentinel). */
private fun waistCmStep(current: Double, up: Boolean): Double {
    if (current <= 0.0) return if (up) WAIST_SEED_CM else 0.0
    return (current + if (up) 1.0 else -1.0).coerceAtLeast(WAIST_SEED_CM - 30.0)
}

/** Step the waist by one inch (entry unit in imperial; stored as cm), seeding [WAIST_SEED_CM] from
 *  unset. Snaps to whole inches so the up/down sequence is symmetric, mirroring the Height field. */
private fun waistInchesStep(current: Double, up: Boolean): Double {
    if (current <= 0.0) return if (up) WAIST_SEED_CM else 0.0
    val inches = UnitFormatter.cmToInches(current).roundToInt()
    val nextInches = (inches + if (up) 1 else -1)
    val nextCm = nextInches * UnitFormatter.CENTIMETERS_PER_INCH
    return nextCm.coerceAtLeast(WAIST_SEED_CM - 30.0)
}

// MARK: - Strap status helpers (mirror SettingsView's computed properties)

@androidx.annotation.StringRes
private fun strapStatusTitle(bonded: Boolean, connected: Boolean): Int = when {
    bonded && connected -> R.string.settings_strap_status_streaming
    connected -> R.string.settings_strap_status_connected
    bonded -> R.string.settings_strap_status_idle
    else -> R.string.settings_strap_status_disconnected
}

private fun strapTone(bonded: Boolean, connected: Boolean): StrandTone = when {
    connected -> StrandTone.Positive
    bonded -> StrandTone.Warning
    else -> StrandTone.Critical
}

// `internal` (not private) so the unit test in the same package can assert the scanning branch.
@androidx.annotation.StringRes
internal fun strapStatusDetail(bonded: Boolean, connected: Boolean, scanning: Boolean): Int = when {
    scanning -> R.string.settings_strap_detail_searching
    bonded && connected -> R.string.settings_strap_detail_streaming
    connected -> R.string.settings_strap_detail_connecting
    bonded -> R.string.settings_strap_detail_bonded_idle
    else -> R.string.settings_strap_detail_disconnected
}

private fun batteryTone(pct: Double): StrandTone = when {
    pct <= 15 -> StrandTone.Critical
    pct <= 30 -> StrandTone.Warning
    else -> StrandTone.Positive
}

// MARK: - Sex options

private data class SexOption(val tag: String, val label: Int)

private val SEX_OPTIONS = listOf(
    SexOption("male", R.string.settings_sex_male),
    SexOption("female", R.string.settings_sex_female),
    SexOption("nonbinary", R.string.settings_sex_nonbinary),
)

// MARK: - Section card (ports SettingsView's private SettingsSection)

/**
 * A grouped settings card: a "Settings" overline + icon + title header, an explanatory blurb, then
 * content. A faint brand-green wash anchors the card to NOOP's neutral chrome (mirrors macOS).
 */
@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    blurb: String,
    content: @Composable () -> Unit,
) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline(stringResource(R.string.settings_section_overline))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                    Text(title, style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

// MARK: - Labelled toggle row (title + detail + trailing Switch)

/**
 * A title + explanatory detail on the left with a trailing [Switch], matching the in-section toggle idiom
 * the Strap/Health Connect sections already use. Used by the v5 Health & wellness group so every opt-in
 * reads consistently. The switch colours mirror the rest of Settings (gold track when on).
 */
@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = NoopType.subhead, color = Palette.textPrimary)
            Text(detail, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset,
                uncheckedBorderColor = Palette.hairline,
            ),
        )
    }
}

// MARK: - Two-column form row (ports SettingsView's private FormRow)

/** Label on the left, control on the right — the two-column form feel. */
@Composable
private fun FormRow(label: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            style = NoopType.body,
            color = Palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        control()
    }
}

// MARK: - Shared bits

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Palette.hairline),
    )
}

@Composable
private fun NoteRow(icon: ImageVector, iconTint: Color, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(text, style = NoopType.footnote, color = Palette.textSecondary)
    }
}

@Composable
private fun AttributionRow(repo: String, note: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = "$repo, $note" },
    ) {
        Text("›", style = NoopType.headline, color = Palette.accent)
        Text(repo, style = NoopType.mono(12f), color = Palette.textPrimary)
        Text("· $note", style = NoopType.footnote, color = Palette.textTertiary)
    }
}
