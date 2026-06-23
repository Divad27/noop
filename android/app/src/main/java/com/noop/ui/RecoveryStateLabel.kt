package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.noop.R

/**
 * Localized recovery-state word for a 0–100 score. Mirrors the thresholds of
 * [Palette.recoveryState] (which stays canonical en-US — DEPLETED/LOW/MODERATE/PRIMED/PEAK — for
 * §9.3 logic parity); this routes the DISPLAY through the German string resources
 * (ERSCHÖPFT/NIEDRIG/MODERAT/BEREIT/SPITZE), the same producer-vs-display split the rest of the
 * i18n uses.
 */
@Composable
fun recoveryStateLabel(score: Double): String = when {
    score < 25 -> stringResource(R.string.recovery_state_depleted)
    score < 50 -> stringResource(R.string.recovery_state_low)
    score < 70 -> stringResource(R.string.recovery_state_moderate)
    score < 88 -> stringResource(R.string.recovery_state_primed)
    else -> stringResource(R.string.recovery_state_peak)
}
