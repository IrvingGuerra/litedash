package com.irvingguerra.litedash.feature.map

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Cuánto se acuesta el mapa inclinado (grados). */
private const val TILT_DEGREES = 42.0

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
 * Del plano del mapa a la pantalla de una vista de `width` × `height` px con tu posición en (`atX`, `atY`): gira
 * `turn` grados alrededor de ella y la acuesta en perspectiva cuanto diga `tilt` (0 = plano, 1 = inclinado del todo: la
 * cámara mira hacia adelante, como al navegar; entre los dos, la transición). Se reusa cuadro a cuadro ([set]) sin crear
 * nada. Sin Android, para probarla con JUnit: [values] es la matriz 3×3 en el orden de `android.graphics.Matrix.setValues`.
 */
internal class MapProjection {
    val values = FloatArray(9)
    private val inverse = FloatArray(9)
    // Para armar la matriz sin crear arreglos.
    private val rotate = FloatArray(9)
    private val step = FloatArray(9)
    private val m1 = FloatArray(9)
    private val m2 = FloatArray(9)
    private val p = FloatArray(3)
    private val q = FloatArray(8)

    private var width = 0f
    private var height = 0f
    private var atX = 0f
    private var atY = 0f
    private var turn = Float.NaN
    private var tilt = 0f
    private var density = 0f

    /**
     * Más allá de esto (px del plano, desde tu posición) no se dibuja. Inclinado, lo justo para que el mapa
     * llegue hasta arriba de la vista (en widgets cuadrados o altos, [REACH] se quedaba a media vista y
     * arriba solo había cielo), sin pasar de [MAX_REACH].
     */
    private var far = 0f

    /** Inclinado, la altura en pantalla de lo más lejano que se dibuja ([far] adelante); puede quedar arriba de la vista. */
    var edge = 0f
        private set

    /** Arriba de esta línea no hay mapa que pedir: [edge] dentro de la vista (plano, la orilla de arriba). */
    private var top = 0f

    /** Pone la vista y la cámara; false si nada cambió (la matriz ya es esa). */
    fun set(width: Float, height: Float, atX: Float, atY: Float, turn: Float, tilt: Float, density: Float): Boolean {
        if (width == this.width && height == this.height && atX == this.atX && atY == this.atY && turn == this.turn &&
            tilt == this.tilt && density == this.density
        ) return false
        this.width = width; this.height = height; this.atX = atX; this.atY = atY
        this.turn = turn; this.tilt = tilt; this.density = density
        val r = Math.toRadians(turn.toDouble())
        val rc = cos(r).toFloat()
        val rs = sin(r).toFloat()
        // Girar alrededor de tu posición.
        val side = maxOf(width, height)
        fill(rotate, rc, -rs, atX - rc * atX + rs * atY, rs, rc, atY - rs * atX - rc * atY, 0f, 0f, 1f)
        if (tilt > 0f) {
            val t = Math.toRadians(TILT_DEGREES * tilt.coerceAtMost(1f))
            val c = cos(t).toFloat()
            val s = sin(t).toFloat()
            val camera = maxOf(CAMERA_PX * density, CAMERA_PER_HEIGHT * height)
            // Lo que hace falta para que lo más lejano quede a [TOP_GAP] de la orilla de arriba (ahí lo cubre el
            // desvanecido); si el horizonte queda más abajo, nunca se alcanza: hasta el tope.
            val drop = atY - TOP_GAP * height
            val room = c - drop * s / camera
            val needed = if (room > 0f) drop / room else Float.MAX_VALUE
            far = needed.coerceIn(REACH * side, MAX_REACH * side)
            // Lo mismo que Camera.rotateX (la y se encoge y lo de arriba se aleja), centrado en tu posición:
            // trasladar(at) · inclinar · trasladar(−at) · girar.
            fill(step, 1f, 0f, -atX, 0f, 1f, -atY, 0f, 0f, 1f)
            multiplyInto(step, rotate, m1)
            fill(step, 1f, 0f, 0f, 0f, c, 0f, 0f, -s / camera, 1f)
            multiplyInto(step, m1, m2)
            fill(step, 1f, 0f, atX, 0f, 1f, atY, 0f, 0f, 1f)
            multiplyInto(step, m2, values)
            edge = atY - far * c / (1f + far * s / camera)
            top = edge.coerceAtLeast(0f)
        } else {
            far = REACH * side
            rotate.copyInto(values)
            edge = 0f
            top = 0f
        }
        invertInto(values, inverse)
        return true
    }

    /** El punto ([x], [y]) del plano del mapa en la pantalla, en [out] (x, y); false si queda detrás de la cámara. */
    fun toScreen(x: Float, y: Float, out: FloatArray): Boolean {
        project(values, x, y, out)
        return out[2] > 0f && out[0].isFinite() && out[1].isFinite()
    }

    /** El punto ([x], [y]) de la pantalla en el plano del mapa, en [out] (x, y); false si cae en el cielo. */
    fun toPlane(x: Float, y: Float, out: FloatArray): Boolean {
        project(inverse, x, y, out)
        return out[2] > 0f && out[0].isFinite() && out[1].isFinite()
    }

