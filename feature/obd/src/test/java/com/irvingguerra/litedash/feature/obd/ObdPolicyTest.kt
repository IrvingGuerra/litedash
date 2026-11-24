package com.irvingguerra.litedash.feature.obd

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPolicyTest {
    @Test fun lo_que_no_se_arregla_no_se_reintenta() {
        for (e in listOf(ObdError.NO_BLUETOOTH_PERMISSION, ObdError.NOT_OBD, ObdError.USB_UNPLUGGED, ObdError.NO_USB_PERMISSION)) {
            assertNull(e.name, retryDelay(e, dropped = true, retries = 0, wanted = false))
        }
    }

    @Test fun mantener_no_insiste_con_lo_que_pide_al_usuario() {
        // Negó el permiso del cable: cambiar de página o entrar al Taller ya no vuelve a sacar el diálogo.
        for (e in listOf(ObdError.NO_USB_PERMISSION, ObdError.NOT_OBD, ObdError.USB_NOT_SERIAL, ObdError.NO_USB)) {
            assertFalse(e.name, keepReconnects(e, permitted = true, cablePresent = true, cableAllowed = false))
        }
        // Lo que se arregla solo sigue como antes.
        for (e in listOf(ObdError.ADAPTER_SILENT, ObdError.CAR_SILENT, ObdError.BLUETOOTH_OFF, ObdError.CONNECTION_LOST)) {
            assertTrue(e.name, keepReconnects(e, permitted = false, cablePresent = false, cableAllowed = false))
        }
        // El permiso de Bluetooth que ya se dio, el cable que ya está otra vez y el permiso del cable dado por fuera, sí.
        assertTrue(keepReconnects(ObdError.NO_BLUETOOTH_PERMISSION, permitted = true, cablePresent = false, cableAllowed = false))
        assertFalse(keepReconnects(ObdError.NO_BLUETOOTH_PERMISSION, permitted = false, cablePresent = false, cableAllowed = false))
        assertTrue(keepReconnects(ObdError.USB_UNPLUGGED, permitted = false, cablePresent = true, cableAllowed = false))
        assertFalse(keepReconnects(ObdError.USB_UNPLUGGED, permitted = true, cablePresent = false, cableAllowed = false))
        assertTrue(keepReconnects(ObdError.NO_USB_PERMISSION, permitted = false, cablePresent = true, cableAllowed = true))
    }

    @Test fun ble_sin_canal_serie_una_vez_se_reintenta() {
        // La caché de GATT incompleta se ve igual que un aparato que no es OBD: la primera vez se reintenta solo.
        assertEquals(ObdError.GATT_BUSY, noChannelError("AA:BB", previous = null))
        assertEquals(ObdError.GATT_BUSY, noChannelError("AA:BB", previous = "CC:DD"))
        assertTrue(ObdError.GATT_BUSY.retry)
        // Dos seguidas en el mismo aparato: no es OBD y [keepReconnects] ya no insiste.
        assertEquals(ObdError.NOT_OBD, noChannelError("AA:BB", previous = "AA:BB"))
    }

    @Test fun enchufar_el_cable_reconecta_salvo_permiso_negado() {
        assertTrue(replugReconnects(ObdError.USB_UNPLUGGED, cableAllowed = false))
        assertTrue(replugReconnects(ObdError.USB_SILENT, cableAllowed = false))
        assertFalse(replugReconnects(ObdError.NO_USB_PERMISSION, cableAllowed = false))
        assertTrue(replugReconnects(ObdError.NO_USB_PERMISSION, cableAllowed = true))
        assertFalse(replugReconnects(ObdError.USB_NOT_SERIAL, cableAllowed = true))
    }

    @Test fun busqueda_ble_fallida_se_repite_una_vez() {
        // 3 = error interno (NWD T3, 8227L) y 2 = no se pudo registrar: una vez más; lo demás o la segunda, no.
        assertTrue(retryScan(3, retried = false))
        assertTrue(retryScan(2, retried = false))
        assertFalse(retryScan(3, retried = true))
        assertFalse(retryScan(1, retried = false))
        assertFalse(retryScan(4, retried = false))
    }

    @Test fun el_133_se_reintenta_pronto_aunque_nunca_haya_conectado() =
        assertEquals(ObdTiming.GATT_RETRY_MS, retryDelay(ObdError.GATT_BUSY, dropped = false, retries = 0, wanted = false))

    @Test fun primer_fallo_sin_haber_conectado_se_queda_a_la_vista() =
        assertNull(retryDelay(ObdError.ADAPTER_UNREACHABLE, dropped = false, retries = 0, wanted = false))

    @Test fun caida_y_racha_de_reintentos() {
        assertEquals(ObdTiming.RECONNECT_MS, retryDelay(ObdError.CONNECTION_LOST, dropped = true, retries = 0, wanted = false))
        // Un reintento que falla sin conectar sigue la racha (antes solo se intentaba una vez).
        assertEquals(ObdTiming.RECONNECT_MS, retryDelay(ObdError.ADAPTER_UNREACHABLE, dropped = false, retries = 1, wanted = false))
    }

    @Test fun se_rinde_al_tope() {
        assertNull(retryDelay(ObdError.CONNECTION_LOST, dropped = true, retries = ObdTiming.MAX_RETRIES, wanted = false))
        assertNull(retryDelay(ObdError.GATT_BUSY, dropped = false, retries = ObdTiming.MAX_RETRIES, wanted = false))
    }

    @Test fun mientras_se_mantiene_no_se_rinde() {
        // Adaptador dormido al encender el auto: el primer fallo ya se reintenta.
        assertEquals(ObdTiming.RECONNECT_MS, retryDelay(ObdError.ADAPTER_UNREACHABLE, dropped = false, retries = 0, wanted = true))
        // Pasado el tope, esperas que crecen al doble…
        val waits = (0..12).map { retryDelay(ObdError.ADAPTER_UNREACHABLE, dropped = false, retries = ObdTiming.MAX_RETRIES + it, wanted = true)!! }
        assertEquals(ObdTiming.WANTED_RETRY_MS, waits.first())
        assertEquals(ObdTiming.WANTED_RETRY_MS * 2, waits[1])
        assertTrue(waits.zipWithNext().all { (a, b) -> b >= a })
        // …hasta el tope, y ahí se quedan.
        assertEquals(ObdTiming.WANTED_MAX_MS, waits.last())
        assertTrue(waits.all { it <= ObdTiming.WANTED_MAX_MS })
        // El 133 también sigue, ya con la espera larga.
        assertEquals(ObdTiming.WANTED_RETRY_MS, retryDelay(ObdError.GATT_BUSY, dropped = false, retries = ObdTiming.MAX_RETRIES, wanted = true))
        // Lo que no se arregla reintentando sigue sin reintentarse aunque lo quieran.
        assertNull(retryDelay(ObdError.NO_USB_PERMISSION, dropped = false, retries = 0, wanted = true))
    }

    @Test fun auto_callado_75_s_parece_apagado_y_vuelve_con_un_dato() {
        val limit = ObdTiming.CAR_SILENT_MS
        val s = CarSilence(start = 0)
        assertFalse(s.lap(limit - 1, answered = false))
        assertFalse(s.silent)
        // Se anota una sola vez al quedarse callado.
        assertTrue(s.lap(limit, answered = false))
        assertTrue(s.silent)
        assertFalse(s.lap(limit + 5_000, answered = false))
        assertTrue(s.silent)
        assertFalse(s.lap(limit + 6_000, answered = true))
        assertFalse(s.silent)
        // Plan nuevo: la cuenta empieza de cero.
        s.restart(limit * 3)
        assertFalse(s.lap(limit * 3 + 1_000, answered = false))
        assertFalse(s.silent)
    }

    @Test fun sin_nada_que_preguntar_no_se_dice_apagado() {
        val s = CarSilence(start = 0)
        s.lap(ObdTiming.CAR_SILENT_MS, answered = false)
        assertTrue(s.silent)
        s.unknown()
        assertFalse(s.silent)
    }

    @Test fun clasifica_los_fallos() {
        assertEquals(ObdError.CAR_SILENT, classify(ObdException(ObdError.CAR_SILENT), dropped = false))
        assertEquals(ObdError.NO_BLUETOOTH_PERMISSION, classify(SecurityException("x"), dropped = false))
        assertEquals(ObdError.CONNECTION_LOST, classify(IOException("read failed"), dropped = false))
        assertEquals(ObdError.UNKNOWN, classify(IllegalStateException(), dropped = false))
        // Ya conectado, un adaptador que deja de contestar es una caída; un cable desenchufado sigue siendo eso.
        assertEquals(ObdError.CONNECTION_LOST, classify(ObdException(ObdError.ADAPTER_SILENT), dropped = true))
        assertEquals(ObdError.USB_UNPLUGGED, classify(ObdException(ObdError.USB_UNPLUGGED), dropped = true))
    }

    @Test fun cola_de_bytes() {
        val q = ByteQueue()
        q.put(ByteArray(300) { it.toByte() })
        q.put(byteArrayOf(9, 8, 7), 1, 2)
        assertEquals(302, q.size)
        repeat(300) { assertEquals(it and 0xFF, q.take(0)) }
        assertEquals(8, q.take(0))
        assertEquals(7, q.take(0))
        assertNull(q.take(10))
    }

    @Test fun cerrar_la_cola_suelta_al_que_espera() {
        val q = ByteQueue()
        val done = CountDownLatch(1)
        var got: Int? = 0
        thread {
            got = q.take(10_000)
            done.countDown()
        }
        Thread.sleep(50)
        q.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNull(got)
    }
}
