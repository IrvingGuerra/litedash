package com.irvingguerra.litedash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
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
    /** Del lado que debe quedar el auto antes de abrir la app (true = izquierda). */
    private const val EXTRA_LEFT = "izquierda"

    /** Cada cuánto el auto revisa si ya quedó en su lado, y hasta cuándo esperar. */
    private const val CHECK_MS = 100L
    private const val GIVE_UP_MS = 3_000L

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
        start(context, intent, d.prefs.splitCarLeft)
        return true
    }

    /**
     * Android solo arma la división desde la ventana que está al frente, y lo que se abre
     * "al lado" queda del otro lado de quien lo abre. Por eso todo sale de ventanas visibles:
     * - Auto a la izquierda desde el inicio: el auto (completo) abre la app a su lado.
     * - Auto a la derecha: una ventana de paso ([SplitStartActivity]) abre el auto a su lado
     *   (a la derecha) y el auto, ya en su lugar, abre la app a su lado: donde estaba la de paso.
     */
    internal fun start(context: Context, app: Intent, carLeft: Boolean) {
        val target = if (carLeft) SplitActivity::class.java else SplitStartActivity::class.java
        val intent = Intent(context, target)
            .putExtra(EXTRA_APP, app)
            .putExtra(EXTRA_OPEN, true)
            .putExtra(EXTRA_LEFT, carLeft)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    internal fun appOf(intent: Intent): Intent? = IntentCompat.getParcelableExtra(intent, EXTRA_APP, Intent::class.java)

    /**
     * El auto abre la app a su lado, pero hasta que ya quedó del lado que le toca (en un
     * celular o pantalla lenta la división tarda en acomodarse). Si no llega en [GIVE_UP_MS],
     * la abre igual: mejor del otro lado que no abrirla.
     */
    internal fun openBeside(activity: SplitActivity, intent: Intent) {
        val app = appOf(intent) ?: return
        if (!intent.getBooleanExtra(EXTRA_OPEN, false)) return
        // Ya se armó: que no se vuelva a abrir al girar la pantalla o regresar.
        intent.removeExtra(EXTRA_OPEN)
        val left = intent.getBooleanExtra(EXTRA_LEFT, true)
        val view = activity.window.decorView
        val since = System.currentTimeMillis()
        fun tryOpen() {
            if (activity.isFinishing) return
            if (!activity.onSide(left) && System.currentTimeMillis() - since < GIVE_UP_MS) {
                view.postDelayed(::tryOpen, CHECK_MS)
                return
            }
            runCatching { activity.startActivity(Intent(app).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        view.post(::tryOpen)
    }

    /** La ventana de paso abre el auto a su lado, que debe quedar a la [EXTRA_LEFT]. */
    internal fun moveCar(activity: ComponentActivity, intent: Intent) {
        val app = appOf(intent) ?: return activity.finish()
        val board = Intent(activity, SplitActivity::class.java)
            .putExtra(EXTRA_APP, app)
            .putExtra(EXTRA_OPEN, true)
            .putExtra(EXTRA_LEFT, intent.getBooleanExtra(EXTRA_LEFT, false))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        runCatching { activity.startActivity(board) }
    }

    /**
     * Cambia el auto de lado sin cerrar nada (cerrar y volver a abrir desde atrás lo bloquea
     * Android en los equipos de verdad): el auto abre la ventana de paso en su mismo lugar, esa
     * ventana manda el auto al otro lado y el auto abre la app donde quedó la de paso.
     */
    internal fun swap(activity: SplitActivity, app: Intent?) {
        app ?: return
        val prefs = (activity.application as LiteDashApp).dependencies.prefs
        prefs.splitCarLeft = !prefs.splitCarLeft
        val step = Intent(activity, SplitStartActivity::class.java)
            .putExtra(EXTRA_APP, app)
            .putExtra(EXTRA_LEFT, prefs.splitCarLeft)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        runCatching { activity.startActivity(step) }
    }
}

/** ¿La ventana ya está del lado [left] de la pantalla? (Completa cuenta como izquierda.) */
private fun ComponentActivity.onSide(left: Boolean): Boolean {
    val at = IntArray(2)
    window.decorView.getLocationOnScreen(at)
    val screen = resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val onLeft = at[0] < screen / 4
    if (!left) return !onLeft
    // A la izquierda: completa (recién abierto desde el inicio) o ya en la mitad izquierda.
    return onLeft && (!isInMultiWindowMode || window.decorView.width < screen * 3 / 4)
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

    // Ya con el auto al frente (y en su lado), se arma la división desde su ventana.
    private fun openAppIfNeeded() = SplitScreen.openBeside(this, intent)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(contextWithLanguage(base))
}

/**
 * Ventana de paso, oscura y de un instante (opaca: desde una transparente Android a veces no
 * divide): manda el auto a su lado. Es noHistory: se va sola cuando la app la tapa.
 */
class SplitStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) window.decorView.post { SplitScreen.moveCar(this, intent) }
    }
}

@Composable
private fun SplitBoard(d: Dependencies, onSwap: () -> Unit, onExit: () -> Unit) {
    val c = LocalColors.current
    val car = remember(d.prefs.carModel) { d.cars.pkg(d.prefs.carModel) }
    Box(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        HomeCar(d, car, onClick = {}, modifier = Modifier.fillMaxSize().padding(16.dp))
        Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
