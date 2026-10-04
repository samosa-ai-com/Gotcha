package com.gotcha.i18n

import android.content.Context
import androidx.annotation.StringRes

/**
 * Reads a string resource, formatted with [get]'s args. For code outside
 * Compose that still has to produce display text — the settings search index,
 * error messages built in a callback — so it can be handed a [Context]'s
 * strings in the app and the same in a unit test.
 */
fun interface StringLookup {
    fun get(@StringRes id: Int, vararg args: Any): String

    operator fun invoke(@StringRes id: Int, vararg args: Any): String = get(id, *args)
}

/** This context's strings, in its current display language. */
@Suppress("SpreadOperator") // forwarding the format args is the whole point
fun Context.stringLookup(): StringLookup = StringLookup { id, args -> getString(id, *args) }
