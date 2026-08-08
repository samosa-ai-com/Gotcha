// Custom detekt rules for the Gotcha repo. Compiled against detekt-api 1.23.6
// (the version the app pins) and the same kotlin-compiler-embeddable 1.9.x that
// detekt bundles, so rule bytecode stays binary-compatible at runtime.
plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.6")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.22")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-api:1.23.6")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.6")
    testImplementation("junit:junit:4.13.2")
}
