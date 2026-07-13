plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    // Pinned to 1.23.6: last detekt release built against Kotlin 1.9.x. Bump only with Kotlin.
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}
