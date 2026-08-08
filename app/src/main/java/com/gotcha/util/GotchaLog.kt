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
    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    fun d(tag: String, throwable: Throwable?, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message(), throwable)
    }

    fun v(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag, message())
    }

    fun v(tag: String, throwable: Throwable?, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag, message(), throwable)
    }
}
