package com.irvingguerra.litedash

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarPaint
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.car.snapshot
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.ScreenCar
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Vive mientras viva la app, como el enlace: lo del celular solo trabaja con él conectado. */
internal val phoneScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * Tu auto en LiteDash Connect (PROTOCOLO_LINK.md §5d y §5e): al conectarse un celular que lo pide y cada vez que
 * cambian el modelo, el color, el acabado, el brillo, el nombre o los autos instalados, se le manda; la imagen (la
 * misma del inicio) solo si no la tiene. [name] es como se llama el auto en la pantalla; [edit] sabe qué entradas
 * del catálogo mandó el celular (para decir si el modelo tiene acabados).
 */
internal fun wirePhoneCar(link: PhoneLink, prefs: Preferences, cars: CarRepository, edit: PhoneCarEdit, name: () -> String) {
    phoneScope.launch {
        snapshotFlow {
            if (!link.connected || !link.wantsCar) return@snapshotFlow null
            val p = cars.pkg(prefs.carModel)
            Shown(p, prefs.carPaint, name(), p != null && cars.hasFinishes(p, edit.entryOf(p.id)), cars.installed.map { it.id })
        }
            .collectLatest { shown ->
                shown ?: return@collectLatest
                // El selector de color pinta en vivo: se manda cuando se queda quieto.
                delay(SETTLE_MS)
                val p = shown.p
                val image = imageKey(p, shown.paint)
                // Si no se puede dibujar (poca memoria en una pantalla sencilla), va sin imagen: el celular sigue con la de antes.
                val webp = if (link.needsCarImage(image)) {
                    try {
                        cars.snapshot(p, shown.paint, IMAGE_WIDTH)?.webp()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }
                val car = ScreenCar(
                    id = p?.id.orEmpty(),
                    name = shown.name,
                    model = p?.name.orEmpty(),
                    make = p?.make.orEmpty(),
                    color = shown.paint.color.toArgb(),
                    finish = shown.paint.finish.stored,
                    gloss = shown.paint.gloss,
                    finishes = shown.finishes,
                    cars = shown.cars,
                    image = image,
                )
                link.sendCar(car, webp)
            }
    }
}

/** Lo que se ve del auto (y lo que puede cambiar el celular): si cambia algo, se vuelve a mandar. */
private data class Shown(val p: CarPackage?, val paint: CarPaint, val name: String, val finishes: Boolean, val cars: List<String>)

/** Huella de la imagen: cambia con lo que se ve (modelo y su versión, pintura), no con el nombre. */
private fun imageKey(p: CarPackage?, paint: CarPaint): String {
    val gloss = if (paint.finish.shines) "%.2f".format(java.util.Locale.ROOT, paint.gloss) else ""
    val text = listOf(DRAWING_VERSION, p?.id.orEmpty(), p?.sha256.orEmpty(), paint.color.toArgb(), paint.finish.stored, gloss).joinToString("|")
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

/** WebP con transparencia; si pesa de más, con menos calidad. */
private suspend fun Bitmap.webp(): ByteArray = withContext(Dispatchers.Default) {
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    val out = ByteArrayOutputStream()
    var quality = 82
    while (true) {
        out.reset()
        compress(format, quality, out)
        if (out.size() <= MAX_BYTES || quality <= 50) break
        quality -= 12
    }
    out.toByteArray()
}

/** Cambia si cambia cómo se dibuja la imagen (para que el celular la vuelva a pedir). */
private const val DRAWING_VERSION = 3
private const val SETTLE_MS = 800L
/** Ancho del auto en la imagen: se ve nítido a lo ancho de un celular. */
private const val IMAGE_WIDTH = 840
private const val MAX_BYTES = 150 * 1024
