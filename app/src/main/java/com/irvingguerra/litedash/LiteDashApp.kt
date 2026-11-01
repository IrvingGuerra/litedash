package com.irvingguerra.litedash

import android.app.Application
import android.service.notification.StatusBarNotification
import com.irvingguerra.litedash.feature.updater.Updater
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.NotificationListener
import com.irvingguerra.litedash.feature.music.NotificationService
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.dashboard.DashboardStore
import com.irvingguerra.litedash.feature.dashboard.DashboardState

/**
 * Las dependencias viven aquí (no en la actividad) porque el servicio de avisos
 * también las usa, aun sin la pantalla abierta. Todo es perezoso.
 */
class LiteDashApp : Application() {
    val dependencies: Dependencies by lazy {
        val mapPackages = NAVIGATION_PACKAGES.toSet() +
            if (BuildConfig.DEBUG) setOf(NavigationRepository.TEST_PACKAGE) else emptySet()
        val prefs = Preferences(this)
        Dependencies(
            apps = AppRepository(this),
            music = MusicRepository(this).apply { preferred = prefs.musicApp },
            navigation = NavigationRepository(this, mapPackages),
            prefs = prefs,
            dashboard = DashboardState(DashboardStore(this)),
            cars = CarRepository(this, BuildConfig.CATALOG_URL),
            updater = Updater(this, BuildConfig.UPDATES_URL, BuildConfig.VERSION_CODE.toLong()),
            weather = WeatherRepository(this),
            trip = TripRepository(this),
            reminders = ReminderRepository(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Los avisos llegan aquí solo si el usuario dio acceso a notificaciones (el mismo de la música).
        NotificationService.listener = object : NotificationListener {
            override fun onPosted(notification: StatusBarNotification) = dependencies.navigation.posted(notification)
            override fun onRemoved(notification: StatusBarNotification) = dependencies.navigation.removed(notification)
        }
    }
}
