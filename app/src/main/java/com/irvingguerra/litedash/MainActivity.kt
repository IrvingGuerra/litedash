package com.irvingguerra.litedash

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.irvingguerra.litedash.core.ui.CardLook
import com.irvingguerra.litedash.core.ui.LiteDashTheme
import com.irvingguerra.litedash.core.ui.LocalCardLook
import com.irvingguerra.litedash.feature.link.PhoneLink

class MainActivity : ComponentActivity() {
    private val dependencies get() = (application as LiteDashApp).dependencies

    // Sube cada vez que se vuelve a la app (p. ej. después de dar un permiso en Ajustes).
    private val revision = mutableIntStateOf(0)
    // Sube al tocar el botón de inicio estando ya en LiteDash: cierra lo que esté abierto.
    private val homePressed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveTask = taskId
        liveOwner = System.identityHashCode(this)
        // Abierta desde otra app con un lugar (geo:): el mapa en grande lo enseña.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) PlaceRequests.fromGeo(intent.data)
        // Es la pantalla de inicio: "atrás" no la cierra (los paneles sí se cierran con atrás).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        // Borde a borde desde el primer cuadro (con el tema que se pueda saber aquí; el contenido lo afina).
        val theme = dependencies.prefs.theme
        edgeToEdge(if (theme == ThemeMode.SUN || theme == ThemeMode.SYSTEM) isSystemNight() else theme == ThemeMode.DARK)
        setContent {
            LiteDashContent(dependencies, revision.intValue) { Home(dependencies, revision.intValue, homePressed.intValue) }
        }
        // Al encender el auto LiteDash arranca antes que la barra del sistema de algunas pantallas (TS10): se quedaba
        // con el hueco de una barra que ya no está. Se vuelve a medir unas veces mientras la pantalla termina de arrancar.
        for (ms in REFIT_MS) window.decorView.postDelayed(refitLater, ms)
    }

    private val refitLater = Runnable { refit() }

    override fun onDestroy() {
        window.decorView.removeCallbacks(refitLater)
        // Por instancia: tras [restart] (o cualquier recreación) el onDestroy de la vieja llega después del onCreate de la
        // nueva, con la misma tarea; comparar la tarea dejaba -1 y [bringToFront] perdía su primer paso.
        if (liveOwner == System.identityHashCode(this)) {
            liveOwner = 0
            liveTask = -1
        }
        super.onDestroy()
    }

    /**
     * Pide otra vez las barras del sistema (y vuelve a ocultar la de arriba si así se eligió). Si la
     * ventana quedó más chica que la pantalla (una raya negra), obliga a Android a volver a darle tamaño;
     * si cuadra, no vuelve a medir todo.
     */
    private fun refit() {
        if (isDestroyed) return
        if (dependencies.prefs.hideStatusBar) applyStatusBar(window, true)
        val view = window.decorView
        ViewCompat.requestApplyInsets(view)
        val screen = screenSize(this)
        val short = view.width > 0 && !isInSplit() && (view.width != screen.x || view.height != screen.y)
        if (short) {
            window.attributes = window.attributes
            view.requestLayout()
        }
        // Después del layout: así el reporte trae la medida nueva.
        view.post { windowInfo = describeWindow(view, screenSize(this)) }
    }

    // En pantalla dividida la ventana es más chica a propósito.
    private fun isInSplit() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode

    private fun isSystemNight() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // Idioma elegido en Ajustes (o el del sistema si no eligió).
    override fun attachBaseContext(base: Context) = super.attachBaseContext(contextWithLanguage(base))

    // Con un idioma bajado, sus recursos no los pone al día Android ([TranslatedResources.sync]).
    override fun onConfigurationChanged(newConfig: Configuration) {
        syncTranslated(resources, newConfig)
        super.onConfigurationChanged(newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePressed.intValue++
        if (intent.action == Intent.ACTION_VIEW) PlaceRequests.fromGeo(intent.data)
    }

    // Mandos al volante: llegan como teclas normales de Android mientras LiteDash está al frente.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        dependencies.wheel.onKey(event, dependencies.music) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        dependencies.wheel.onKey(event, dependencies.music) || super.onKeyUp(keyCode, event)

    // Ventanas de LiteDash a la vista (puede haber dos: una como inicio y otra como app). El enlace con el celular
    // busca seguido solo si alguna se ve; avisarle no lo crea (sin celular vinculado no existe hasta que se pide).
    override fun onStart() {
        super.onStart()
        if (visibleWindows++ == 0) (application as LiteDashApp).phoneIfCreated()?.visible(true)
    }

    override fun onStop() {
        super.onStop()
        if (--visibleWindows == 0) (application as LiteDashApp).phoneIfCreated()?.visible(false)
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        dependencies.music.cancelReturn()
        // Al abrir LiteDash se busca al celular vinculado. Sin vínculo el enlace ni se arma (Ajustes → Celular lo arma
        // para el QR); si ya existe, se le avisa igual.
        if (PhoneLink.isPaired(this)) dependencies.phone.start() else (application as LiteDashApp).phoneIfCreated()?.start()
        revision.intValue++
        refit()
    }

    // Algunos teléfonos (Samsung) regresan la barra al cerrar un aviso o un diálogo, sin pasar por onResume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refit()
    }

    override fun onPause() {
        super.onPause()
        inFront = false
    }

    companion object {
        /** Cuántas [MainActivity] se ven ahora (onStart/onStop; solo en el hilo principal). */
        private var visibleWindows = 0

        /** Alguna ventana de LiteDash se ve. */
        val anyVisible get() = visibleWindows > 0

        /** LiteDash está al frente (para no traerlo de nuevo si ya lo está). */
        @Volatile var inFront = false
            private set

        /** El tamaño de la ventana y lo que ocupan las barras del sistema, la última vez que se midió (para los reportes). */
        @Volatile var windowInfo = "Ventana: ?"
            private set

        /** Después de crear la ventana: la barra del sistema de algunas pantallas aparece varios segundos tarde. */
        private val REFIT_MS = longArrayOf(1_500L, 5_000L, 12_000L, 30_000L)

        /**
         * Abre el inicio otra vez desde cero (al cambiar de idioma). No con `recreate()`: Android
         * reusa la ventana vieja y en Android 15 quedaba sin borde a borde (una franja abajo y,
         * con otro alto, otro acomodo del inicio).
         */
        fun restart(activity: Activity) {
            restarted = true
            reopenLanguage = true
            // Como inicio si así se abrió (si no, Android hacía otro al tocar Inicio) y en una tarea limpia:
            // con otro LiteDash abierto como app, Android le entregaba la orden a ese y no cambiaba el idioma.
            val intent = Intent(Intent.ACTION_MAIN).setClass(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (activity.intent?.hasCategory(Intent.CATEGORY_HOME) == true) intent.addCategory(Intent.CATEGORY_HOME)
            // Primero abrir y luego cerrar: Android solo deja la nueva como inicio si la abre un inicio vivo.
            activity.startActivity(intent)
            activity.finish()
            // Sin la animación de cierre de la vieja: el cambio se ve como un solo cuadro.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
            }
        }

        // La actividad nueva viene de [restart] (no de abrir LiteDash): sin animación de entrada.
        @Volatile private var restarted = false

        /** ¿Se reinició por [restart]? Lo dice una sola vez. */
        fun takeRestarted(): Boolean = restarted.also { restarted = false }

        // Tras [restart] (cambiar idioma) Ajustes se vuelve a abrir donde estaba el idioma, no en el inicio.
        @Volatile private var reopenLanguage = false

        /** ¿Hay que volver a Ajustes → Inicio y apariencia → Idioma y unidades? Lo dice una sola vez. */
        fun takeReopenLanguage(): Boolean = reopenLanguage.also { reopenLanguage = false }

        // La tarea de la actividad viva (-1 = ninguna), para [bringToFront].
        @Volatile private var liveTask = -1
        /** Qué ventana puso [liveTask] (su identityHashCode, sin guardar la actividad; 0 = ninguna). */
        @Volatile private var liveOwner = 0

        /** Trae el inicio al frente sin tocar lo abierto (no es el botón de inicio: no cierra paneles). */
        fun bringToFront(context: Context) {
            if (inFront) return
            // Primero se mueve la tarea viva (REORDER_TASKS): en algunas pantallas el startActivity solo no la traía al frente.
            val task = liveTask
            if (task != -1) runCatching { context.getSystemService(ActivityManager::class.java)?.moveTaskToFront(task, 0) }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { context.startActivity(intent) }
        }
    }
}

