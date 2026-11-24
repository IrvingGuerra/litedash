package com.irvingguerra.litedash.feature.obd

import android.bluetooth.le.ScanCallback
import java.io.IOException

/**
 * Por qué no hay conexión con el adaptador. La app lo dice con su propio texto (ES/EN); el
 * detalle crudo del sistema solo va al reporte. [retry]: vale la pena volver a intentar solo.
 */
enum class ObdError(val retry: Boolean) {
    /** El Bluetooth de la pantalla está apagado (o no tiene): al prender conecta; mientras, reintentar no cuesta. */
    BLUETOOTH_OFF(true),
    NO_BLUETOOTH_PERMISSION(false),
    /** Esta pantalla no maneja USB. */
    NO_USB(false),
    NO_USB_PERMISSION(false),
    /** El cable no está conectado (o se desenchufó). */
    USB_UNPLUGGED(false),
    /** El cable no es un puerto serie. */
    USB_NOT_SERIAL(false),
    /** El cable no aceptó la configuración o no contestó a ninguna velocidad. */
    USB_SILENT(true),
    /** El aparato no se comporta como un adaptador OBD (otro aparato BLE). */
    NOT_OBD(false),
    /** Bluetooth LE ocupado o se cayó al abrir (el 133 de GATT, típico de MediaTek): suele conectar al rato. */
    GATT_BUSY(true),
    /** No se pudo abrir la conexión (apagado, fuera de alcance o ya ocupado por otro aparato). */
    ADAPTER_UNREACHABLE(true),
    /** Se conectó pero el adaptador no contesta. */
    ADAPTER_SILENT(true),
    /** El adaptador contesta pero el auto no (contacto apagado, protocolo no soportado). */
    CAR_SILENT(true),
    /** Se cayó estando conectado. */
    CONNECTION_LOST(true),
    UNKNOWN(true),
}

/** Un fallo del OBD con su [kind]; el mensaje es el detalle crudo (solo para el reporte). */
class ObdException(val kind: ObdError, detail: String? = null, cause: Throwable? = null) :
    IOException(detail ?: cause?.message ?: kind.name, cause)

/**
 * El tipo de cualquier fallo, en un solo lugar: los propios ya traen el suyo; los del sistema
 * se clasifican. [dropped]: ya estaba conectado (entonces lo que vale reintentar es "se cayó").
 */
internal fun classify(t: Throwable, dropped: Boolean): ObdError {
    val kind = when (t) {
        is ObdException -> t.kind
        is SecurityException -> ObdError.NO_BLUETOOTH_PERMISSION
        is IOException -> ObdError.CONNECTION_LOST
        else -> ObdError.UNKNOWN
    }
    return if (dropped && kind.retry) ObdError.CONNECTION_LOST else kind
}

/** El detalle crudo de un fallo para el reporte (nunca para el usuario). */
internal fun detailOf(t: Throwable): String = t.message ?: t.cause?.message ?: t.javaClass.name

/**
 * La política de conexión, toda aquí (pura, con pruebas). Tras un fallo: cuánto esperar para el
 * siguiente intento, o null = rendirse (se vuelve a intentar cuando algo pida el escáner otra vez
 * o el usuario toque Conectar). [retries] = reintentos seguidos ya hechos (se reinicia al conectar bien
 * y en cada intento del usuario). [wanted] = se mantiene conectado ([ObdRepository.keep]).
 * - Lo que no se arregla reintentando (permiso, sin cable, no es OBD) nunca se reintenta.
 * - El 133 de GATT se reintenta pronto aunque nunca haya conectado.
 * - Tras caerse, dentro de una racha o si se mantiene, se reintenta pronto hasta
 *   [ObdTiming.MAX_RETRIES]. Un primer fallo sin haber conectado y sin mantenerse se queda a la vista.
 * - Pasado el tope, mientras se mantenga no se rinde (el adaptador puede seguir dormido al encender
 *   el auto): espera cada vez más, [ObdTiming.WANTED_RETRY_MS] al doble hasta [ObdTiming.WANTED_MAX_MS].
 *   Si no, se rinde.
 */
internal fun retryDelay(error: ObdError, dropped: Boolean, retries: Int, wanted: Boolean): Long? = when {
    !error.retry -> null
    retries < ObdTiming.MAX_RETRIES && error == ObdError.GATT_BUSY -> ObdTiming.GATT_RETRY_MS
    retries < ObdTiming.MAX_RETRIES && (dropped || retries > 0 || wanted) -> ObdTiming.RECONNECT_MS
    wanted -> {
        val doublings = (retries - ObdTiming.MAX_RETRIES).coerceIn(0, 8)
        (ObdTiming.WANTED_RETRY_MS shl doublings).coerceAtMost(ObdTiming.WANTED_MAX_MS)
    }
    else -> null
}

/**
 * ¿[ObdRepository.keep] vuelve a conectar tras [error]? Lo que se arregla solo, sí. Lo que pide algo del usuario
 * (negó el permiso del cable, no es un adaptador OBD, no es puerto serie) no: si no, el diálogo de Android salía cada
 * vez que aparecía un medidor; eso se reintenta con «Conectar». Tres se arreglan sin tocar el adaptador: el permiso de
 * Bluetooth que ya se dio ([permitted]), el cable que ya está otra vez ([cablePresent]) y el permiso del cable que se
 * dio por fuera ([cableAllowed]: «Abrir siempre con LiteDash» al enchufarlo).
 */
