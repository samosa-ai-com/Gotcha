package com.gotcha

/**
 * The first screen's three choices. The terms and age boxes are the gate; the
 * usage-data box is a preference and can never enable or disable the button on
 * its own.
 */
internal data class LegalConsentChoices(
    val termsAccepted: Boolean = false,
    val ageConfirmed: Boolean = false,
    val usageData: Boolean = false
) {
    val canContinue: Boolean get() = termsAccepted && ageConfirmed
}

/**
 * The usage-data box starts on only where the regional default does. An
 * unknown region (registration has not answered yet) fails closed, so the box
 * is off rather than guessed on.
 */
internal fun initialUsageDataChecked(region: String?, defaultUsageData: Boolean): Boolean =
    region != null && defaultUsageData

/**
 * What is recorded at submit. If the region is still unknown by then, the box
 * is forced off: declining is the safe default when the region cannot be
 * classified.
 */
internal fun usageDataOnSubmit(checked: Boolean, region: String?): Boolean =
    checked && region != null
