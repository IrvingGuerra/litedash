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
import kotlinx.coroutines.withContext

/**
 * El auto en grande camino a sus llantas: se funde a la vista de espaldas ([fade]) y la cámara
 * sube por detrás hasta quedar arriba ([progress]). Todo se prepara ANTES de tocar ([preload],
 * mientras se ve el inicio): decodificar y teñir 16 cuadros al tocar saturaba el procesador justo
 * durante la animación (se trababa y subía con cuadros faltantes). Quien la crea mueve los
 * valores; [CarTurntable] los dibuja.
 */
@Stable
class CarRise {
    /** El auto va camino a sus llantas (no se gira con el dedo ni se guarda su vuelta). */
    var active by mutableStateOf(false)

    /** De 0 a 1 el auto se funde a la vista de espaldas ([rear]). Se lee solo al dibujar. */
    var fade: () -> Float = { 0f }

    /** 0 = la vista de espaldas; de 0 a 1 sube la cámara. Se lee solo al dibujar. */
    var progress: () -> Float = { 0f }

    /** De 0 a 1 el auto viaja a [target] (para encimarse con la vista de las llantas). Se lee al dibujar. */
    var morph: () -> Float = { 0f }

    /** Dónde está el auto de la vista de las llantas (en la raíz); vacío = no se mueve. */
    var target by mutableStateOf(Rect.Zero)

    /** El auto de espaldas en grande, ya pintado (de ahí arranca la subida). */
    internal var rear by mutableStateOf<CarLayers?>(null)
        private set

    /** Cuadros en total (0 = el auto no trae subida o aún no se cargan). */
    var count by mutableIntStateOf(0)
        private set

    /** Cuántos ya están listos. */
    var loaded by mutableIntStateOf(0)
        private set

    internal var frames: Array<ImageBitmap?> = emptyArray()
        private set

    /** Dónde queda el auto en el último cuadro, en fracciones de la imagen. */
    internal var topBox: Rect? = null
        private set

    /** Esquina del lienzo del auto en la raíz (para llevarlo a [target]); no es estado. */
    internal var canvasOrigin = Offset.Zero

    /** Lo preparado: auto, color y ancho (si no cambia, [preload] no repite nada). */
    private var key: Any? = null

    /** Ya está la vista de espaldas y todos los cuadros (o el auto no trae subida). */
    val ready get() = rear != null && loaded >= count

    /** Hay subida que reproducir. */
    val hasFrames get() = count > 0 && loaded >= count

    /** Termina el viaje (los cuadros se quedan para la siguiente vez). */
    fun finish() {
        active = false
        target = Rect.Zero
    }

    /** Suelta todo lo preparado (al salir del inicio, cambiar de auto o apagar Llantas). */
    fun clear() {
        finish()
        key = null
        rear = null
        frames = emptyArray()
        topBox = null
        count = 0
        loaded = 0
    }

    /**
     * Prepara la vista de espaldas ([rearName]) y los cuadros de la subida de [p] al ancho y color
     * con que se verá el auto en grande. Si ya está lo mismo, no hace nada. Se llama en segundo
     * plano mientras se ve el inicio.
     */
    suspend fun preload(repo: CarRepository, p: CarPackage, rearName: String, color: Color, screenWidthPx: Int) {
        // Pantallas con poca memoria (Android 6–7 con 1 GB: los bitmaps van al heap de Java):
        // 16 cuadros a todo lo ancho serían ~32 MB; a 640 px, ~13 MB y se ve igual de lejos.
        val widthPx = if (Runtime.getRuntime().maxMemory() <= LOW_HEAP) minOf(screenWidthPx, LOW_HEAP_WIDTH) else screenWidthPx
        val want = Triple(p.id + "@" + p.sha256 + "@" + (p.rise != null), color, widthPx)
        if (key == want && ready) return
        key = want
        rear = repo.layers(p, rearName, color, screenWidthPx)
        val info = p.rise
        if (info == null) {
            frames = emptyArray()
            count = 0
            loaded = 0
            return
        }
        val n = info.frames
        val own = arrayOfNulls<ImageBitmap>(n)
        frames = own
        topBox = null
        loaded = 0
        count = n
        // De uno en uno: no hay prisa (falta para que lo toquen) y no se le roba procesador al inicio.
        for (i in 0 until n) {
            val img = repo.riseFrame(p, i, color, widthPx) ?: continue
            if (frames !== own) return
            if (i == n - 1) topBox = withContext(Dispatchers.Default) { solidBox(img.asAndroidBitmap()) }
            own[i] = img
            loaded++
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
