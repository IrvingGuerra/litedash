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
import kotlin.math.sqrt

/**
 * El auto en grande camino a sus llantas: gira hasta quedar de espaldas ([spin]) y la cámara
 * sube por detrás hasta quedar arriba ([progress]). Todo se prepara ANTES de tocar ([preload],
 * mientras se ve el inicio): decodificar y teñir 16 cuadros al tocar saturaba el procesador justo
 * durante la animación (se trababa y subía con cuadros faltantes). Quien la crea mueve los
 * valores; [CarTurntable] los dibuja.
 */
@Stable
class CarRise {
    /** El auto va camino a sus llantas (no se gira con el dedo ni se guarda su vuelta). */
    var active by mutableStateOf(false)

    /** De 0 a 1 el auto gira (en grande) de donde lo dejó el usuario a la vista de espaldas. Se lee al dibujar. */
    var spin: () -> Float = { 0f }

    /** 0 = la vista de espaldas; de 0 a 1 sube la cámara. Se lee solo al dibujar. */
    var progress: () -> Float = { 0f }

    /** De 0 a 1 el auto viaja a [target] (para encimarse con la vista de las llantas). Se lee al dibujar. */
    var morph: () -> Float = { 0f }

    /** Dónde está el auto de la vista de las llantas (en la raíz); vacío = no se mueve. */
    var target by mutableStateOf(Rect.Zero)

    /** Los cuadros del giro en grande, ya pintados: de donde lo dejó el usuario hasta quedar de espaldas. */
    internal var turnFrames by mutableStateOf<List<CarLayers>>(emptyList())
        private set

    /** Dónde queda el auto (lo sólido) en el último cuadro del giro, en fracciones de su imagen. */
    internal var turnEnd: Rect? = null
        private set

    /** Cuántos cuadros gira (para que cada uno dure lo mismo). */
    val turnSteps get() = turnFrames.size

    /** Cuadros en total (0 = el auto no trae subida o aún no se cargan). */
    var count by mutableIntStateOf(0)
        private set

    /** Cuántos ya están listos. */
    var loaded by mutableIntStateOf(0)
        private set

    internal var frames: Array<ImageBitmap?> = emptyArray()
        private set

    /** Dónde queda el auto en cada cuadro, en fracciones de la imagen (para fundir uno con otro sin que se vean dos). */
    internal var boxes: Array<Rect?> = emptyArray()
        private set

    /** Dónde queda el auto en el último cuadro, en fracciones de la imagen. */
    internal val topBox: Rect? get() = boxes.lastOrNull()

    /** Esquina del lienzo del auto en la raíz (para llevarlo a [target]); no es estado. */
    internal var canvasOrigin = Offset.Zero

    /** Lo preparado del giro y de la subida (si no cambia, [preload] no repite nada). */
    private var turnKey: Any? = null
    private var riseKey: Any? = null

    /** Sube con cada [clear]: una preparación que iba a la mitad ya no guarda nada. */
    private var generation = 0

    /** Ya está el giro y todos los cuadros (o el auto no trae subida). */
    val ready get() = turnReady && loaded >= count

    private var turnReady by mutableStateOf(false)

    /** Hay subida que reproducir. */
    val hasFrames get() = count > 0 && loaded >= count

    /** Termina el viaje (los cuadros se quedan para la siguiente vez). */
    fun finish() {
        active = false
        target = Rect.Zero
    }

    /** Suelta todo lo preparado (el inicio dejó de verse, falta memoria, cambió el auto o se apagó Llantas). */
    fun clear() {
        finish()
        generation++
        releaseTurn()
        releaseRise()
    }

    private fun releaseTurn() {
        turnKey = null
        turnFrames = emptyList()
        turnEnd = null
        turnReady = false
    }

    private fun releaseRise() {
        riseKey = null
        frames = emptyArray()
        boxes = emptyArray()
        count = 0
        loaded = 0
    }

