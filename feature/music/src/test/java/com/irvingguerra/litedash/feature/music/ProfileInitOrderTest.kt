package com.irvingguerra.litedash.feature.music

import java.io.File
import java.net.URLClassLoader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Los perfiles de Bluetooth ([FactoryBluetoothKind]) y [HeadUnit] se nombran uno al otro al iniciarse: sin importar cuál
 * toque primero la app (un aviso que llega antes que nada, la calibración…), las listas deben salir completas y sin
 * nulos. Cada caso carga las clases de nuevo, en un cargador aparte, empezando por una distinta.
 */
class ProfileInitOrderTest {
    private val pkg = "com.irvingguerra.litedash.feature.music"

    private fun freshLoader() = URLClassLoader(
        System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }.toTypedArray(),
        null,
    )

    private fun brandApps(loader: ClassLoader): List<*> =
        Class.forName("$pkg.FactoryBluetoothKt", true, loader).getMethod("getBRAND_BLUETOOTH").invoke(null) as List<*>

    @Test
    fun anyClassCanBeTheFirstToStart() {
        for (first in listOf("BroadcastMeta", "FactoryBluetoothKind", "AutochipsBluetooth", "GenericBluetooth", "AndroidAvrcpBluetooth", "HeadUnit", "FactoryBluetoothKt")) {
            val loader = freshLoader()
            Class.forName("$pkg.$first", true, loader)
            if (first == "BroadcastMeta") {
                // Un aviso de Autochips como lo primero que llega.
                val companion = Class.forName("$pkg.BroadcastMeta", true, loader).getField("Companion").get(null)
                companion.javaClass.getMethod("parse", String::class.java, Map::class.java).invoke(
                    companion, "com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility.action.ACTION_PLAYBACK_DATA_UPDATE",
                    mapOf("com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility.extra.PLAYBACK_STATUS" to 1),
                )
            }
            val apps = brandApps(loader)
            assertEquals(first, 14, apps.size)
            assertEquals(first, 0, apps.count { it == null })
        }
    }
}
