package com.irvingguerra.litedash.feature.link

/**
 * Cuándo toca buscar al celular (PROTOCOLO_LINK.md §6), en un solo lugar y sin Android (se prueba con un reloj
 * falso). Todos los avisos (abrir LiteDash, faro, red nueva, Bluetooth, el panel) solo piden un intento
 * ([waitFor]): nunca dos con menos de [MIN_GAP_MS] entre sí. Tras un intento fallido:
 * - **LiteDash al frente:** otro a los 5, 10, 20 y 40 s y luego cada minuto, mientras siga al frente.
 * - **En segundo plano:** ninguno por tiempo. Solo el oído del faro y los cambios de red piden intentos (el faro
 *   del celular suena cada 30 s mientras te busca: es lo que lo hace conectar solo y casi no cuesta).
 */
internal class LinkSchedule {
    /** Reintentos seguidos sin conexión desde el último reinicio de la cuenta. */
    var retries = 0
        private set
    private var attemptedAt = NEVER
    private var sweptAt = NEVER
    private var sweepNext = true

    /** Cuánto falta para poder intentar (0 = ya). */
    fun waitFor(now: Long): Long = (attemptedAt + MIN_GAP_MS - now).coerceAtLeast(0)

    /**
     * Empieza un intento ahora; regresa si toca además tocar a los vecinos (§1c): solo con LiteDash al frente,
     * en el primer intento tras abrir, cambiar de red o "Buscar ahora", y cada [SWEEP_WATCHING_MS] con el panel.
     */
    fun attempt(now: Long, foreground: Boolean, watching: Boolean): Boolean {
        attemptedAt = now
        val sweep = foreground && (sweepNext || (watching && now - sweptAt >= SWEEP_WATCHING_MS))
        if (sweep) {
            sweepNext = false
            sweptAt = now
        }
        return sweep
    }

    /** El intento falló: cuánto esperar al siguiente; null = no se programa ninguno (segundo plano). */
    fun failed(foreground: Boolean): Long? {
        if (!foreground) return null
        return RETRY_MS[retries.coerceAtMost(RETRY_MS.lastIndex)].also { retries++ }
    }

    /** Conectó o LiteDash volvió al frente: la cuenta vuelve a empezar (sin volver a tocar a los vecinos). */
    fun resetRetries() {
        retries = 0
    }

    /** Otra red o "Buscar ahora": seguido otra vez y tocando a los vecinos. */
    fun restart() {
        retries = 0
        sweepNext = true
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2
        /** Entre dos intentos, pidan lo que pidan los avisos (el panel abierto también intenta a este paso). */
        const val MIN_GAP_MS = 2_000L
        val RETRY_MS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)
        const val SWEEP_WATCHING_MS = 10_000L
    }
}

/**
 * Lo último visto de cada red (sus IPv4 y puertas de enlace), para saber si un aviso de Android es de verdad
 * otra red: `onLinkPropertiesChanged` llega seguido en algunas pantallas por IPv6 o DNS, que no cambian nada aquí.
 */
internal class NetworkMemory<K> {
    private val seen = HashMap<K, String>()

    /** [network] tiene estas direcciones: true si es nueva o cambiaron. */
    fun changed(network: K, addresses: Collection<String>, gateways: Collection<String>): Boolean {
        val signature = addresses.sorted().joinToString(",") + "|" + gateways.sorted().joinToString(",")
        return seen.put(network, signature) != signature
    }

    fun lost(network: K) {
        seen.remove(network)
    }

    fun clear() = seen.clear()
}
