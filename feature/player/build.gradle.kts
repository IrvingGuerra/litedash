plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.irvingguerra.litedash.feature.player"
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
    implementation(project(":feature:music"))
    implementation(libs.core)
    implementation(libs.activity.compose)
    testImplementation(libs.junit)
    // org.json de verdad en las pruebas (el de Android es solo un esqueleto fuera del equipo).
    testImplementation(libs.json)
}
