plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.irvingguerra.litedash.feature.updater"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
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
    implementation(platform(libs.compose.bom))
    // Solo el estado (mutableStateOf, @Immutable): la interfaz del actualizador vive en la app.
    implementation(libs.compose.runtime)
    // FileProvider e IntentCompat (la app ya lo trae con Activity: no suma peso).
    implementation(libs.core)
    testImplementation(libs.junit)
    // org.json de verdad en las pruebas (el de Android es solo un esqueleto fuera del equipo).
    testImplementation(libs.json)
}
