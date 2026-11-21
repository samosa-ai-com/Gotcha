// Termux's terminal emulator and terminal view, copied into this repository so that no extra
// Maven repository is needed. See NOTICE.md for what is copied and what is ours.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 34

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
