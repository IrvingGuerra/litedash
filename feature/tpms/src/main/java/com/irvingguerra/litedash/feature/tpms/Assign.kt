package com.irvingguerra.litedash.feature.tpms

/**
 * La llanta en la que se acomoda sola una lectura que dice su posición (Sysgration por Bluetooth);
 * null si no toca. Solo con la pantalla de Llantas abierta ([watching]): con ella cerrada, el
 * vecino de al lado no entra. Nunca a una llanta ocupada, ni un sensor ya puesto o que el usuario
 * quitó ([removed]).
 */
internal fun autoAssignTarget(assignment: Map<Wheel, String>, r: TireReading, removed: Collection<String>, watching: Boolean): Wheel? {
    val w = r.reportedWheel ?: return null
    if (!watching || w in assignment || r.sensorId in assignment.values || r.sensorId in removed) return null
    return w
}

/**
 * Qué pasa al poner [id] en [w]: la asignación nueva, con qué llanta se intercambia el historial
 * ([swapWith]: de donde venía el sensor; su historial va con él), qué historiales se vacían
 * ([cut]: el de un sensor que ya no está) y el ajuste que estrena el sensor ([offset]: el que
 * esperaba en [pending] para el primer sensor de esa llanta, si el sensor no tiene uno propio).
 * El ajuste guardado del sensor no se mueve: va con su id.
 */
internal data class SensorMove(
    val assignment: Map<Wheel, String>,
    val swapWith: Wheel?,
    val cut: Set<Wheel>,
    val offset: Float?,
)

internal fun moveSensor(
    assignment: Map<Wheel, String>,
    w: Wheel,
    id: String,
    pending: Map<Wheel, Float>,
    offsets: Map<String, Float>,
): SensorMove {
    if (assignment[w] == id) return SensorMove(assignment, null, emptySet(), null)
    val from = assignment.entries.firstOrNull { it.value == id }?.key
    val next = assignment.filterValues { it != id } + (w to id)
    val offset = pending[w]?.takeIf { id !in offsets }
    // Venía de otra llanta: su historial llega con él y el que deja allá (el del que salió de w) ya no es de nadie.
    return if (from != null) SensorMove(next, from, setOf(from), offset)
    else SensorMove(next, null, setOf(w), offset)
}

/** Las últimas [max] ids de [list] con [id] al final (sin repetir). */
internal fun pushRecent(list: List<String>, id: String, max: Int): List<String> = (list - id + id).takeLast(max)
