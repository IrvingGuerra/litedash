plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.irvingguerra.litedash.feature.music"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:ui"))
    implementation(libs.core)
    testImplementation(libs.junit)
    // org.json de verdad en las pruebas (el de Android es solo un esqueleto fuera del equipo).
    testImplementation(libs.json)
}
