package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import com.irvingguerra.litedash.core.ui.LiteDashTema

class MainActivity : ComponentActivity() {
    private val dependencias get() = (application as LiteDashApp).dependencias

    // Sube cada vez que se vuelve a la app (p. ej. después de dar un permiso en Ajustes).
    private val revision = mutableIntStateOf(0)
    // Sube al tocar el botón de inicio estando ya en LiteDash: cierra lo que esté abierto.
    private val inicioTocado = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Es la pantalla de inicio: "atrás" no la cierra (los paneles sí se cierran con atrás).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            val d = dependencias
            val oscuro = when (d.prefs.tema) {
                ModoTema.OSCURO -> true
                ModoTema.CLARO -> false
                ModoTema.SISTEMA -> isSystemInDarkTheme()
            }
            // Íconos de la barra del sistema claros u oscuros según el tema.
            LaunchedEffect(oscuro) {
                val transparente = android.graphics.Color.TRANSPARENT
                val estilo = if (oscuro) SystemBarStyle.dark(transparente) else SystemBarStyle.light(transparente, transparente)
                enableEdgeToEdge(estilo, estilo)
            }
            LiteDashTema(oscuro = oscuro, escala = d.prefs.escala) {
                Inicio(d, revision.intValue, inicioTocado.intValue)
            }
        }
    }

    // Idioma elegido en Ajustes (o el del sistema si no eligió).
    override fun attachBaseContext(base: Context) = super.attachBaseContext(contextoConIdioma(base))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) inicioTocado.intValue++
    }

    override fun onResume() {
        super.onResume()
        revision.intValue++
    }
}
