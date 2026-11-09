package com.irvingguerra.litedash

/*
 * Quitar el fondo de la foto del auto, sin ML: relleno desde los bordes por píxeles parecidos
 * al color del borde. Portado de CarPhoto.java de StradaOS (launcher abierto, GPL-3.0,
 * https://github.com/lordnordic-debug/StradaOS), adaptado a Kotlin y a funciones puras para probarlo en JUnit.
 */

/** Niveles de intensidad (0 = suave … 3 = máxima). */
const val CUT_LEVELS = 4

/** Una imagen ARGB ya recortada a lo que quedó visible. */
class Cutout(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Quita el fondo de [pixels] (ARGB, [width]×[height]). [level] 0..3: más alto quita más (sombras,
 * degradados y, desde 2, el reflejo gris del piso). Devuelve null si no encontró un fondo claro
 * (quitó menos del 8 % o más del 97 % de la imagen).
 */
fun removeBackground(pixels: IntArray, width: Int, height: Int, level: Int): Cutout? {
    val w = width
    val h = height
    val n = w * h
    if (n == 0) return null
    val strength = level.coerceIn(0, CUT_LEVELS - 1)
    val seedTol = 90 + strength * 45 // distancia máxima al color del borde
    val stepTol = 26 + strength * 10 // diferencia máxima entre vecinos (degradados)

    // Semilla: mediana por canal del borde (aguanta que el auto toque la orilla), refinada con el
    // promedio de los píxeles del borde que se le parecen.
    val border = IntArray(2 * (w + h))
    var count = 0
    for (x in 0 until w) {
        border[count++] = pixels[x]
        border[count++] = pixels[(h - 1) * w + x]
    }
    for (y in 0 until h) {
        border[count++] = pixels[y * w]
        border[count++] = pixels[y * w + w - 1]
    }
    var seed = medianColor(border, count)
    var sr = 0L
    var sg = 0L
    var sb = 0L
    var similar = 0
    for (i in 0 until count) {
        val c = border[i]
        if (dist(c, seed) < seedTol) {
            sr += (c shr 16) and 255
            sg += (c shr 8) and 255
            sb += c and 255
            similar++
        }
    }
    if (similar > 0) seed = (0xFF shl 24) or ((sr / similar).toInt() shl 16) or ((sg / similar).toInt() shl 8) or (sb / similar).toInt()

    // Relleno BFS desde los bordes: cada vecino debe parecerse al anterior y a la semilla.
    val bg = BooleanArray(n)
    val queue = IntArray(n)
    var tail = 0
    fun push(i: Int) {
        if (!bg[i] && dist(pixels[i], seed) < seedTol) {
            bg[i] = true
            queue[tail++] = i
        }
    }
    fun grow(i: Int, from: Int) {
        if (!bg[i] && dist(pixels[i], from) < stepTol && dist(pixels[i], seed) < seedTol) {
            bg[i] = true
            queue[tail++] = i
        }
    }
    for (x in 0 until w) {
        push(x)
        push((h - 1) * w + x)
    }
    for (y in 0 until h) {
        push(y * w)
        push(y * w + w - 1)
    }
    var head = 0
    while (head < tail) {
        val i = queue[head++]
        val x = i % w
        val y = i / w
        val c = pixels[i]
        if (x > 0) grow(i - 1, c)
        if (x < w - 1) grow(i + 1, c)
        if (y > 0) grow(i - w, c)
        if (y < h - 1) grow(i + w, c)
    }
    if (strength >= 2) removeReflection(pixels, bg, w, h, queue)

    val removed = bg.count { it }
    if (removed < n * 0.08 || removed > n * 0.97) return null

    // Borde suave: menos alfa entre más vecinos de fondo tenga el píxel; y se recorta a lo visible.
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    val out = IntArray(n)
    for (i in 0 until n) {
        if (bg[i]) continue
        val x = i % w
        val y = i / w
        var around = 0
        if (x > 0 && bg[i - 1]) around++
        if (x < w - 1 && bg[i + 1]) around++
        if (y > 0 && bg[i - w]) around++
        if (y < h - 1 && bg[i + w]) around++
        val a = when (around) {
            0 -> 255
            1 -> 200
            2 -> 130
            else -> 60
        }
        out[i] = (a shl 24) or (pixels[i] and 0x00FFFFFF)
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
    val cw = maxX - minX + 1
    val ch = maxY - minY + 1
    val cropped = IntArray(cw * ch)
    for (y in 0 until ch) System.arraycopy(out, (minY + y) * w + minX, cropped, y * cw, cw)
    return Cutout(cropped, cw, ch)
}

/** Reflejo o sombra gris del piso: crece desde el fondo por grises claros en el 30 % de abajo del auto. */
private fun removeReflection(p: IntArray, bg: BooleanArray, w: Int, h: Int, queue: IntArray) {
    var minY = h
    var maxY = -1
    for (y in 0 until h) for (x in 0 until w) {
        if (!bg[y * w + x]) {
            if (y < minY) minY = y
            maxY = y
        }
    }
    if (maxY < 0) return
    val start = minY + ((maxY - minY) * 0.70f).toInt()
    var head = 0
    var tail = 0
    for (y in start until h) for (x in 0 until w) if (bg[y * w + x]) queue[tail++] = y * w + x
    while (head < tail) {
        val i = queue[head++]
        val x = i % w
        val y = i / w
        // Los 4 vecinos sin crear un arreglo por píxel.
        for (k in 0 until 4) {
            val j = when (k) {
                0 -> if (x > 0) i - 1 else -1
                1 -> if (x < w - 1) i + 1 else -1
                2 -> if (y > start) i - w else -1
                else -> if (y < h - 1) i + w else -1
            }
            if (j < 0 || bg[j]) continue
            val c = p[j]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (mx > 90 && mx - mn < 45) {
                bg[j] = true
                queue[tail++] = j
            }
        }
    }
}

/** Distancia entre dos colores: suma de las diferencias por canal. */
private fun dist(a: Int, b: Int): Int =
    Math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
        Math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
        Math.abs((a and 255) - (b and 255))

private fun medianColor(c: IntArray, len: Int): Int {
    val r = IntArray(256)
    val g = IntArray(256)
    val b = IntArray(256)
    for (i in 0 until len) {
        r[(c[i] shr 16) and 255]++
        g[(c[i] shr 8) and 255]++
        b[c[i] and 255]++
    }
    return (0xFF shl 24) or (median(r, len) shl 16) or (median(g, len) shl 8) or median(b, len)
}

private fun median(hist: IntArray, len: Int): Int {
    var acc = 0
    for (v in 0 until 256) {
        acc += hist[v]
        if (acc >= len / 2) return v
    }
    return 255
}
