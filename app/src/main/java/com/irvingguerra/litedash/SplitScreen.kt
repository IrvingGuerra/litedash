package com.irvingguerra.litedash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Pantalla dividida real del sistema: la app de mapa o de música en una mitad y el auto
 * (3D o en carretera) en la otra. El inicio no puede ser una mitad, así que el auto vive en
 * [SplitActivity], una actividad aparte en su propia tarea; el inicio no se toca y, al
 * regresar, se ve como estaba. Necesita Android 12L o más nuevo y la pantalla acostada.
 */
object SplitScreen {
    private const val EXTRA_APP = "app"
    private const val EXTRA_OPEN = "abrir"

    /** Tras abrir la app a pantalla completa, cuánto esperar para poner el auto a su lado. */
    internal const val ADJACENT_DELAY_MS = 600L

    /** Al cambiar de lado, cuánto esperar a que se quite la división de antes. */
    private const val SWAP_DELAY_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())

    /** Si esta pantalla deja abrir dos apps lado a lado desde una app (Android 12L+). */
    // El ajuste del sistema no tiene constante pública: se busca por nombre (una vez por consulta).
    @SuppressLint("DiscouragedApi")
    fun supported(): Boolean {
        if (Build.VERSION.SDK_INT < 32) return false
        val r = Resources.getSystem()
        val id = r.getIdentifier("config_supportsMultiWindow", "bool", "android")
        return id == 0 || r.getBoolean(id)
    }

    /** Las apps que se abren divididas: las de mapa y de música (las conocidas, la elegida o por su categoría). */
    private fun isMapOrMusic(context: Context, d: Dependencies, pkg: String): Boolean {
        if (pkg in NAVIGATION_PACKAGES || pkg in MUSIC_PACKAGES || pkg == d.prefs.musicApp) return true
        if (d.prefs.mapApp?.substringBefore('/') == pkg) return true
        val category = runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
        return category == ApplicationInfo.CATEGORY_MAPS || category == ApplicationInfo.CATEGORY_AUDIO
    }

    /** Abre [intent] dividida si toca; false = que se abra normal. */
    fun open(context: Context, intent: Intent, pkg: String): Boolean {
        val d = (context.applicationContext as LiteDashApp).dependencies
        if (!d.prefs.splitScreen || !supported()) return false
        if (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        if (!isMapOrMusic(context, d, pkg)) return false
        start(context.applicationContext, intent, d.prefs.splitCarLeft)
        return true
    }

    /**
     * Android solo arma la división desde la ventana que está al frente, y lo que abre "al
     * lado" queda del otro lado. Auto a la izquierda: el auto, al frente, abre la app a su lado.
     * Auto a la derecha: una ventana invisible ([SplitStartActivity]) abre el auto a su lado y
     * el auto abre la app en el lado de la invisible, que se va sola (noHistory).
     */
    internal fun start(context: Context, app: Intent, carLeft: Boolean) {
        val target = if (carLeft) SplitActivity::class.java else SplitStartActivity::class.java
        val intent = Intent(context, target)
            .putExtra(EXTRA_APP, app)
            .putExtra(EXTRA_OPEN, carLeft)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    internal fun appOf(intent: Intent): Intent? = IntentCompat.getParcelableExtra(intent, EXTRA_APP, Intent::class.java)

    /** El auto ya al frente abre la app del otro lado (una sola vez). */
    internal fun openBeside(activity: SplitActivity, intent: Intent) {
        val app = appOf(intent) ?: return
        if (!intent.getBooleanExtra(EXTRA_OPEN, false)) return
        // Ya se armó: que no se vuelva a abrir al girar la pantalla o regresar.
        intent.removeExtra(EXTRA_OPEN)
        runCatching { activity.startActivity(Intent(app).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Auto a la derecha: el auto se abre al lado de esta ventana (a la derecha) y desde ahí
     * abre la app "a su lado", que ya es la izquierda. Abrirla de golpe sin "al lado" traía
     * su tarea completa al frente y deshacía la división si ya estaba abierta.
     */
    internal fun joinRight(activity: ComponentActivity, intent: Intent) {
        val app = appOf(intent) ?: return activity.finish()
        val board = Intent(activity, SplitActivity::class.java)
            .putExtra(EXTRA_APP, app)
            .putExtra(EXTRA_OPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        runCatching { activity.startActivity(board) }
    }

    /**
     * Cambia el auto de lado: se cierra el auto (la app queda completa) y se vuelve a armar
     * al revés. Armar encima de una división abierta no mueve las ventanas.
     */
    internal fun swap(activity: SplitActivity, app: Intent?) {
        val prefs = (activity.application as LiteDashApp).dependencies.prefs
        prefs.splitCarLeft = !prefs.splitCarLeft
        val context = activity.applicationContext
        activity.finishAndRemoveTask()
        // Que Android termine de quitar la división antes de armarla otra vez.
        if (app != null) handler.postDelayed({ start(context, app, prefs.splitCarLeft) }, SWAP_DELAY_MS)
    }
}

/** El auto solo, para la mitad de la pantalla dividida, con cambiar de lado y salir. */
class SplitActivity : ComponentActivity() {
    private val dependencies get() = (application as LiteDashApp).dependencies
    private val revision = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiteDashContent(dependencies, revision.intValue) { SplitBoard(dependencies, onSwap = { SplitScreen.swap(this, SplitScreen.appOf(intent)) }, onExit = { finishAndRemoveTask() }) }
        }
        if (savedInstanceState == null) openAppIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openAppIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        revision.intValue++
    }

    // Ya con el auto al frente, se arma la división desde su ventana.
    private fun openAppIfNeeded() {
        val current = intent
        // Con un respiro: si el auto acaba de entrar a la división, Android todavía la está armando.
        window.decorView.postDelayed({ SplitScreen.openBeside(this, current) }, SplitScreen.ADJACENT_DELAY_MS)
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(contextWithLanguage(base))
}

/** Ventana oscura de un instante (opaca: desde una transparente Android a veces no divide) para dejar el auto a la derecha (ver [SplitScreen.start]). */
class SplitStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) window.decorView.post { SplitScreen.joinRight(this, intent) }
    }
}

@Composable
private fun SplitBoard(d: Dependencies, onSwap: () -> Unit, onExit: () -> Unit) {
    val c = LocalColors.current
    val car = remember(d.prefs.carModel) { d.cars.pkg(d.prefs.carModel) }
    Box(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        HomeCar(d, car, onClick = {}, modifier = Modifier.fillMaxSize().padding(16.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(Icons.swap, onSwap, background = c.surfaceHigh)
            IconButton(Icons.expand, onExit, background = c.surfaceHigh)
        }
    }
}

/** Ajustes → Apps → Pantalla dividida. */
@Composable
fun SplitSettings(d: Dependencies) {
    val c = LocalColors.current
    val supported = remember { SplitScreen.supported() }
    SwitchRow(stringResource(R.string.split_title), d.prefs.splitScreen, { d.prefs.splitScreen = it }, stringResource(R.string.split_help))
    if (d.prefs.splitScreen) {
        Label(stringResource(R.string.split_side), Typography.label, c.textMuted)
        Segmented(
            listOf(true to stringResource(R.string.split_left), false to stringResource(R.string.split_right)),
            d.prefs.splitCarLeft,
            { d.prefs.splitCarLeft = it },
        )
        Label(stringResource(R.string.split_swap_help), Typography.small, c.textMuted)
    }
    if (!supported) Label(stringResource(R.string.split_unsupported, Build.VERSION.RELEASE), Typography.small, c.danger)
}
