package com.irvingguerra.litedash.feature.obd

import java.io.IOException

/**
 * Por qué no hay conexión con el adaptador. La app lo dice con su propio texto (ES/EN); el
 * detalle crudo del sistema solo va al reporte. [retry]: vale la pena volver a intentar solo.
 */
enum class ObdError(val retry: Boolean) {
    /** El Bluetooth de la pantalla está apagado (o no tiene). */
    BLUETOOTH_OFF(false),
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
 * siguiente intento, o null = rendirse (se vuelve a intentar cuando alguien pida el escáner otra vez
 * o el usuario toque Conectar). [retries] = reintentos seguidos ya hechos (se reinicia al conectar bien
 * y en cada intento del usuario).
 * - Lo que no se arregla reintentando (permiso, sin cable, no es OBD) nunca se reintenta.
 * - El 133 de GATT se reintenta pronto aunque nunca haya conectado.
 * - Lo demás, solo si ya estaba conectado o es parte de una racha de reintentos.
 */
internal fun retryDelay(error: ObdError, dropped: Boolean, retries: Int): Long? = when {
    !error.retry || retries >= ObdTiming.MAX_RETRIES -> null
    error == ObdError.GATT_BUSY -> ObdTiming.GATT_RETRY_MS
    dropped || retries > 0 -> ObdTiming.RECONNECT_MS
    else -> null
}

/** Los tiempos del OBD, juntos (ninguno probado en hardware más allá de lo anotado). */
internal object ObdTiming {
    /** Reintentos seguidos antes de rendirse. */
    const val MAX_RETRIES = 5
    /** Espera tras caerse (el adaptador se reinicia, el auto se apagó un momento). */
    const val RECONNECT_MS = 8_000L
    /** Espera tras un 133 de GATT. */
    const val GATT_RETRY_MS = 1_000L
    /** Un respiro antes de abrir BLE: que el Bluetooth termine la búsqueda o el GATT que se acaba de cerrar. */
    const val BLE_SETTLE_MS = 400L
    /** Pedir los servicios en el mismo aviso de "conectado" falla en varias pantallas (MediaTek). */
    const val BLE_DISCOVER_DELAY_MS = 600L
    /** Lo que se espera a que BLE quede listo (conectar, servicios y avisos). */
    const val BLE_OPEN_S = 15L
    /** Pausa entre rondas de sockets de Bluetooth clásico (solo sin estrategia recordada). */
    const val BT_ROUND_MS = 1_000L
    const val WIFI_CONNECT_MS = 5_000
    /** Cuánto dura una búsqueda de adaptadores Bluetooth LE. */
    const val SCAN_MS = 10_000L
    /** Lo lento (temperatura, gasolina, voltaje) se lee cada tanto. */
    const val SLOW_EVERY_MS = 2_000L
    /** Pausa entre vueltas de lectura (~10 por segundo con lo que tarda el adaptador). */
    const val POLL_GAP_MS = 60L
    /** Consultas seguidas sin respuesta que se toman como conexión perdida. */
    const val MAX_FAILURES = 3
    /** Llantas de fábrica: búsquedas por conexión y la espera entre ellas (el módulo puede tardar en despertar). */
    const val TIRE_SEARCHES = 3
    const val TIRE_SEARCH_GAP_MS = 5 * 60_000L
}
