package com.gotcha.util

import android.util.Log
import com.gotcha.BuildConfig

/**
 * App-wide logging facade. `d`/`v` are no-ops in release builds: `BuildConfig.DEBUG`
 * folds to a constant `false`, and the message is a lambda so its string is never
 * built either. Raw `Log.w`/`Log.e` stay in use for failures worth reporting from
 * the field.
 */
object GotchaLog {
    // inline: in release BuildConfig.DEBUG folds to false, so the whole call
    // (lambda allocation included) is removed at the call site rather than
    // paying for a Function0 instance that never runs.
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    inline fun d(tag: String, throwable: Throwable?, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message(), throwable)
    }

    inline fun v(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag, message())
    }

    inline fun v(tag: String, throwable: Throwable?, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag, message(), throwable)
    }
}
