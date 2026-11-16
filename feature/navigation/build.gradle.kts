plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.irvingguerra.litedash.feature.navigation"
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
    implementation(platform(libs.compose.bom))
    // Solo el estado (mutableStateOf, @Immutable): la interfaz de la siguiente vuelta vive en la app.
    implementation(libs.compose.runtime)
    testImplementation(libs.junit)
}
