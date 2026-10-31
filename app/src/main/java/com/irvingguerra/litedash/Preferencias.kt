package com.irvingguerra.litedash

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ModoTema { OSCURO, CLARO, SISTEMA }

/** Idioma de la app: "" = el del sistema. Se lee antes de crear la pantalla. */
object Idioma {
    fun leer(contexto: Context) = contexto.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getString("idioma", "") ?: ""
}

/** [base] con el idioma elegido en Ajustes. */
fun contextoConIdioma(base: Context): Context {
    val idioma = Idioma.leer(base)
    if (idioma.isBlank()) return base
    val config = android.content.res.Configuration(base.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(idioma)) }
    return base.createConfigurationContext(config)
}

/** Ajustes del usuario: cada cambio se guarda al momento y redibuja lo que lo usa. */
class Preferencias(contexto: Context) {
    private val p = contexto.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    private var _tema by mutableStateOf(runCatching { ModoTema.valueOf(p.getString("tema", null)!!) }.getOrDefault(ModoTema.OSCURO))
    var tema: ModoTema
        get() = _tema
        set(v) { _tema = v; p.edit { putString("tema", v.name) } }

    private var _escala by mutableFloatStateOf(p.getFloat("escala", 1f))
    var escala: Float
        get() = _escala
        set(v) { _escala = v; p.edit { putFloat("escala", v) } }

    private var _nombreAuto by mutableStateOf(p.getString("nombre_auto", null))
    /** null = todavía no lo nombra (se muestra "Mi auto"). */
    var nombreAuto: String?
        get() = _nombreAuto
        set(v) { _nombreAuto = v; p.edit { putString("nombre_auto", v) } }

    private var _colorAuto by mutableIntStateOf(p.getInt("color_auto", 0))
    var colorAuto: Int
        get() = _colorAuto
        set(v) { _colorAuto = v; p.edit { putInt("color_auto", v) } }

    private var _modeloAuto by mutableStateOf(p.getString("modelo_auto", null))
    /** Id del auto del catálogo que se muestra; null = el sedán genérico. */
    var modeloAuto: String?
        get() = _modeloAuto
        set(v) { _modeloAuto = v; p.edit { putString("modelo_auto", v) } }

    private var _bienvenida by mutableStateOf(p.getBoolean("bienvenida_vista", false))
    var bienvenidaVista: Boolean
        get() = _bienvenida
        set(v) { _bienvenida = v; p.edit { putBoolean("bienvenida_vista", v) } }

    private var _pasoBienvenida by mutableIntStateOf(p.getInt("paso_bienvenida", 0))
    /** Se guarda porque Android reinicia la app al volverla pantalla de inicio. */
    var pasoBienvenida: Int
        get() = _pasoBienvenida
        set(v) { _pasoBienvenida = v; p.edit { putInt("paso_bienvenida", v) } }

    private var _noPedirInicio by mutableStateOf(p.getBoolean("no_pedir_inicio", false))
    /** El usuario pidió no volver a ver el aviso de "hazlo tu pantalla de inicio". */
    var noPedirInicio: Boolean
        get() = _noPedirInicio
        set(v) { _noPedirInicio = v; p.edit { putBoolean("no_pedir_inicio", v) } }

    private var _idioma by mutableStateOf(p.getString("idioma", "") ?: "")
    /** "" (sistema), "es" o "en". Cambiarlo recrea la actividad. */
    var idioma: String
        get() = _idioma
        set(v) { _idioma = v; p.edit(commit = true) { putString("idioma", v) } }

    private var _ocultas by mutableStateOf(p.getStringSet("ocultas", emptySet())!!.toSet())
    /** Apps que no salen en el cajón ([com.irvingguerra.litedash.feature.apps.App.clave]). */
    var ocultas: Set<String>
        get() = _ocultas
        set(v) { _ocultas = v; p.edit { putStringSet("ocultas", v) } }

    private var _dock by mutableStateOf(p.getString("dock", null)?.split('\n')?.filter { it.isNotBlank() })
    /** Apps fijas en el dock ([com.irvingguerra.litedash.feature.apps.App.clave]); null = las sugeridas. */
    var dock: List<String>?
        get() = _dock
        set(v) { _dock = v; p.edit { putString("dock", v?.joinToString("\n")) } }

    private var _appMapa by mutableStateOf(p.getString("app_mapa", null))
    /** App de navegación elegida ([com.irvingguerra.litedash.feature.apps.App.clave]); null = la primera instalada. */
    var appMapa: String?
        get() = _appMapa
        set(v) { _appMapa = v; p.edit { putString("app_mapa", v) } }
}
