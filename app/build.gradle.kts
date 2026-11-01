import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Versión: solo en version.properties; el versionCode se calcula.
val appVersionName: String = Properties().run {
    rootProject.file("version.properties").inputStream().use { load(it) }
    getProperty("VERSION_NAME")
}

fun versionCodeFrom(name: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.(\d+))?$""").matchEntire(name)
        ?: error("VERSION_NAME inválido: $name (formato X.Y.Z, X.Y.Z-beta.N o X.Y.Z-rc.N)")
    val (major, minor, patch, type, n) = m.destructured
    val suffix = when (type) {
        "beta" -> n.toInt().also { require(it in 1..49) { "beta.N va de 1 a 49" } }
        "rc" -> 49 + n.toInt().also { require(it in 1..49) { "rc.N va de 1 a 49" } }
        else -> 99
    }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + suffix
}

// Firma: key.properties fuera del repo (local) o variables de entorno (CI). Sin ellas, firma de depuración.
val signing = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
// Catálogo de autos (solo imágenes y datos; ver feature/car): igual en Play y en GitHub.
// Publicado en el release "catalogo" (prerelease). Para probar uno local en el
// emulador: ./gradlew ... -Pcatalog=http://10.0.2.2:8765/catalogo.json
val CATALOG_URL = (findProperty("catalog") as String?)
    ?: "https://github.com/IrvingGuerra/litedash/releases/download/catalogo/catalogo.json"
val hasSigning = signingValue("storeFile", "LITEDASH_STORE_FILE") != null

android {
    namespace = "com.irvingguerra.litedash"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.irvingguerra.litedash"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeFrom(appVersionName)
        versionName = appVersionName
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signingValue("storeFile", "LITEDASH_STORE_FILE")!!)
                storePassword = signingValue("storePassword", "LITEDASH_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "LITEDASH_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "LITEDASH_KEY_PASSWORD")
            }
        }
    }

    // Mismo código, dos canales. Mismo applicationId y versionCode.
    flavorDimensions += "channel"
    productFlavors {
        create("play") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"play\"")
            buildConfigField("String", "CATALOG_URL", "\"$CATALOG_URL\"")
            // Play prohíbe actualizarse por fuera de la tienda: sin actualizador.
            buildConfigField("String", "UPDATES_URL", "\"\"")
        }
        create("github") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"github\"")
            buildConfigField("String", "CATALOG_URL", "\"$CATALOG_URL\"")
            buildConfigField("String", "UPDATES_URL", "\"https://api.github.com/repos/IrvingGuerra/litedash/releases?per_page=20\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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

    // El idioma se elige dentro de la app: Play debe traer español e inglés siempre.
    bundle {
        language { enableSplit = false }
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
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:music"))
    implementation(project(":feature:car"))
    implementation(project(":feature:updater"))
    implementation(project(":feature:navigation"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)
}
