package com.irvingguerra.litedash.auto

import android.app.Presentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.irvingguerra.litedash.Home
import com.irvingguerra.litedash.LiteDashApp
import com.irvingguerra.litedash.R
import com.irvingguerra.litedash.contextWithLanguage
import com.irvingguerra.litedash.core.ui.LiteDashTheme
import com.irvingguerra.litedash.isDark
import com.irvingguerra.litedash.rememberIsNight

/*
 * Experimento (solo la versión de GitHub): el tablero de LiteDash dentro de Android Auto.
 * Android Auto solo da lienzo libre a las apps de navegación (su "mapa"); ahí se dibuja el
 * inicio de siempre en una pantalla virtual y se le pasan los toques. Aparece en Android Auto
 * solo con "Fuentes desconocidas" prendido en sus ajustes de desarrollador.
 */
class AutoService : CarAppService() {
    // Solo el Android Auto de Google (lista firmada de la biblioteca); en depuración, cualquiera (el simulador).
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = AutoScreen(carContext)
    }
}

private class AutoScreen(car: CarContext) : Screen(car) {
    private val dash = DashSurface(car, lifecycle)

    init {
        car.getCarService(AppManager::class.java).setSurfaceCallback(dash)
    }

    // Android Auto exige al menos un botón suyo: "Inicio" cierra lo que esté abierto.
    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(Action.Builder().setTitle(carContext.getString(R.string.auto_home)).setOnClickListener { dash.home() }.build())
                .build(),
        )
        .build()
}

/** El inicio de LiteDash dibujado en la superficie que da Android Auto. */
private class DashSurface(private val car: CarContext, private val screen: Lifecycle) : SurfaceCallback {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var host: Host? = null
    private val homePressed = mutableIntStateOf(0)
    // Lo que no tapan los botones de Android Auto (en píxeles); null = toda la superficie.
    private val visible = mutableStateOf<Rect?>(null)

    fun home() { homePressed.intValue++ }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        val d = (car.applicationContext as LiteDashApp).dependencies
        val vd = car.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("LiteDash", container.width, container.height, container.dpi, surface, 0)
        val owner = Host(screen)
        val p = Presentation(contextWithLanguage(car), vd.display, R.style.Theme_LiteDash)
        val view = ComposeView(p.context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeOnBackPressedDispatcherOwner(owner)
            setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    val night = rememberIsNight(d.prefs, needed = d.prefs.theme == com.irvingguerra.litedash.ThemeMode.SUN)
                    LiteDashTheme(dark = isDark(d, night), scale = d.prefs.scale, accent = d.prefs.accentColor) {
                        val r = visible.value
                        val pad = if (r == null) Modifier else with(LocalDensity.current) {
                            Modifier.padding(
                                start = r.left.toDp(), top = r.top.toDp(),
                                end = (container.width - r.right).coerceAtLeast(0).toDp(),
                                bottom = (container.height - r.bottom).coerceAtLeast(0).toDp(),
                            )
                        }
                        Box(pad) { Home(d, 0, homePressed.intValue) }
                    }
                }
            }
        }
        p.setContentView(view)
        p.show()
        display = vd
        presentation = p
        host = owner
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) { visible.value = Rect(visibleArea) }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    // Un toque en la pantalla del auto = tocar y soltar en el mismo punto.
    override fun onClick(x: Float, y: Float) {
        val v = presentation?.window?.decorView ?: return
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            v.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun release() {
        presentation?.dismiss()
        display?.release()
        host?.destroy()
        presentation = null
        display = null
        host = null
    }
}

/**
 * Lo que una actividad le da a Compose (ciclo de vida, estado guardado, "atrás", permisos), para
 * dibujar fuera de una actividad. Sigue el ciclo de vida de la sesión de Android Auto. Pedir permisos
 * desde el auto no hace nada: se dan en el celular.
 */
private class Host(session: Lifecycle) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
    OnBackPressedDispatcherOwner, ActivityResultRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val follow = LifecycleEventObserver { _, event -> registry.handleLifecycleEvent(event) }
    private val sessionLifecycle = session
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
    }

    init {
        saved.performAttach()
        saved.performRestore(null)
        session.addObserver(follow)
    }

    fun destroy() {
        sessionLifecycle.removeObserver(follow)
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
