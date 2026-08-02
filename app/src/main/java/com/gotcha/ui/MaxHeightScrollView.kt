package com.gotcha.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.ScrollView

/**
 * A [ScrollView] that caps its measured height at [maxHeightPx].
 *
 * Used inside transient overlay cards (Lens, Assistive Ball, Screen Companion)
 * so a long body string can't push the card off the screen. The default
 * [ScrollView] honors `layoutParams` exactly, so without this cap the card
 * inherits WRAP_CONTENT and a long message grows until the chip row is
 * clipped — the bug is reproduced in `ScreenLensController` and both
 * `AssistiveBallOverlay` success/info and proactive panel call sites.
 *
 * When [maxHeightPx] is left at [Int.MAX_VALUE], the wrapper passes the
 * original height spec through unchanged, so it is a safe drop-in anywhere
 * a plain `ScrollView` would work.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** Maximum height in pixels. [Int.MAX_VALUE] means no cap. */
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = maxHeightPx
        val effectiveHeightSpec = if (cap < Int.MAX_VALUE) {
            MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, effectiveHeightSpec)
    }
}
