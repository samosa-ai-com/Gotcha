package com.gotcha.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gotcha.R

/**
 * "Just now" under a minute, then Android's own "5 min. ago", "3 hours ago",
 * "2 days ago" — in the app's display language either way.
 */
@Composable
fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String =
    if (now - epochMillis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.time_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(
            epochMillis,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