/** El tema (claro, oscuro o por la noche), el brillo y la escala de LiteDash alrededor de [content]. */
@Composable
internal fun ComponentActivity.LiteDashContent(d: Dependencies, revision: Int, content: @Composable () -> Unit) {
    // Una sola cuenta de la noche (sol o sensor de luz) para el tema y el brillo.
    val night = rememberIsNight(d.prefs, needed = d.prefs.theme == ThemeMode.SUN || d.prefs.brightnessBySun)
    val dark = isDark(d, night)
    // Íconos de la barra del sistema claros u oscuros según el tema (el borde a borde ya se puso al crear).
    LaunchedEffect(dark) { edgeToEdge(dark) }
    // La barra de Android de arriba, oculta si se pidió: deslizando desde arriba se asoma un momento.
    // También al volver a LiteDash ([revision]): otra app o el apagado de pantalla la regresan.
    val hideStatusBar = d.prefs.hideStatusBar
    LaunchedEffect(hideStatusBar, revision) { applyStatusBar(window, hideStatusBar) }
    ApplyBrightness(d.prefs, night, revision)
    // La piel de las tarjetas (lisas o de vidrio) vale para toda la app; el inicio le suma tu fondo desenfocado.
    val look = remember(d.prefs.cardStyle, d.prefs.cardOpacity) { CardLook(d.prefs.cardStyle, d.prefs.cardOpacity) }
    LiteDashTheme(dark = dark, scale = d.prefs.scale, accent = d.prefs.accentColor, cards = d.prefs.cardColor) {
        CompositionLocalProvider(LocalCardLook provides look) { AlertHost(content) }
    }
}

