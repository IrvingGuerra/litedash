package com.irvingguerra.litedash.feature.obd

import java.io.IOException

/**
 * Las lecturas del motor mientras dura la conexión. Una que falla no tumba el enlace (adaptador lento, el auto
 * ocupado, un clon que a veces no manda su ">"): tras un ">" que no llegó se resincroniza ([Elm327.resync]) para
 * que la siguiente no lea la respuesta vieja. Solo a la [ObdTiming.MAX_FAILURES]-ésima falla seguida (contando un
 * "ATI" que tampoco contestó) se da por perdido y se lanza. "NO DATA" no es falla: ese dato no está (null).
 */
internal class ReadGuard(private val e: Elm327) {
    private var failures = 0

    fun <T> ask(read: () -> T?): T? = try {
        read().also { failures = 0 }
    } catch (x: IOException) {
        if (++failures >= ObdTiming.MAX_FAILURES) throw x
        val silent = x is ObdException && x.kind == ObdError.ADAPTER_SILENT
        if (silent && !e.resync() && ++failures >= ObdTiming.MAX_FAILURES) throw x
        null
    }
}
