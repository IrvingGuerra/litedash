package com.irvingguerra.litedash.feature.map

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Cuánto se acuesta el mapa inclinado (grados). */
private const val TILT_DEGREES = 50.0

/** Hasta dónde se dibuja (en tamaños de la vista); inclinado es lo mínimo. */
private const val REACH = 1.6f

/** Inclinado, lo más lejos que se dibuja (en veces el lado mayor de la vista): lo lejano ya va con poco detalle. */
private const val MAX_REACH = 4f

/** Inclinado, dónde acaba el mapa (fracción del alto desde arriba): lo cubre el desvanecido hacia el cielo. */
private const val TOP_GAP = 0.08f

/** La cámara de Android a 8 pulgadas (576 px a densidad 1): la perspectiva de siempre en pantallas bajas. */
private const val CAMERA_PX = 576f

/** En vistas altas la cámara se aleja con el alto: el horizonte queda arriba de la vista en cualquier tamaño. */
private const val CAMERA_PER_HEIGHT = 1.25f

/**
 * Del plano del mapa a la pantalla de una vista de [width] × [height] px con tu posición en ([atX], [atY]):
 * gira [turn] grados alrededor de ella y, si va [tilted], la acuesta en perspectiva (la cámara mira hacia
 * adelante, como al navegar). Sin Android, para probarla con JUnit: [values] es la matriz 3×3 en el
 * orden de `android.graphics.Matrix.setValues`.
 */
class MapProjection(
    private val width: Float,
    private val height: Float,
    private val atX: Float,
    private val atY: Float,
    turn: Float,
    tilted: Boolean,
    density: Float,
) {
    val values: FloatArray
    private val inverse: FloatArray

    /**
     * Más allá de esto (px del plano, desde tu posición) no se dibuja. Inclinado, lo justo para que el mapa
     * llegue hasta arriba de la vista (en widgets cuadrados o altos, [REACH] se quedaba a media vista y
     * arriba solo había cielo), sin pasar de [MAX_REACH].
     */
    private val far: Float

    /** Inclinado, la altura en pantalla de lo más lejano que se dibuja ([far] adelante); puede quedar arriba de la vista. */
    val edge: Float

    /** Arriba de esta línea no hay mapa que pedir: [edge] dentro de la vista (plano, la orilla de arriba). */
    private val top: Float

    init {
        val r = Math.toRadians(turn.toDouble())
        val rc = cos(r).toFloat()
        val rs = sin(r).toFloat()
        // Girar alrededor de tu posición.
        val side = maxOf(width, height)
        val rotate = floatArrayOf(rc, -rs, atX - rc * atX + rs * atY, rs, rc, atY - rs * atX - rc * atY, 0f, 0f, 1f)
        if (tilted) {
            val t = Math.toRadians(TILT_DEGREES)
            val c = cos(t).toFloat()
            val s = sin(t).toFloat()
            val camera = maxOf(CAMERA_PX * density, CAMERA_PER_HEIGHT * height)
            // Lo que hace falta para que lo más lejano quede a [TOP_GAP] de la orilla de arriba (ahí lo cubre el
            // desvanecido); si el horizonte queda más abajo, nunca se alcanza: hasta el tope.
            val drop = atY - TOP_GAP * height
            val room = c - drop * s / camera
            val needed = if (room > 0f) drop / room else Float.MAX_VALUE
            far = needed.coerceIn(REACH * side, MAX_REACH * side)
            // Lo mismo que Camera.rotateX (la y se encoge y lo de arriba se aleja), centrado en tu posición.
            val tilt = floatArrayOf(1f, 0f, 0f, 0f, c, 0f, 0f, -s / camera, 1f)
            values = multiply(translate(atX, atY), multiply(tilt, multiply(translate(-atX, -atY), rotate)))
            edge = atY - far * c / (1f + far * s / camera)
            top = edge.coerceAtLeast(0f)
        } else {
            far = REACH * side
            values = rotate
            edge = 0f
            top = 0f
        }
        inverse = invert(values)
    }

    /**
     * Las teselas que se ven, en pares (columna, fila) sin dar la vuelta al mundo: [fx], [fy] son tu
     * posición en coordenadas de tesela y [tilePx] el lado de una en pantalla. Se toma lo visible
     * (de [top] para abajo) llevado al plano y se descarta cada tesela cuyo cuadro en pantalla no lo toca.
     */
    fun visibleTiles(fx: Double, fy: Double, tilePx: Float): IntArray {
        val corners = floatArrayOf(0f, top, width, top, 0f, height, width, height)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val p = FloatArray(3)
        for (i in corners.indices step 2) {
            project(inverse, corners[i], corners[i + 1], p)
            if (p[2] <= 0f || !p[0].isFinite() || !p[1].isFinite()) continue
            val x = (p[0] - atX).coerceIn(-far, far)
            val y = (p[1] - atY).coerceIn(-far, far)
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        if (minX > maxX) return IntArray(0)
        val out = ArrayList<Int>()
        for (tx in tileSpan(fx, minX, maxX, tilePx)) for (ty in tileSpan(fy, minY, maxY, tilePx)) {
            if (onScreen(atX + ((tx - fx) * tilePx).toFloat(), atY + ((ty - fy) * tilePx).toFloat(), tilePx, p)) {
                out += tx
                out += ty
            }
        }
        return out.toIntArray()
    }

    /**
     * Qué tan grande se ve en pantalla la tesela ([tx], [ty]) respecto a su tamaño ([tilePx]): 1 de cerca;
     * inclinado, lo lejano se ve chico (0.5 = a la mitad). La orilla más larga de su cuadro proyectado.
     */
    fun scaleOf(fx: Double, fy: Double, tilePx: Float, tx: Int, ty: Int): Float {
        val left = atX + ((tx - fx) * tilePx).toFloat()
        val top0 = atY + ((ty - fy) * tilePx).toFloat()
        val q = FloatArray(12)
        val p = FloatArray(3)
        for (corner in 0 until 4) {
            project(values, left + (corner and 1) * tilePx, top0 + (corner shr 1) * tilePx, p)
            // Detrás de la cámara: no se sabe; se queda con todo el detalle.
            if (p[2] <= 0f) return 1f
            q[corner * 3] = p[0]
            q[corner * 3 + 1] = p[1]
        }
        fun edge(a: Int, b: Int) = hypot(q[a * 3] - q[b * 3], q[a * 3 + 1] - q[b * 3 + 1])
        return maxOf(edge(0, 1), edge(2, 3), edge(0, 2), edge(1, 3)) / tilePx
    }

    /** ¿El cuadro de la tesela con esquina ([left], [top0]) toca la parte visible de la pantalla? */
    private fun onScreen(left: Float, top0: Float, size: Float, p: FloatArray): Boolean {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (corner in 0 until 4) {
            project(values, left + (corner and 1) * size, top0 + (corner shr 1) * size, p)
            // Una esquina detrás de la cámara: no se sabe dónde cae, mejor pedirla.
            if (p[2] <= 0f) return true
            minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0]); minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1])
        }
        return maxX > 0f && minX < width && maxY > top && minY < height
    }
}

