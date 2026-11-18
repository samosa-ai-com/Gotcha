package com.gotcha.telemetry

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The closed schema for opt-in usage data.
 *
 * Every event has a fixed set of properties, and every property a fixed
 * vocabulary: a boolean, a small integer, a bucket, or a word from a list the
 * app owns. Nothing in it can hold text a user typed or saw — a prompt, a reply,
 * a file name, a command, an app's name. [Telemetry.track] drops any event that
 * does not match, so a mistake at a call site loses the event rather than
 * sending something it should not.
 *
 * The same table drives the "What we send" list on the Privacy page, and the
 * ingest server validates against a copy of it.
 */
object TelemetrySchema {

    /** Sent as `schema` in every batch; bump when a property changes meaning. */
    const val VERSION = 1

    /** Which product the ingest server files these events under. */
    const val PRODUCT = "gotcha"

    private val events = mutableMapOf<String, Map<String, Prop>>()

    /** Every event name, in declaration order. */
    val eventNames: List<String> get() = events.keys.toList()

    /** The properties [name] may carry, or null for an unknown event. */
    fun propsOf(name: String): Map<String, Prop>? = events[name]

    internal fun define(name: String, vararg props: Pair<String, Prop>) {
        check(name !in events) { "event $name defined twice" }
        events[name] = props.toMap()
    }

    /**
     * True when [event] is one this schema describes: a known name, every
     * required property present, no property it does not list, and every value
     * inside that property's vocabulary.
     */
    fun accepts(event: TelemetryEvent): Boolean {
        val spec = events[event.name] ?: return false
        if (!spec.keys.containsAll(event.props.keys)) return false
        return spec.all { (key, prop) ->
            val value = event.props[key]
            if (value == null) prop.optional else prop.vocab.accepts(value)
        }
    }

    init {
        TelemetryEvents.defineAll(this)
    }
}

/** One property of an event: what values it may take, and whether it may be left out. */
data class Prop(val vocab: Vocab, val optional: Boolean = false)

/** The values a property may take. */
sealed interface Vocab {
    fun accepts(value: JsonPrimitive): Boolean

    data object Bool : Vocab {
        override fun accepts(value: JsonPrimitive) = !value.isString && value.booleanOrNull != null
    }

    /** A whole number in [range]: version codes and similar small counters. */
    data class Count(val range: IntRange) : Vocab {
        override fun accepts(value: JsonPrimitive) = !value.isString && value.intOrNull?.let { it in range } == true
    }

    /** One of a fixed list of words. */
    data class Words(val words: Set<String>) : Vocab {
        override fun accepts(value: JsonPrimitive) = value.isString && value.content in words
    }

    /**
     * A name taken from the app's own code, such as an exception class: limited
     * to identifier characters and a length, so a message or a path cannot pass.
     */
    data class CodeName(val maxLength: Int) : Vocab {
        override fun accepts(value: JsonPrimitive) =
            value.isString && value.content.length in 1..maxLength && CODE_NAME.matches(value.content)

        private companion object {
            val CODE_NAME = Regex("[A-Za-z0-9_.$]+")
        }
    }
}

/** Ranges are bucketed so a value says how much, never exactly how much. */
object Buckets {
    private val MS_EDGES = listOf(100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 15_000L, 60_000L)

    /** Durations in milliseconds. */
    val MS: Set<String> = (MS_EDGES.map { "<$it" } + ">=60000").toSet()

    /** Small counts. */
    val COUNT: Set<String> = setOf("0", "1", "2-4", "5-9", "10-24", "25+")

    /** Durations in seconds. */
    val SECONDS: Set<String> = setOf("0-5", "6-30", "31-120", "121-600", "601-1800", ">1800")

    /** Token counts. */
    val TOKENS: Set<String> = setOf("<1k", "<4k", "<16k", "<64k", "<128k", ">=128k")

    fun ms(value: Long): String = MS_EDGES.firstOrNull { value < it }?.let { "<$it" } ?: ">=60000"

    @Suppress("MagicNumber")
    fun count(value: Int): String = when {
        value <= 0 -> "0"
        value == 1 -> "1"
        value <= 4 -> "2-4"
        value <= 9 -> "5-9"
        value <= 24 -> "10-24"
        else -> "25+"
    }

    @Suppress("MagicNumber")
    fun seconds(value: Long): String = when {
        value <= 5 -> "0-5"
        value <= 30 -> "6-30"
        value <= 120 -> "31-120"
        value <= 600 -> "121-600"
        value <= 1_800 -> "601-1800"
        else -> ">1800"
    }

    @Suppress("MagicNumber")
    fun tokens(value: Int): String = when {
        value < 1_000 -> "<1k"
        value < 4_000 -> "<4k"
        value < 16_000 -> "<16k"
        value < 64_000 -> "<64k"
        value < 128_000 -> "<128k"
        else -> ">=128k"
    }
}
