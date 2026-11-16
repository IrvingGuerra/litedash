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

// Novedades de esta versión (su sección del CHANGELOG), para mostrarlas en Acerca de.
val releaseNotes: String = rootProject.file("CHANGELOG.md").readText()
    .substringAfter("## [$appVersionName]", "")
    .substringAfter("\n").substringBefore("\n## [").trim()

/**
 * El único cálculo del versionCode (X.Y.Z; nunca beta/rc): MAYOR×10 000 000 + MENOR×100 000 + PARCHE×100 + 99. Desde la
 * 1.4.100 (antes ×1 000 000 / ×10 000: la 1.4.100 chocaba con la 1.5.0); el 99 final se queda para no bajar de número. El
 * actualizador compara versiones por su nombre, no con este número.
 */
fun versionCodeFrom(name: String): Int {
    val (major, minor, patch) = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(name)?.destructured
        ?: error("VERSION_NAME inválido: $name (formato X.Y.Z)")
    require(patch.toInt() <= 999) { "el parche va de 0 a 999" }
    return major.toInt() * 10_000_000 + minor.toInt() * 100_000 + patch.toInt() * 100 + 99
}

// Firma: key.properties fuera del repo (local) o variables de entorno (CI). Sin ellas, firma de depuración.
val signing = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
// Catálogo de autos (solo imágenes y datos; ver feature/car): igual en Play y en GitHub (va en defaultConfig).
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
        minSdk = 23
        targetSdk = 37
        versionCode = versionCodeFrom(appVersionName)
        versionName = appVersionName
        buildConfigField("String", "CATALOG_URL", "\"$CATALOG_URL\"")
        buildConfigField("String", "RELEASE_NOTES", "\"" + releaseNotes.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"")
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

    // Solo los idiomas de LiteDash: sin esto vienen los ~80 idiomas de las librerías de AndroidX.
    androidResources {
        localeFilters += listOf("es", "en")
    }

    // Mismo código, dos canales, mismo applicationId y versionCode. Lo que solo es de un canal vive en app/src/<canal>.
    flavorDimensions += "channel"
    productFlavors {
        create("play") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"play\"")
        }
        create("github") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"github\"")
            buildConfigField("String", "UPDATES_URL", "\"https://api.github.com/repos/IrvingGuerra/litedash/releases?per_page=20\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sin META-INF/version-control-info.textproto: el commit ya lo dice la etiqueta del release.
            vcsInfo.include = false
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        // Igual que release pero firmable en local: es lo que mide el módulo :benchmark.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // Sin ofuscar: el Baseline Profile se genera con esta variante y debe traer los nombres reales.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "benchmark-rules.pro")
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
        warningsAsErrors = false
    }

    // El idioma se elige dentro de la app: Play debe traer español e inglés siempre.
    bundle {
        language { enableSplit = false }
    }

    packaging {
        // Lo que la app no usa al correr: licencias y versiones sueltas de las librerías (los
        // créditos van en Sistema) y los metadatos de Kotlin para reflexión (no se usa).
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "DebugProbesKt.bin",
            "/META-INF/androidx/**/LICENSE.txt",
            "/META-INF/*.version",
            "/kotlin/**.kotlin_builtins",
        )
        // Las .so (la de trazos de Compose, ~10 KB por arquitectura) van comprimidas y Android las
        // extrae al instalar: sin comprimir, cada una se alinea a 16 KB y el relleno pesaba más
        // que ellas (hasta ~45 KB de hueco en la APK).
        jniLibs.useLegacyPackaging = true
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
    implementation(project(":feature:player"))
    implementation(project(":feature:car"))
    // Play prohíbe actualizarse por fuera de la tienda: el actualizador solo viaja en la versión de GitHub.
    "githubImplementation"(project(":feature:updater"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:map"))
    implementation(project(":core:vehicle"))
    implementation(project(":feature:obd"))
    implementation(project(":feature:tpms"))
    implementation(project(":feature:link"))

    // Compose (foundation y ui) llega de core:ui; el BOM se queda para las librerías de Compose sin versión (tooling).
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.core)
    implementation(libs.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    // org.json de verdad en las pruebas (el de Android es solo un esqueleto fuera del equipo).
    testImplementation(libs.json)
}