    /**
     * Prepara el giro ([turnPath]: los cuadros de donde lo dejó el usuario hasta quedar de espaldas)
     * y los cuadros de la subida de [p], del [color] y para el auto en grande ([canvasWidthPx]: el
     * ancho de su lienzo). Fuera de la caché del giro y a un ancho que cabe en memoria ([widths]).
     * Cada parte con su llave: cambiar la vuelta del auto solo rehace el giro. Si ya está lo mismo,
     * no hace nada. Se llama en segundo plano mientras se ve el inicio.
     */
    suspend fun preload(repo: CarRepository, p: CarPackage, turnPath: List<String>, color: Color, canvasWidthPx: Int) {
        if (canvasWidthPx <= 0) return
        val gen = generation
        val (turnWidth, riseWidth) = widths(p, turnPath, canvasWidthPx, repo.lowMemory)
        val car = p.id + "@" + p.sha256
        val turnWant = listOf(car, turnPath, color, turnWidth)
        if (turnKey != turnWant || !turnReady) {
            // Lo viejo se suelta antes de armar lo nuevo (no conviven los dos en memoria).
            releaseTurn()
            // Uno por uno; si falta uno, no hay giro (nunca a medias).
            val turn = ArrayList<CarLayers>(turnPath.size)
            for (name in turnPath) {
                val layers = repo.turnFrame(p, name, color, turnWidth)
                if (gen != generation) return
                if (layers == null) {
                    turn.clear()
                    break
                }
                turn += layers
            }
            turnEnd = turn.lastOrNull()?.let { withContext(Dispatchers.Default) { solidBox(it.car.asAndroidBitmap()) } }
            if (gen != generation) return
            turnFrames = turn
            turnReady = true
            turnKey = turnWant
        }
        val info = p.rise
        val riseWant = listOf(car, info, color, riseWidth)
        if (riseKey == riseWant && loaded >= count) return
        releaseRise()
        if (info == null) {
            riseKey = riseWant
            return
        }
        val n = info.frames
        val own = arrayOfNulls<ImageBitmap>(n)
        val ownBoxes = arrayOfNulls<Rect>(n)
        frames = own
        boxes = ownBoxes
        count = n
        // De uno en uno: no hay prisa (falta para que lo toquen) y no se le roba procesador al inicio.
        for (i in 0 until n) {
            val img = repo.riseFrame(p, i, color, riseWidth)
            if (gen != generation || frames !== own) return
            // Si falta uno, no hay subida (nunca a medias).
            if (img == null) return releaseRise().also { riseKey = riseWant }
            ownBoxes[i] = withContext(Dispatchers.Default) { solidBox(img.asAndroidBitmap()) }
            own[i] = img
            loaded++
        }
        riseKey = riseWant
    }
}

/**
 * Los anchos del giro y de la subida para un lienzo de [canvas] px: lo más cerca del lienzo
 * que quepa en [TURN_BUDGET] (o [LOW_TURN_BUDGET] con poca memoria). La subida (la cámara
 * subiendo, en movimiento) va a un ancho fijo más chico; el giro se lleva el resto. Cada cuadro
 * del giro son el auto (proporción [CAR_ASPECT]) y su sombra y reflejo a la mitad (caja del piso).
 */
private fun widths(p: CarPackage, turnPath: List<String>, canvas: Int, low: Boolean): Pair<Int, Int> {
    val budget = if (low) LOW_TURN_BUDGET else TURN_BUDGET
    val rise = p.rise
    val riseWidth = minOf(canvas, if (low) LOW_RISE_WIDTH else RISE_WIDTH)
    val riseBytes = if (rise == null) 0f else rise.frames * riseWidth * (riseWidth / rise.aspect) * 4
    if (turnPath.isEmpty()) return canvas to riseWidth
    val floor = p.floor[turnPath.first()]
    // Bytes de un cuadro del giro por px² de ancho.
    val perPx = 4 * (1 / CAR_ASPECT + if (floor == null) 0f else 2 * (floor.width / 2) * (floor.height / 2))
    val fit = sqrt((budget - riseBytes).coerceAtLeast(0f) / turnPath.size / perPx).toInt() / 32 * 32
    return fit.coerceIn(MIN_TURN_WIDTH, maxOf(canvas, MIN_TURN_WIDTH)) to riseWidth
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

/**
 * Lo que puede ocupar lo preparado (giro + subida), en bytes: unos 26 MB (se queda en memoria
 * mientras se ve el inicio); con poca memoria (Android 6 con 1 GB), 15 MB.
 */
private const val TURN_BUDGET = 26f * 1024 * 1024
private const val LOW_TURN_BUDGET = 15f * 1024 * 1024

/**
 * Ancho de los cuadros de la subida: la cámara va en movimiento y no se nota que van reducidos
 * (a 448 ya se notaba al empezar a subir, con el auto de espaldas quieto).
 */
private const val RISE_WIDTH = 640
private const val LOW_RISE_WIDTH = 384

/** Nunca más chico que esto (se vería borroso). */
private const val MIN_TURN_WIDTH = 320
