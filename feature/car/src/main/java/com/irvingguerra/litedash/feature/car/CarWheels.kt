package com.irvingguerra.litedash.feature.car

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.json.JSONObject

/**
 * Dónde pisa cada rueda en cada cuadro del giro (`wheels.json`, aparte del paquete: lo saca el
 * estudio con `ruedas.py`). Cuadros en el orden 00, m00, 01, m01…; por rueda x, y en fracción del
 * cuadro y si se ve o la tapa el auto. [aspect] = ancho / alto del cuadro.
 */
@Immutable
class CarWheels private constructor(val frames: Int, private val aspect: Float, private val data: List<FloatArray>) {

    /**
     * Las cuatro ruedas del cuadro [k] (de [frames]) con el auto dibujado en una caja de [size]
     * como lo dibuja el giro: centrado y sin deformar.
     */
    fun spots(k: Int, size: Size): WheelSpots {
        val w = minOf(size.width, size.height * aspect)
        val h = w / aspect
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        val i = k.mod(frames) * 3
        return WheelSpots(
            data.map { Offset(left + it[i] * w, top + it[i + 1] * h) },
            data.map { it[i + 2] > 0.5f },
        )
    }

    companion object {
        /** El orden de [WheelSpots]: delantera izquierda, delantera derecha, trasera izquierda, trasera derecha. */
        private val KEYS = listOf("fl", "fr", "rl", "rr")

        /**
         * La versión que se necesita: un archivo anterior no cuenta como bajado y se vuelve a bajar
         * (2 = las ruedas del lado que se ve en diagonal ya no salen como tapadas).
         */
        private const val VERSION = 2

        /** null si el archivo no sirve (otro formato, cortado o de una versión anterior). */
        fun parse(text: String): CarWheels? = runCatching {
            val o = JSONObject(text)
            check(o.optInt("version", 1) >= VERSION)
            val frames = o.getInt("frames")
            val data = KEYS.map { key ->
                val a = o.getJSONArray(key)
                check(a.length() == frames * 3)
                FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
            CarWheels(frames, o.getDouble("aspect").toFloat(), data)
        }.getOrNull()
    }
}

/** Las cuatro ruedas en el cuadro que se ve: dónde pisan (en píxeles de la pieza) y si se ven. */
@Immutable
class WheelSpots(val points: List<Offset>, val visible: List<Boolean>)