/** [m] aplicada a ([x], [y]): en [out] queda x, y en pantalla y w (≤ 0 = detrás de la cámara). */
private fun project(m: FloatArray, x: Float, y: Float, out: FloatArray) {
    val w = m[6] * x + m[7] * y + m[8]
    out[0] = (m[0] * x + m[1] * y + m[2]) / w
    out[1] = (m[3] * x + m[4] * y + m[5]) / w
    out[2] = w
}

private fun translate(x: Float, y: Float) = floatArrayOf(1f, 0f, x, 0f, 1f, y, 0f, 0f, 1f)

private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
    val row = i / 3
    val col = i % 3
    a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
}

private fun invert(m: FloatArray): FloatArray {
    val a = m[4] * m[8] - m[5] * m[7]
    val b = m[5] * m[6] - m[3] * m[8]
    val c = m[3] * m[7] - m[4] * m[6]
    val det = m[0] * a + m[1] * b + m[2] * c
    return floatArrayOf(
        a / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
        b / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
        c / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
    )
}

/**
 * Cuántos zooms menos usar para una tesela que se ve a [scale] de su tamaño: lo lejano del mapa inclinado
 * se ve chico, así que una tesela "madre" (4 veces más terreno) o "abuela" (16) basta y se piden muchas menos.
 */
fun detailDrop(scale: Float): Int = when {
    scale < FAR_SCALE -> 2
    scale < MID_SCALE -> 1
    else -> 0
}

/**
 * Abajo de esta escala, un zoom menos; abajo de [FAR_SCALE], dos. La madre se dibuja a lo más 1.4 veces
 * ampliada: lejos, chica y fundiéndose con el cielo, no se nota (y en 1920×720 se piden 49 teselas, no 122).
 */
private const val MID_SCALE = 0.7f
private const val FAR_SCALE = 0.35f
