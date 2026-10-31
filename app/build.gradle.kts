import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Versión: solo en version.properties; el versionCode se calcula.
val nombreVersion: String = Properties().run {
    rootProject.file("version.properties").inputStream().use { load(it) }
    getProperty("VERSION_NAME")
}

fun versionCodeFrom(name: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.(\d+))?$""").matchEntire(name)
        ?: error("VERSION_NAME inválido: $name (formato X.Y.Z, X.Y.Z-beta.N o X.Y.Z-rc.N)")
    val (mayor, menor, parche, tipo, n) = m.destructured
    val sufijo = when (tipo) {
        "beta" -> n.toInt().also { require(it in 1..49) { "beta.N va de 1 a 49" } }
        "rc" -> 49 + n.toInt().also { require(it in 1..49) { "rc.N va de 1 a 49" } }
        else -> 99
    }
    return mayor.toInt() * 1_000_000 + menor.toInt() * 10_000 + parche.toInt() * 100 + sufijo
}

// Firma: key.properties fuera del repo (local) o variables de entorno (CI). Sin ellas, firma de depuración.
val firma = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun datoFirma(clave: String, env: String): String? = firma.getProperty(clave) ?: System.getenv(env)
val hayFirma = datoFirma("storeFile", "LITEDASH_STORE_FILE") != null

android {
    namespace = "com.irvingguerra.litedash"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.irvingguerra.litedash"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeFrom(nombreVersion)
        versionName = nombreVersion
    }

    signingConfigs {
        if (hayFirma) {
            create("release") {
                storeFile = file(datoFirma("storeFile", "LITEDASH_STORE_FILE")!!)
                storePassword = datoFirma("storePassword", "LITEDASH_STORE_PASSWORD")
                keyAlias = datoFirma("keyAlias", "LITEDASH_KEY_ALIAS")
                keyPassword = datoFirma("keyPassword", "LITEDASH_KEY_PASSWORD")
            }
        }
    }

    // Mismo código, dos canales. Mismo applicationId y versionCode.
    flavorDimensions += "canal"
    productFlavors {
        create("play") {
            dimension = "canal"
            buildConfigField("String", "CANAL", "\"play\"")
        }
        create("github") {
            dimension = "canal"
            buildConfigField("String", "CANAL", "\"github\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hayFirma) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        // Igual que release pero firmable en local: es lo que mide el módulo :benchmark.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Las versiones se fijan a mano; no son errores.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // aapt exige -v26 para adaptive-icon aunque minSdk ya sea 26.
        disable += "ObsoleteSdkInt"
        warningsAsErrors = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":feature:apps"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)
}
