package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the Settings → Strap status detail copy, in particular that an in-flight scan takes
 * precedence over bonded/connected so the user gets "Searching…" feedback the moment Re-scan is
 * tapped (issue #1). The button's `enabled = !live.scanning` relies on the same scanning flag, so
 * a regression here is the visible half of "Re-scan does nothing".
 *
 * Since i18n, the helper returns a @StringRes id rather than the literal copy; the localized text
 * lives in strings.xml / values-de. The assertions below pin the branch→resource mapping.
 */
class StrapStatusDetailTest {

    @Test
    fun scanning_takesPrecedence_overEveryOtherState() {
        // Even when already bonded + connected, an active scan must map to the searching copy.
        assertEquals(
            R.string.settings_strap_detail_searching,
            strapStatusDetail(bonded = true, connected = true, scanning = true),
        )
        assertEquals(
            R.string.settings_strap_detail_searching,
            strapStatusDetail(bonded = false, connected = false, scanning = true),
        )
    }

    @Test
    fun nonScanning_branches_areUnchanged() {
        assertEquals(
            R.string.settings_strap_detail_streaming,
            strapStatusDetail(bonded = true, connected = true, scanning = false),
        )
        assertEquals(
            R.string.settings_strap_detail_connecting,
            strapStatusDetail(bonded = false, connected = true, scanning = false),
        )
        assertEquals(
            R.string.settings_strap_detail_bonded_idle,
            strapStatusDetail(bonded = true, connected = false, scanning = false),
        )
        assertEquals(
            R.string.settings_strap_detail_disconnected,
            strapStatusDetail(bonded = false, connected = false, scanning = false),
        )
    }
}
