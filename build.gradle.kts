plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    // Pinned to 1.23.6: last detekt release built against Kotlin 1.9.x. Bump only with Kotlin.
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
    // Coverage is a diagnostic, not a gate — see docs/FEATURE_TEST_COVERAGE.md for the
    // manifest that actually gates the build. No koverVerify thresholds by design.
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
}
