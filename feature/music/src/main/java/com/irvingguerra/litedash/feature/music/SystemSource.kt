package com.irvingguerra.litedash.feature.music

import android.content.Context
import java.lang.reflect.Modifier

/**
 * Para los reportes: el "servicio de fuentes" de las pantallas Hengchen (HCN, `android.sourceservice.SourceInfo`,
 * clase del firmware), que sabe qué suena y, según el reporte de StradaOS #5, la canción que el launcher de
 * fábrica muestra de ZLINK (cuya sesión de medios llega vacía). No se usa todavía para mostrar nada: primero
 * hay que ver en un reporte real qué trae. Solo lee; textos como "sí"/"vacío" (nunca el nombre de la canción).
 */
object SystemSource {
    private const val CLASS = "android.sourceservice.SourceInfo"
    private const val MAX_ITEMS = 40

    /** Una línea para el reporte; null si la pantalla no tiene esa clase (casi todas). En otro hilo: reflexión e IPC. */
    fun describe(context: Context): String? {
        val cls = runCatching { Class.forName(CLASS) }.getOrNull() ?: return null
        return buildString {
            append("Fuente del sistema (HCN): ")
            val methods = runCatching { cls.declaredMethods.map { it.name }.distinct().sorted().take(MAX_ITEMS) }.getOrDefault(emptyList())
            val instance = runCatching { cls.getMethod("getInstance").invoke(null) }.getOrNull()
                ?: runCatching { cls.getMethod("getInstance", Context::class.java).invoke(null, context) }.getOrNull()
            if (instance == null) {
                append("sin instancia; métodos=").append(methods.joinToString())
                return@buildString
            }
            fun read(name: String) = runCatching { cls.getMethod(name).invoke(instance) }.getOrNull()
            append("fuente=").append(read("getSourcePackage") ?: "-")
            append("; música=").append(read("getMusicInfo")?.let(::fields) ?: "-")
            append("; métodos=").append(methods.joinToString())
        }
    }

    /** Los campos de un objeto: números y sí/no tal cual; un texto solo dice si trae algo. */
    private fun fields(o: Any): String = o.javaClass.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }
        .take(MAX_ITEMS)
        .joinToString(prefix = "${o.javaClass.simpleName}{", postfix = "}") { f ->
            val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
            f.name + "=" + when (v) {
                null -> "null"
                is Number, is Boolean -> v.toString()
                is CharSequence -> if (v.isBlank()) "vacío" else "sí"
                else -> v.javaClass.simpleName
            }
        }
}
