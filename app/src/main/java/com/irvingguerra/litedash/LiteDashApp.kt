package com.irvingguerra.litedash

import android.app.Application
import android.service.notification.StatusBarNotification
import com.irvingguerra.litedash.feature.actualizar.Actualizador
import com.irvingguerra.litedash.feature.apps.RepositorioApps
import com.irvingguerra.litedash.feature.auto.RepositorioAutos
import com.irvingguerra.litedash.feature.musica.RepositorioMusica
import com.irvingguerra.litedash.feature.musica.ServicioMusica
import com.irvingguerra.litedash.feature.navegacion.RepositorioNavegacion
import com.irvingguerra.litedash.feature.tablero.AlmacenTablero
import com.irvingguerra.litedash.feature.tablero.EstadoTablero

/**
 * Las dependencias viven aquí (no en la actividad) porque el servicio de avisos
 * también las usa, aun sin la pantalla abierta. Todo es perezoso.
 */
class LiteDashApp : Application() {
    val dependencias: Dependencias by lazy {
        val paquetesMapa = PAQUETES_NAVEGACION.toSet() +
            if (BuildConfig.DEBUG) setOf(RepositorioNavegacion.PAQUETE_PRUEBAS) else emptySet()
        Dependencias(
            apps = RepositorioApps(this),
            musica = RepositorioMusica(this),
            navegacion = RepositorioNavegacion(this, paquetesMapa),
            prefs = Preferencias(this),
            tablero = EstadoTablero(AlmacenTablero(this)),
            autos = RepositorioAutos(this, BuildConfig.URL_CATALOGO),
            actualizador = Actualizador(this, BuildConfig.URL_ACTUALIZACIONES, BuildConfig.VERSION_CODE.toLong()),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Los avisos llegan aquí solo si el usuario dio acceso a notificaciones (el mismo de la música).
        ServicioMusica.oyente = object : ServicioMusica.Oyente {
            override fun alPublicar(aviso: StatusBarNotification) = dependencias.navegacion.publicado(aviso)
            override fun alQuitar(aviso: StatusBarNotification) = dependencias.navegacion.quitado(aviso)
        }
    }
}
