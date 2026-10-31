package com.irvingguerra.litedash.feature.navegacion

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Lo que dice el aviso de la app de navegación: la siguiente vuelta y la llegada. */
@Immutable
data class Indicacion(
    val clave: String,
    val paquete: String,
    /** Normalmente la distancia a la vuelta ("300 m"). */
    val titulo: String,
    /** Normalmente la maniobra ("Gira a la derecha hacia Reforma"). */
    val texto: String,
    /** Llegada, tiempo o distancia restante, si la app la pone. */
    val detalle: String,
    /** La flecha de la maniobra que dibuja la app. */
    val flecha: Bitmap?,
)

/**
 * Lee la siguiente vuelta del aviso que Waze o Google Maps muestran mientras
 * navegan, para tenerla en el tablero sin abrir el mapa. Solo mira avisos de
 * [paquetes] y solo mientras algo la usa ([usar]/[soltar]); no guarda nada.
 */
class RepositorioNavegacion(private val contexto: Context, private val paquetes: Set<String>) {
    var indicacion by mutableStateOf<Indicacion?>(null)
        private set

    private var usuarios = 0

    /** [activos] son los avisos que ya están en pantalla al empezar a escuchar. */
    fun usar(activos: () -> List<StatusBarNotification>) {
        usuarios++
        if (usuarios == 1) activos().forEach(::publicado)
    }

    fun soltar() {
        usuarios = (usuarios - 1).coerceAtLeast(0)
        if (usuarios == 0) indicacion = null
    }

    fun publicado(aviso: StatusBarNotification) {
        if (usuarios == 0 || aviso.packageName !in paquetes) return
        val n = aviso.notification
        // Waze y Maps navegan con un aviso fijo (o de categoría navegación); los demás avisos no cuentan.
        val fijo = n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.category == Notification.CATEGORY_NAVIGATION
        if (!fijo && aviso.packageName != PAQUETE_PRUEBAS) return
        val e = n.extras
        val titulo = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val texto = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (titulo.isBlank() && texto.isBlank()) return
        indicacion = Indicacion(
            clave = aviso.key,
            paquete = aviso.packageName,
            titulo = titulo,
            texto = texto,
            detalle = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            flecha = flecha(n),
        )
    }

    fun quitado(aviso: StatusBarNotification) {
        if (indicacion?.clave == aviso.key) indicacion = null
    }

    private fun flecha(n: Notification): Bitmap? = runCatching {
        val d = n.getLargeIcon()?.loadDrawable(contexto) ?: return null
        if (d is BitmapDrawable) return d.bitmap
        val lado = (48 * contexto.resources.displayMetrics.density).toInt()
        Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, lado, lado)
            d.draw(Canvas(it))
        }
    }.getOrNull()

    companion object {
        /** Los avisos de `adb shell cmd notification post` (para probar sin manejar). */
        const val PAQUETE_PRUEBAS = "com.android.shell"
    }
}
