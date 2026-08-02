import java.util.Properties
import org.gradle.api.tasks.PathSensitivity

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load signing credentials from local.properties (gitignored), if present.
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Developer-specific Samosa endpoints. A public checkout builds against inert
 * placeholders so it cannot reach the real backend; supply your own values via
 * an environment variable or `local.properties` (both gitignored, neither is
 * ever committed). Environment wins, so CI can override a developer's file.
 */
fun samosaConfig(name: String, placeholder: String): String =
    providers.environmentVariable(name).orNull
        ?: keystoreProps.getProperty(name)
        ?: placeholder

android {
    namespace = "com.gotcha"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gotcha"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField(
            "String",
            "SAMOSA_API_URL",
            "\"${samosaConfig("SAMOSA_API_URL", "https://api.samosa-ai.example")}\""
        )
        buildConfigField(
            "String",
            "SAMOSA_SKILL_HOST",
            "\"${samosaConfig("SAMOSA_SKILL_HOST", "samosa-ai.example")}\""
        )
        buildConfigField(
            "String",
            "SAMOSA_WEB_CLIENT_ID",
            "\"${samosaConfig(
                "SAMOSA_WEB_CLIENT_ID",
                "YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com"
            )}\""
        )
    }

    signingConfigs {
        // Only wired up when release signing keys are provided in local.properties.
        if (keystoreProps.containsKey("RELEASE_STORE_FILE")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Use the release signing config only if it was configured above.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // android-mail and android-activation both bundle these under META-INF.
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        htmlReport = true
        sarifReport = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true // required by Robolectric
        // FeatureCoverageManifestTest rewrites docs/FEATURE_TEST_COVERAGE.md instead of
        // asserting on it when this is true:
        //   ./gradlew :app:testDebugUnitTest -PupdateCoverageDocs=true
        unitTests.all { test ->
            val updatingCoverageDocs = providers.gradleProperty("updateCoverageDocs").getOrElse("false")
            test.systemProperty("gotcha.coverage.update", updatingCoverageDocs)
            // The generated doc is an *input* to the drift assertion, so hand-editing it has to
            // invalidate the task — otherwise Gradle reports UP-TO-DATE and the gate never runs.
            // Skipped while regenerating, when the task writes that same file.
            if (updatingCoverageDocs != "true") {
                test.inputs
                    .file(rootProject.file("docs/FEATURE_TEST_COVERAGE.md"))
                    .withPropertyName("featureCoverageDoc")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
        animationsDisabled = true
        managedDevices {
            localDevices {
                // Android 11 (minSdk) — the aosp-atd x86 image for this API level appears to hang
                // on boot on Windows/WHPX (confirmed: qemu process alive but memory flat,
                // never progresses); the plain aosp x86 image boots fine.
                create("api30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    systemImageSource = "aosp"
                }
                // Android 12
                create("api31") {
                    device = "Pixel 4"
                    apiLevel = 31
                    systemImageSource = "aosp-atd"
                }
                // Android 13
                create("api33") {
                    device = "Pixel 6"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
                // Android 14
                create("api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
                // Android 15
                create("api35") {
                    device = "Pixel 8"
                    apiLevel = 35
                    systemImageSource = "aosp-atd"
                }
                // Android 16 — no ATD image published for this API level yet;
                // Gradle's own error suggested "google" as the available source.
                create("api36") {
                    device = "Pixel 8"
                    apiLevel = 36
                    systemImageSource = "google"
                }
            }
            groups {
                create("smoke") {
                    targetDevices.add(allDevices["api34"])
                }
                // API 35/36 bring background-start, foreground-service and notification
                // restrictions that hit an overlay/assistant app hard — the highest-risk
                // untested axis after OEM behaviour.
                create("full") {
                    targetDevices.add(allDevices["api30"])
                    targetDevices.add(allDevices["api33"])
                    targetDevices.add(allDevices["api34"])
                    targetDevices.add(allDevices["api35"])
                    targetDevices.add(allDevices["api36"])
                }
                // Android 11 (minSdk) through Android 16 — the range requested for manual
                // multi-version verification.
                create("android11to16") {
                    targetDevices.add(allDevices["api30"])
                    targetDevices.add(allDevices["api31"])
                    targetDevices.add(allDevices["api33"])
                    targetDevices.add(allDevices["api34"])
                    targetDevices.add(allDevices["api35"])
                    targetDevices.add(allDevices["api36"])
                }
            }
        }
    }
}

/**
 * Coverage reporting only — deliberately no `koverVerify` thresholds.
 *
 * A global percentage would be misleading here: most of `com.gotcha.tools` needs a Context,
 * and the Compose files in `com.gotcha.ui` generate synthetic bytecode that pollutes the
 * denominator. The gate is the feature manifest (FeatureCoverageManifestTest); this is the
 * diagnostic that tells you where to aim next. Watch `com.gotcha.tools`, `com.gotcha.agent`
 * and `com.gotcha.data`.
 *
 * Reports: ./gradlew :app:koverHtmlReport -> app/build/reports/kover/html/index.html
 */
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.gotcha.ui.theme.*",
                    "*ComposableSingletons*",
                    "*\$\$serializer",
                    "*_Factory",
                    "*Kt\$*\$*" // Compose/lambda synthetics
                )
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    // One-off formatting fixes: ./gradlew :app:detekt -PdetektAutoCorrect
    autoCorrect = project.hasProperty("detektAutoCorrect")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Lifecycle + ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Networking: Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON: kotlinx-serialization + Retrofit converter
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Secure storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Samosa AI: Google Sign-In via Credential Manager + Google Identity Services
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // HTML parsing for webfetch tool
    implementation("org.jsoup:jsoup:1.17.2")

    // Connectors: Custom Tabs for the BYO-OAuth consent flow
    implementation("androidx.browser:browser:1.8.0")

    // Connectors: IMAP/SMTP email (JavaMail for Android)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Health Connect: on-device fitness/health records (no cloud API, no credentials)
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    // connect-client pulls guava at *runtime* scope only, but guava's module metadata
    // constrains com.google.guava:listenablefuture to the empty
    // "9999.0-empty-to-avoid-conflict-with-guava" marker on every configuration — including
    // the compile classpath. Without full guava there too, CameraX's ListenableFuture usage
    // (MediaCaptureTool) no longer compiles. Pinned to the version connect-client already
    // resolves to, so nothing changes at runtime.
    implementation("com.google.guava:guava:31.1-android")

    // Markdown rendering
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.17.0")
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.17.0")
    implementation("io.noties.markwon:core:4.6.2") {
        exclude(group = "com.atlassian.commonmark", module = "commonmark")
    }

    // ML Kit on-device text recognition (for Lens mode OCR) and barcode scanning
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // CameraX for automated photo capture
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Static analysis
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Robolectric tier: runs Context-dependent tools on the JVM, and via @Config(sdk = [...])
    // exercises SDK_INT branches across API levels far more cheaply than an emulator matrix row.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("io.mockk:mockk:1.13.13")

    // Instrumented tests
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