    /**
     * Las teselas que se ven, en pares (columna, fila) en [out], sin dar la vuelta al mundo: [fx], [fy] son tu
     * posición en coordenadas de tesela y [tilePx] el lado de una en pantalla. Se toma lo visible
     * (de [top] para abajo) llevado al plano y se descarta cada tesela cuyo cuadro en pantalla no lo toca.
     */
    fun visibleTiles(fx: Double, fy: Double, tilePx: Float, out: IntList) {
        out.clear()
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (corner in 0 until 4) {
            project(inverse, if (corner and 1 == 0) 0f else width, if (corner shr 1 == 0) top else height, p)
            if (p[2] <= 0f || !p[0].isFinite() || !p[1].isFinite()) continue
            val x = (p[0] - atX).coerceIn(-far, far)
            val y = (p[1] - atY).coerceIn(-far, far)
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        if (minX > maxX) return
        val xs = tileSpan(fx, minX, maxX, tilePx)
        val ys = tileSpan(fy, minY, maxY, tilePx)
        for (tx in xs) for (ty in ys) {
            if (onScreen(atX + ((tx - fx) * tilePx).toFloat(), atY + ((ty - fy) * tilePx).toFloat(), tilePx)) {
                out.add(tx)
                out.add(ty)
            }
        }
    }

    /**
     * Qué tan grande se ve en pantalla la tesela ([tx], [ty]) respecto a su tamaño ([tilePx]): 1 de cerca;
     * inclinado, lo lejano se ve chico (0.5 = a la mitad). La orilla más larga de su cuadro proyectado.
     */
    fun scaleOf(fx: Double, fy: Double, tilePx: Float, tx: Int, ty: Int): Float {
        val left = atX + ((tx - fx) * tilePx).toFloat()
        val top0 = atY + ((ty - fy) * tilePx).toFloat()
        for (corner in 0 until 4) {
            project(values, left + (corner and 1) * tilePx, top0 + (corner shr 1) * tilePx, p)
            // Detrás de la cámara: no se sabe; se queda con todo el detalle.
            if (p[2] <= 0f) return 1f
            q[corner * 2] = p[0]
            q[corner * 2 + 1] = p[1]
        }
        // Las cuatro orillas: arriba (0-1), abajo (2-3), izquierda (0-2) y derecha (1-3).
        val longest = maxOf(maxOf(side(0, 1), side(2, 3)), maxOf(side(0, 2), side(1, 3)))
        return longest / tilePx
    }

    private fun side(a: Int, b: Int) = hypot(q[a * 2] - q[b * 2], q[a * 2 + 1] - q[b * 2 + 1])

    /** ¿El cuadro de la tesela con esquina ([left], [top0]) toca la parte visible de la pantalla? */
    private fun onScreen(left: Float, top0: Float, size: Float): Boolean {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (corner in 0 until 4) {
            project(values, left + (corner and 1) * size, top0 + (corner shr 1) * size, p)
            // Una esquina detrás de la cámara: no se sabe dónde cae, mejor pedirla.
            if (p[2] <= 0f) return true
            minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0]); minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1])
        }
        return maxX > 0f && minX < width && maxY > top && minY < height
    }

    companion object {
        /** Una ya puesta (para las pruebas y para quien la use una vez). */
        fun of(width: Float, height: Float, atX: Float, atY: Float, turn: Float, tilt: Float, density: Float) =
            MapProjection().apply { set(width, height, atX, atY, turn, tilt, density) }
    }
}

/** Una lista de enteros que crece y se reusa (sin meter cada número en una caja). */
internal class IntList(capacity: Int = 64) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    operator fun get(i: Int) = data[i]

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun clear() {
        size = 0
    }
}

/** Como [IntList], con decimales. */
internal class DoubleList(capacity: Int = 64) {
    private var data = DoubleArray(capacity.coerceAtLeast(1))
    var size = 0
        private set

    operator fun get(i: Int) = data[i]

    operator fun set(i: Int, value: Double) {
        data[i] = value
    }

    fun last() = data[size - 1]

    fun add(value: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }
}

/** [m] aplicada a ([x], [y]): en [out] queda x, y en pantalla y w (≤ 0 = detrás de la cámara). */
private fun project(m: FloatArray, x: Float, y: Float, out: FloatArray) {
    val w = m[6] * x + m[7] * y + m[8]
    out[0] = (m[0] * x + m[1] * y + m[2]) / w
    out[1] = (m[3] * x + m[4] * y + m[5]) / w
    out[2] = w
}

private fun fill(m: FloatArray, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float, g: Float, h: Float, i: Float) {
    m[0] = a; m[1] = b; m[2] = c; m[3] = d; m[4] = e; m[5] = f; m[6] = g; m[7] = h; m[8] = i
}

/** [a] · [b] en [out] (que no sea ninguno de los dos). */
private fun multiplyInto(a: FloatArray, b: FloatArray, out: FloatArray) {
    for (i in 0 until 9) {
        val row = i / 3
        val col = i % 3
        out[i] = a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }
}

/** La inversa de [m] en [out]. */
private fun invertInto(m: FloatArray, out: FloatArray) {
    val a = m[4] * m[8] - m[5] * m[7]
    val b = m[5] * m[6] - m[3] * m[8]
    val c = m[3] * m[7] - m[4] * m[6]
    val det = m[0] * a + m[1] * b + m[2] * c
    fill(
        out,
        a / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
        b / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
        c / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
    )
}

/**
 * Cuántos zooms menos usar para una tesela que se ve a [scale] de su tamaño: lo lejano del mapa inclinado
 * se ve chico, así que una tesela "madre" (4 veces más terreno) o "abuela" (16) basta y se piden muchas menos.
 */
internal fun detailDrop(scale: Float): Int = when {
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
