package com.irvingguerra.litedash

import androidx.compose.runtime.Composable

/**
 * El actualizador de LiteDash. Solo existe en la versión de GitHub (`app/src/github`): Play prohíbe que una app se
 * actualice por fuera de la tienda, así que en la de Play [appUpdates] da null y no viaja ni su código ni sus textos.
 */
interface AppUpdates {
    /** Revisión en silencio al volver al inicio (se limita sola a una cada tanto). */
    suspend fun checkQuietly()

    /** Aviso del inicio cuando hay versión nueva que no se dejó para después; [onOpen] lleva a Ajustes. */
    @Composable
    fun Banner(onOpen: () -> Unit)

    /** La tarjeta de Ajustes → Acerca de y ayuda → Acerca de: buscar, bajar e instalar. */
    @Composable
    fun Card()

    /** Una línea para el reporte técnico (lo último que dijo el instalador de Android, tal cual). */
    fun describe(): String
}
