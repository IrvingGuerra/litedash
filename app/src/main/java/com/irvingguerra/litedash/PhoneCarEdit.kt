package com.irvingguerra.litedash

import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.car.CatalogEntry
import com.irvingguerra.litedash.feature.car.InstallError
import com.irvingguerra.litedash.feature.car.PaintFinish
import com.irvingguerra.litedash.feature.link.CarEdit
import com.irvingguerra.litedash.feature.link.PhoneLink
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Editar tu auto desde LiteDash Connect (PROTOCOLO_LINK.md §5e y §5f). La pantalla manda: aplica lo que pide el
 * celular, baja ella lo que falte (o, si no puede, le pide el archivo al celular) y le dice cómo va con `carJob`.
 * La confirmación es el `car` de siempre ([wirePhoneCar]). [text] da los textos para el usuario en su idioma.
 */
internal class PhoneCarEdit(
    private val link: PhoneLink,
    private val prefs: Preferences,
    private val cars: CarRepository,
    private val text: (Int) -> String,
) {
    /**
     * Entradas del catálogo que mandó el celular, solo la del auto que pidió y la del auto actual (con ellas se baja
     * lo que falte y se sabe si hay acabados).
     */
    private val entries = mutableStateMapOf<String, CatalogEntry>()

    /** Lo que se sabe del catálogo para [id]: primero el que bajó la propia pantalla; si no, lo que mandó el celular. */
    fun entryOf(id: String): CatalogEntry? = cars.knownEntry(id) ?: entries[id]

    /** Lo que se está bajando o recibiendo para el celular: el auto [id] ([CAR]) o las capas de su acabado ([FINISH]). */
    private class Job(val id: String, val kind: String, val entry: CatalogEntry) {
        var step = DOWNLOAD
        /** Cuándo se mandó el último avance (a lo más 4 por segundo). */
        var toldAt = 0L
    }

    private var job: Job? = null
        set(value) {
            field = value
            // El enlace solo acepta el paquete que se le pidió al celular ([need]).
            if (value?.step != NEED) link.expectPackage(null)
        }
    /** Un pedido que espera a que termine otra descarga (el repositorio baja una cosa a la vez). */
    private var queued: Job? = null

    init {
        link.onSetCar = ::apply
        link.onPackage = ::received
        // Se fue el celular: lo que esperaba de él ya no llega (lo que baja la pantalla sigue y se elige al terminar).
        phoneScope.launch {
            snapshotFlow { link.connected }.collect { on ->
                if (on) return@collect
                queued = null
                if (job?.step == NEED) job = null
            }
        }
        phoneScope.launch {
            snapshotFlow { cars.downloading }.collect { now ->
                val j = job
                val what = if (j?.kind == CAR) j.id else CarRepository.FINISH
                if (j != null && j.step == DOWNLOAD && now != null && now.first == what) {
                    val at = SystemClock.elapsedRealtime()
                    if (at - j.toldAt >= PROGRESS_MS) {
                        j.toldAt = at
                        link.sendCarJob(j.id, j.kind, DOWNLOAD, now.second)
                    }
                }
                if (now == null) queued?.let {
                    queued = null
                    start(it)
                }
            }
        }
    }

    /** Un `setCar` ya revisado (en el hilo principal): lo que no venga o no sea válido no se toca. */
    private fun apply(edit: CarEdit) {
        edit.color?.let { prefs.carColor = Color(OPAQUE or it) }
        edit.gloss?.let { prefs.paintGloss = it }
        edit.name?.let { prefs.carName = it.ifEmpty { null } }
        // La entrada tal como viene del catálogo, solo la del auto que se pide (o el actual, al pedir un acabado); si
        // no se puede leer o no es segura, como si no hubiera llegado. [CarEdit.id] ya viene revisado.
        val forId = edit.id?.takeIf { it.isNotEmpty() } ?: prefs.carModel
        edit.entry?.let { runCatching { CatalogEntry.fromJson(it) }.getOrNull() }
            ?.takeIf { it.isSafe && it.id == forId }
            ?.let { entries[it.id] = it }
        edit.id?.let(::choose)
        // Lo demás ya no sirve (el mapa no crece).
        entries.keys.retainAll(setOfNotNull(prefs.carModel, job?.id))
        edit.finish?.let {
            prefs.paintFinish = PaintFinish.fromStored(it)
            ensureFinish(asked = true)
        }
    }

    /** Elige el auto [id] ("" = el sedán genérico); sin instalar, lo baja (o lo pide al celular). */
    private fun choose(id: String) {
        val current = job
        if (id.isNotEmpty() && cars.pkg(id) == null) {
            // Ya se está bajando o esperando: si esperaba el archivo, se le vuelve a pedir.
            if (current != null && current.kind == CAR && current.id == id) {
                if (current.step == NEED) link.sendCarJob(id, CAR, NEED)
                return
            }
            val entry = entryOf(id)
            if (entry == null) {
                link.sendCarJob(id, CAR, FAILED, why = text(R.string.phone_job_unknown))
                return
            }
            // Otro auto cancela el anterior (sin avisar que falló: el celular ya lo sabe).
            queued = null
            start(Job(id, CAR, entry))
            return
        }
        job = null
        queued = null
        prefs.carModel = id.ifEmpty { null }
        ensureFinish(asked = false)
    }

    /**
     * Con un acabado distinto de Satinado y el auto sin sus capas: se bajan (si el catálogo las ofrece para este
     * modelo). [asked] = lo pidió el celular: si este modelo no tiene acabados, se le dice.
     */
    private fun ensureFinish(asked: Boolean) {
        if (prefs.paintFinish == PaintFinish.SATIN) return
        val current = job
        // El auto que se está bajando o instalando: sus capas se piden al terminar.
        if (current?.kind == CAR && current.step != NEED) return
        val p = cars.pkg(prefs.carModel) ?: return
        if (p.finish) return
        if (current != null && current.id == p.id) {
            if (current.step == NEED) link.sendCarJob(p.id, FINISH, NEED)
            return
        }
        val entry = entryOf(p.id)
        if (entry == null || !cars.offersFinish(p, entry)) {
            if (asked) link.sendCarJob(p.id, FINISH, FAILED, why = text(R.string.phone_job_no_finish))
            return
        }
        queued = null
        start(Job(p.id, FINISH, entry))
    }

    /** Lo baja la pantalla; si el repositorio ya baja otra cosa, espera su turno. */
    private fun start(j: Job) {
        job = j
        j.step = DOWNLOAD
        // Sin internet, que lo pase el celular; si bajó otra cosa o no se pudo instalar, se le dice por qué.
        val failed = { e: InstallError -> if (e == InstallError.OFFLINE) need(j) else fail(j, reason(e)) }
        val started = if (j.kind == CAR) {
            cars.install(j.entry, failed) { installed(j) }
        } else {
            val p = cars.pkg(j.id) ?: return fail(j, R.string.phone_job_unknown)
            cars.fetchFinish(p, j.entry, failed) { finished(j) }
        }
        if (!started) queued = j
    }

    private fun reason(e: InstallError) = if (e == InstallError.DAMAGED) R.string.phone_job_damaged else R.string.phone_job_install_failed

    /** La pantalla no pudo bajarlo (sin internet): que el celular lo pase (§5f), del tamaño que dice su entrada. */
    private fun need(j: Job) {
        if (job !== j) return
        j.step = NEED
        link.expectPackage(j.id, j.kind, if (j.kind == CAR) j.entry.bytes else j.entry.finish?.bytes ?: 0)
        link.sendCarJob(j.id, j.kind, NEED)
    }

    /** El auto quedó instalado: se elige (si no lo canceló otro pedido) y, si toca, se bajan sus capas. */
    private fun installed(j: Job) {
        if (job !== j) return
        job = null
        prefs.carModel = j.id
        link.sendCarJob(j.id, CAR, DONE)
        ensureFinish(asked = false)
    }

    private fun finished(j: Job) {
        if (job !== j) return
        job = null
        link.sendCarJob(j.id, FINISH, DONE)
    }

    private fun fail(j: Job, why: Int) {
        if (job !== j) return
        job = null
        link.sendCarJob(j.id, j.kind, FAILED, why = text(why))
    }

    /** Llegó del celular el paquete [kind] de [id] ([file]; null = llegó mal y ya se tiró). */
    private fun received(id: String, kind: String, file: File?) {
        val j = job?.takeIf { it.id == id && it.kind == kind && it.step == NEED }
        if (file == null) {
            // Con pedido, se queda esperando: el celular puede volver a mandarlo desde la parte 0.
            link.sendCarJob(id, kind, FAILED, why = text(if (j != null) R.string.phone_job_damaged else R.string.phone_job_unexpected))
            return
        }
        if (j == null) {
            phoneScope.launch(Dispatchers.IO) { file.delete() }
            link.sendCarJob(id, kind, FAILED, why = text(R.string.phone_job_unexpected))
            return
        }
        val p = cars.pkg(id)
        // Las capas solo sirven sobre el mismo modelo que se pidió (pudo cambiar mientras llegaban).
        if (kind == FINISH && (p == null || !cars.offersFinish(p, j.entry))) {
            phoneScope.launch(Dispatchers.IO) { file.delete() }
            return fail(j, if (p == null) R.string.phone_job_unknown else R.string.phone_job_no_finish)
        }
        j.step = INSTALL
        link.expectPackage(null)
        link.sendCarJob(id, kind, INSTALL)
        val failed = { e: InstallError -> fail(j, reason(e)) }
        // Si no empieza (ya se baja otra cosa), el repositorio borra el archivo.
        val started = if (kind == CAR || p == null) {
            cars.install(j.entry, file, failed) { installed(j) }
        } else {
            cars.installFinish(p, j.entry, file, failed) { finished(j) }
        }
        if (!started) fail(j, R.string.phone_job_busy)
    }

    private companion object {
        const val CAR = "car"
        const val FINISH = "finish"
        const val DOWNLOAD = "download"
        const val NEED = "need"
        const val INSTALL = "install"
        const val DONE = "done"
        const val FAILED = "failed"
        const val PROGRESS_MS = 250L
        const val OPAQUE = 0xFF shl 24
    }
}
