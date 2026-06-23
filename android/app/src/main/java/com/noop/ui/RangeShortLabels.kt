package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.noop.R

/**
 * Localized short label for a time-range pill code. The selector enums on several screens carry
 * canonical en-US codes — the Trends family uses "W"/"M"/"3M"/"6M"/"1Y"/"ALL", the Workouts family
 * uses "7D"/"30D"/"90D"/"1Y"/"All". German shows "1J"/"ALLE"/"Alle" and "7T"/"30T"/"90T"; the rest
 * are identical. Build the map in @Composable scope, then pass a pure lookup lambda to
 * SegmentedPillControl (whose `label` lambda is not @Composable).
 */
@Composable
fun <T> rememberRangeShortLabels(items: List<T>, code: (T) -> String): Map<T, String> {
    val year = stringResource(R.string.trends_window_year_short)
    val all = stringResource(R.string.trends_window_all_short)
    val allWord = stringResource(R.string.range_short_all_word)
    val d7 = stringResource(R.string.range_short_7d)
    val d30 = stringResource(R.string.range_short_30d)
    val d90 = stringResource(R.string.range_short_90d)
    return items.associateWith { t ->
        when (code(t)) {
            "1Y" -> year
            "ALL" -> all
            "All" -> allWord
            "7D" -> d7
            "30D" -> d30
            "90D" -> d90
            else -> code(t)
        }
    }
}
