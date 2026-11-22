package com.gotcha

/**
 * The first screen's three answers. The terms and age boxes are the gate; the
 * usage-data box is a preference and can never enable or disable the button on
 * its own.
 *
 * Usage data starts on in every region, so the box is simply true unless the
 * user unticks it. The region decides only whether the box is shown here at all
 * (see [usageRowVisible]).
 */
internal data class LegalConsentChoices(
    val termsAccepted: Boolean = false,
    val ageConfirmed: Boolean = false,
    val usageData: Boolean = true
) {
    val canContinue: Boolean get() = termsAccepted && ageConfirmed

    fun withUsageDataChoice(checked: Boolean): LegalConsentChoices = copy(usageData = checked)
}

/**
 * Where the first screen shows the usage-data row: the regions that require
 * prior consent, and any region still unknown. Elsewhere usage data is on by
 * default and lives only in Settings, so this screen stays short.
 *
 * An unknown region shows the row rather than hiding it — a user we cannot
 * classify should still see the control.
 */
internal fun usageRowVisible(region: String?): Boolean = region != "other"