internal fun keepReconnects(error: ObdError?, permitted: Boolean, cablePresent: Boolean, cableAllowed: Boolean): Boolean =
    when (error) {
        null -> true
        ObdError.NO_BLUETOOTH_PERMISSION -> permitted
        ObdError.USB_UNPLUGGED -> cablePresent
        ObdError.NO_USB_PERMISSION -> cableAllowed
        else -> error.retry
    }

/**
 * ¿Volver a enchufar el cable elegido reconecta tras [error]? El que faltaba y lo que se arregla solo; tras negar el
 * permiso, solo si ya se dio ([cableAllowed]).
 */
internal fun replugReconnects(error: ObdError?, cableAllowed: Boolean): Boolean =
    error == ObdError.USB_UNPLUGGED || error?.retry == true || error == ObdError.NO_USB_PERMISSION && cableAllowed

/**
 * ¿Repetir una búsqueda BLE que falló con [errorCode]? Solo una vez y solo los errores de la pila que suelen
 * pasarse solos: 2 (no se pudo registrar la búsqueda) y 3 (error interno). Los demás (ya buscando, sin
 * soporte) no se arreglan repitiendo.
 */
internal fun retryScan(errorCode: Int, retried: Boolean): Boolean =
    !retried && (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED || errorCode == ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

/** Los tiempos del OBD, juntos (ninguno probado en hardware más allá de lo anotado). */
internal object ObdTiming {
    /** Reintentos seguidos antes de rendirse. */
    const val MAX_RETRIES = 5
    /** Espera tras caerse (el adaptador se reinicia, el auto se apagó un momento). */
    const val RECONNECT_MS = 8_000L
    /** Mientras se mantiene, pasado el tope: la primera espera (luego al doble)… */
    const val WANTED_RETRY_MS = 15_000L
    /** …y la más larga. */
    const val WANTED_MAX_MS = 60_000L
    /** Lo que se espera tras prender el Bluetooth de la pantalla antes de conectar (que termine de arrancar). */
    const val BLUETOOTH_ON_SETTLE_MS = 2_000L
    /** Lo que se espera tras enchufar el cable antes de abrirlo (que Android termine de reconocerlo). */
    const val USB_ATTACH_SETTLE_MS = 1_000L
    /** Respiro entre cerrar un enlace Bluetooth y abrir otro. */
    const val REOPEN_GAP_MS = 1_200L
    /** Espera tras un 133 de GATT. */
    const val GATT_RETRY_MS = 1_000L
    /** Un respiro antes de abrir BLE: que el Bluetooth termine la búsqueda o el GATT que se acaba de cerrar. */
    const val BLE_SETTLE_MS = 400L
    /** Pedir los servicios en el mismo aviso de "conectado" falla en varias pantallas (MediaTek). */
    const val BLE_DISCOVER_DELAY_MS = 600L
    /** Lo que se espera a que BLE quede listo (conectar, servicios y avisos). */
    const val BLE_OPEN_S = 15L
    const val WIFI_CONNECT_MS = 5_000
    /** Cuánto dura una búsqueda de adaptadores Bluetooth LE. */
    const val SCAN_MS = 10_000L
    /** Espera antes de repetir una búsqueda BLE que la pila no pudo registrar. */
    const val SCAN_RETRY_MS = 1_500L
    /** Lo lento (temperatura, gasolina, voltaje) se lee cada tanto. */
    const val SLOW_EVERY_MS = 2_000L
    /** Pausa entre vueltas de lectura (~10 por segundo con lo que tarda el adaptador). */
    const val POLL_GAP_MS = 60L
    /** Consultas seguidas sin respuesta que se toman como conexión perdida. */
    const val MAX_FAILURES = 3
    /**
     * Sin ningún dato del auto este tiempo (el ELM contesta "NO DATA": el conector tiene corriente con el contacto
     * apagado) se toma el motor como apagado ([ObdRepository.carSilent]); el enlace sigue conectado.
     */
    const val CAR_SILENT_MS = 75_000L
    /** Llantas de fábrica: búsquedas por conexión y la espera entre ellas (el módulo puede tardar en despertar). */
    const val TIRE_SEARCHES = 3
    const val TIRE_SEARCH_GAP_MS = 5 * 60_000L
}

/**
 * Si el auto parece apagado: ningún PID dio dato en [ObdTiming.CAR_SILENT_MS] ([ObdRepository.carSilent]). Solo cuenta
 * mientras se le preguntan PIDs; sin ninguno no se sabe y no se dice apagado (el Taller lo muestra en el adaptador).
 */
internal class CarSilence(start: Long) {
    var silent = false
        private set
    private var lastData = start

    /** Plan nuevo: la cuenta empieza de cero (un primer "NO DATA" tras un rato sin leer no es motor apagado). */
    fun restart(now: Long) {
        lastData = now
    }

    /** Nada que preguntarle al auto: no se sabe si está prendido. */
    fun unknown() {
        silent = false
    }

    /** Una vuelta de lectura; devuelve true si justo ahora se quedó callado (para anotarlo una vez). */
    fun lap(now: Long, answered: Boolean): Boolean {
        if (answered) {
            lastData = now
            silent = false
            return false
        }
        if (silent || now - lastData < ObdTiming.CAR_SILENT_MS) return false
        silent = true
        return true
    }
}