/** ¿Tema oscuro? Según lo elegido en Ajustes; [night] es la cuenta del sol o del sensor de luz. */
@Composable
private fun isDark(d: Dependencies, night: Boolean): Boolean = when (d.prefs.theme) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.SUN -> night
}

/** Borde a borde con barras transparentes y sus íconos claros u oscuros. */
private fun ComponentActivity.edgeToEdge(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
    enableEdgeToEdge(style, style)
}

/** Lo más que puede medir la ventana: la pantalla entera, con las barras del sistema (la ventana de ahora podría ser la chica). */
private fun screenSize(activity: Activity): Point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    activity.windowManager.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
} else {
    @Suppress("DEPRECATION") Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }
}

/** "Ventana: 1024×552 de 1024×600 · barras arriba 0, abajo 48, izq. 0, der. 0 · teclado 0" (lo que se resta al dibujar). */
private fun describeWindow(view: android.view.View, screen: Point): String {
    val size = "Ventana: ${view.width}×${view.height} de ${screen.x}×${screen.y}"
    val insets = ViewCompat.getRootWindowInsets(view) ?: return "$size · barras ?"
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
    return "$size · barras arriba ${bars.top}, abajo ${bars.bottom}, izq. ${bars.left}, der. ${bars.right} · teclado $ime"
}

/** Oculta o muestra la barra de Android de arriba (deslizando desde arriba se asoma un momento). */
private fun applyStatusBar(window: android.view.Window, hide: Boolean) {
    val bars = WindowCompat.getInsetsController(window, window.decorView)
    bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (hide) bars.hide(WindowInsetsCompat.Type.statusBars()) else bars.show(WindowInsetsCompat.Type.statusBars())
}
