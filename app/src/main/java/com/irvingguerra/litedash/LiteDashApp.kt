package com.irvingguerra.litedash

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.service.notification.StatusBarNotification
import kotlin.concurrent.thread
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.tpms.CarTires
import com.irvingguerra.litedash.feature.tpms.TpmsRepository
import com.irvingguerra.litedash.feature.tpms.UsbAttachActivity
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.PhoneSound
import com.irvingguerra.litedash.feature.link.ScreenInfo
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.NotificationListener
import com.irvingguerra.litedash.feature.music.NotificationService
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.dashboard.DashboardStore
import com.irvingguerra.litedash.feature.dashboard.DashboardState

/** Más rápido que esto (GPS u OBD), el auto se mueve. */
private const val MOVING_KMH = 10f

/**
 * Las dependencias viven aquí (no en la actividad) porque también las usan la pantalla
 * dividida y el servicio de avisos (este solo si ya existen). Todo es perezoso.
 */
class LiteDashApp : Application() {
    /** El enlace con el celular: aparte para saber si ya existe sin crearlo ([phoneIfCreated]). */
    private val phoneLazy = lazy {
        val d = dependencies
        val carName = { d.prefs.carName ?: d.cars.pkg(d.prefs.carModel)?.name ?: getString(R.string.my_car) }
        PhoneLink(this) {
            ScreenInfo(BuildConfig.VERSION_NAME, carName(), d.prefs.carModel ?: "", Build.MODEL)
        }.also { link ->
            wirePhoneMusic(link, d.music) { sound ->
                getString(if (sound == PhoneSound.CALLS) R.string.phone_calls_only else R.string.phone_no_sound)
            }
            val edit = PhoneCarEdit(link, d.prefs, d.cars) { getString(it) }
            wirePhoneCar(link, d.prefs, d.cars, edit, carName)
            wirePhoneState(link) { dependencies }
            wirePhoneReport(link, this) { dependencies }
            // Si ya hay una ventana a la vista (lo normal: lo crea onResume), el enlace lo sabe desde el principio.
            if (MainActivity.anyVisible) link.visible(true)
        }
    }

    /** El enlace si ya existe, sin crearlo (avisar que una ventana se ve o se ocultó no debe crearlo). */
    fun phoneIfCreated(): PhoneLink? = if (phoneLazy.isInitialized()) phoneLazy.value else null

    // Se arma la primera vez que algo de la pantalla lo pide (no por el servicio de avisos).
    private val dependenciesLazy = lazy {
        val mapPackages = NAVIGATION_PACKAGES.toSet() +
            if (BuildConfig.DEBUG) setOf(NavigationRepository.TEST_PACKAGE) else emptySet()
        val prefs = Preferences(this)
        // Las conocidas y la elegida en Ajustes aunque no sea de ellas (al cambiarla se nota sin reiniciar).
        val listensTo = { pkg: String -> pkg in mapPackages || pkg == prefs.mapApp?.substringBefore('/') }
        val bus = VehicleBus()
        val music = MusicRepository(this).apply { opener = SplitScreen::open
            returnHome = { MainActivity.bringToFront(this@LiteDashApp) } }
        OwnPlayer.wire(this, music)
        // Migraciones de la música (lista en MusicRepository.upgrade): se quedan mientras haya pantallas en versiones viejas.
        val oldInternetRadio = prefs.internetRadio.takeUnless { prefs.radioUnified }
        music.upgrade(prefs.musicApp, oldInternetRadio)
        if (oldInternetRadio != null) prefs.radioUnified = true
        music.isProjection = ::isProjectionPackage
        prefs.addBluetoothToBar()
        // Abrir una app de fábrica encima (una receta de sonido) espera a que el auto vaya a 10 km/h o menos. Sin dato
        // de velocidad (ningún widget usa el GPS ni hay OBD) no se sabe: se toma como en movimiento.
        music.moving = { (bus.value(Signal.SPEED) ?: Float.MAX_VALUE) > MOVING_KMH }
        val cars = CarRepository(this, BuildConfig.CATALOG_URL)
        Dependencies(
            apps = AppRepository(this).apply { opener = SplitScreen::open },
            music = music,
            navigation = NavigationRepository(this, listensTo),
            prefs = prefs,
            dashboard = DashboardState(DashboardStore(this)),
            cars = cars,
            updates = appUpdates(this),
            weather = WeatherRepository(this, prefs),
            trip = TripRepository(this, bus),
            reminders = ReminderRepository(this),
            wheel = WheelKeys(this, prefs),
            stats = Stats(this) { prefs.stats },
            pro = ProAccess(this),
            bus = bus,
            obdLazy = lazy { ObdRepository(this, bus) },
            obdSaved = { ObdRepository.hasSavedTarget(this) },
            tpmsLazy = lazy {
                TpmsRepository(this, bus).apply {
                    carTires = object : CarTires {
                        override suspend fun read() = factoryTires(dependencies)
                        override fun stop() = stopFactoryTires()
                    }
                }
            },
            tpmsSaved = { TpmsRepository.hasSavedSource(this) },
            phoneLazy = phoneLazy,
        )
    }
    val dependencies: Dependencies by dependenciesLazy

