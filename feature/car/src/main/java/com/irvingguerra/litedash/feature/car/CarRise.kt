package com.irvingguerra.litedash.feature.car

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Cuadros de la subida que se decodifican a la vez. */
private const val PARALLEL = 3

/**
 * El auto en grande camino a sus llantas: gira solo a [turn] y luego la cámara sube por detrás
 * hasta quedar arriba ([progress]). Quien la crea mueve los valores; [CarTurntable] los dibuja.
 * Los cuadros viven aquí solo mientras [wanted] (luego [release] los suelta).
 */
@Stable
class CarRise {
    /** Vuelta a la que gira solo el auto (null = la que dejó el usuario). */
    var turn by mutableStateOf<Float?>(null)

    /** Preparar los cuadros de la subida (si el paquete la trae) al tamaño y color del auto. */
    var wanted by mutableStateOf(false)

    /** 0 = el giro normal; de 0 a 1 sube la cámara. Se lee solo al dibujar. */
    var progress: () -> Float = { 0f }

    /** De 0 a 1 el auto viaja a [target] (para encimarse con la vista de las llantas). Se lee al dibujar. */
    var morph: () -> Float = { 0f }

    /** Dónde está el auto de la vista de las llantas (en la raíz); vacío = no se mueve. */
    var target by mutableStateOf(Rect.Zero)

    /** Cuadros en total (0 = el auto no trae subida o aún no se piden). */
    var count by mutableIntStateOf(0)
        private set

    /** Cuántos ya están listos; estado: el dibujo se repite al llegar cada uno. */
    var loaded by mutableIntStateOf(0)
        private set

    internal var frames: Array<ImageBitmap?> = emptyArray()
        private set

    /** Dónde queda el auto en el último cuadro, en fracciones de la imagen. */
    internal var topBox: Rect? = null
        private set

    /** Esquina del lienzo del auto en la raíz (para llevarlo a [target]); no es estado. */
    internal var canvasOrigin = Offset.Zero

    /** Ya están todos los cuadros. */
    val complete get() = count > 0 && loaded >= count

    /** Se puede empezar a subir: al menos el primer cuadro está listo. */
    fun canPlay() = loaded > 0 && frames.getOrNull(0) != null

    /** Suelta los cuadros y regresa todo a como estaba. */
    fun release() {
        wanted = false
        turn = null
        target = Rect.Zero
        frames = emptyArray()
        topBox = null
        count = 0
        loaded = 0
    }

    /**
     * Carga los cuadros de [p]: primero el primero y el último (con los que empieza y termina),
     * luego los de en medio, unos cuantos a la vez. Si [release] llega a la mitad, lo que ya
     * se cargó se tira.
     */
    internal suspend fun load(repo: CarRepository, p: CarPackage, info: RiseInfo, color: Color, screenWidthPx: Int) {
        // Pantallas con poca memoria (Android 6–7 con 1 GB: los bitmaps van al heap de Java):
        // 16 cuadros a todo lo ancho serían ~32 MB a la vez; a 640 px, ~13 MB y se ve igual de lejos.
        val widthPx = if (Runtime.getRuntime().maxMemory() <= LOW_HEAP) minOf(screenWidthPx, LOW_HEAP_WIDTH) else screenWidthPx
        val n = info.frames
        val own = arrayOfNulls<ImageBitmap>(n)
        frames = own
        topBox = null
        loaded = 0
        count = n
        val order = listOf(0, n - 1) + (1 until n - 1)
        val gate = Semaphore(PARALLEL)
        coroutineScope {
            order.map { i ->
                async {
                    val img = gate.withPermit { repo.riseFrame(p, i, color, widthPx) } ?: return@async
                    if (i == n - 1) {
                        val box = withContext(Dispatchers.Default) { solidBox(img.asAndroidBitmap()) }
                        if (frames === own) topBox = box
                    }
                    if (frames === own) {
                        own[i] = img
                        loaded++
                    }
                }
            }.awaitAll()
        }
    }
}

/**
 * La caja de lo sólido de la imagen (alfa > 200): el auto sin su sombra suave. En fracciones
 * del ancho y alto; null si no hay nada.
 */
private fun solidBox(b: Bitmap): Rect? {
    val w = b.width
    val h = b.height
    val row = IntArray(w)
    var left = w
    var right = -1
    var top = -1
    var bottom = -1
    for (y in 0 until h) {
        b.getPixels(row, 0, w, 0, y, w, 1)
        var any = false
        for (x in 0 until w) {
            if (row[x] ushr 24 > 200) {
                any = true
                if (x < left) left = x
                if (x > right) right = x
            }
        }
        if (any) {
            if (top < 0) top = y
            bottom = y
        }
    }
    if (top < 0) return null
    return Rect(left.toFloat() / w, top.toFloat() / h, (right + 1).toFloat() / w, (bottom + 1).toFloat() / h)
}

/** Heap a partir del cual los cuadros de la subida se cargan reducidos ([LOW_HEAP_WIDTH]). */
private const val LOW_HEAP = 192L * 1024 * 1024
private const val LOW_HEAP_WIDTH = 640