    override fun onCreate() {
        super.onCreate()
        // Los autos instalados se leen en otro hilo mientras Android arma la pantalla.
        CarRepository.prefetch(this)
        // Los avisos llegan aquí solo si el usuario dio acceso a notificaciones (el mismo de la música).
        // Android conecta ese servicio solo (aun tras forzar el cierre): sin la app armada, nada que hacer.
        NotificationService.listener = object : NotificationListener {
            override val wanted get() = dependenciesLazy.isInitialized()
            override fun onPosted(notification: StatusBarNotification) = dependencies.navigation.posted(notification)
            override fun onRemoved(notification: StatusBarNotification) = dependencies.navigation.removed(notification)
        }
        watchUsbOffer()
        // Un idioma bajado se pone al día solo (la lista se revisa a lo más una vez al día); lo nuevo sale al siguiente arranque.
        val language = Language.read(this)
        if (language.isNotBlank() && language !in BUILT_IN_LANGUAGES) thread(name = "language") { updateLanguage(this, language) }
    }

    /**
     * Al irse a segundo plano se recortan los cuadros del auto (lo más pesado en memoria): a un cuarto
     * si solo se ocultó la pantalla, todos si Android ya la tiene en la lista de procesos a cerrar; ahí
     * también se sueltan los íconos de las apps (se vuelven a leer al regresar).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || !dependenciesLazy.isInitialized()) return
        val background = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        dependencies.cars.trimMemory(all = background)
        if (background) dependencies.apps.trimMemory()
    }

    // Los textos que se piden a la aplicación (repositorios, avisos, servicios que usan applicationContext) salen
    // en el idioma elegido, no en el del sistema. Se rehace solo cuando cambia el idioma ([Language]) o su paquete bajado.
    private class Localized(val language: String, val generation: Int, val resources: Resources)

    @Volatile private var localized: Localized? = null

    override fun getResources(): Resources {
        val base = baseContext ?: return super.getResources()
        val language = Language.read(base)
        val generation = LanguagePacks.generation
        localized?.let { if (it.language == language && it.generation == generation) return it.resources }
        return contextWithLanguage(base).resources.also { localized = Localized(language, generation, it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        localized?.let { syncTranslated(it.resources, newConfig) }
        super.onConfigurationChanged(newConfig)
    }

    // Referencia fuerte: Android guarda los oyentes de preferencias como referencias débiles.
    // Solo importan la fuente de llantas ("source"), el tipo de adaptador OBD ("type") y el módulo OBD.
    private val onUsbPrefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "source" || key == "type" || key == PrefKeys.OBD_MODULE) syncReceivers()
    }

    /**
     * "Usar LiteDash con este USB" solo mientras alguien lo usa: las llantas por USB o un cable
     * OBD USB guardado con el módulo prendido. Se revisa al arrancar (fuera del hilo principal:
     * lee del disco) y cada vez que cambian esos ajustes.
     */
    private fun watchUsbOffer() {
        for (file in arrayOf(TpmsRepository.PREFS, ObdRepository.PREFS, PrefKeys.FILE)) {
            getSharedPreferences(file, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onUsbPrefs)
        }
        syncReceivers()
    }

    private fun syncReceivers() {
        Thread({ synchronized(onUsbPrefs) {
            // El receptor de "Abrir al encender" igual que su opción (por si se restauró un respaldo).
            BootLaunch.sync(this, BootLaunch.enabled(this))
            val settings = getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE)
            val obdOn = !settings.contains(PrefKeys.OBD_MODULE) || settings.getBoolean(PrefKeys.OBD_MODULE, false)
            UsbAttachActivity.offer(this, TpmsRepository.usesUsb(this) || obdOn && ObdRepository.hasUsbTarget(this))
        } }, "sync-receivers").start()
    }
}
