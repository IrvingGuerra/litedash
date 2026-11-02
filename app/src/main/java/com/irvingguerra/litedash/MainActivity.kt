package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import com.irvingguerra.litedash.core.ui.LiteDashTheme

class MainActivity : ComponentActivity() {
    private val dependencies get() = (application as LiteDashApp).dependencies

    // Sube cada vez que se vuelve a la app (p. ej. después de dar un permiso en Ajustes).
    private val revision = mutableIntStateOf(0)
    // Sube al tocar el botón de inicio estando ya en LiteDash: cierra lo que esté abierto.
    private val homePressed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Es la pantalla de inicio: "atrás" no la cierra (los paneles sí se cierran con atrás).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            val d = dependencies
            // Una sola cuenta de la noche (sol o sensor de luz) para el tema y el brillo.
            val night = rememberIsNight(d.prefs.nightSource, needed = d.prefs.theme == ThemeMode.SUN || d.prefs.brightnessBySun)
            val dark = when (d.prefs.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.SUN -> night
            }
            // Íconos de la barra del sistema claros u oscuros según el tema.
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(style, style)
            }
            ApplyBrightness(d.prefs, night, revision.intValue)
            LiteDashTheme(dark = dark, scale = d.prefs.scale) {
                Home(d, revision.intValue, homePressed.intValue)
            }
        }
    }

    // Idioma elegido en Ajustes (o el del sistema si no eligió).
    override fun attachBaseContext(base: Context) = super.attachBaseContext(contextWithLanguage(base))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePressed.intValue++
    }

    // Mandos al volante: llegan como teclas normales de Android mientras LiteDash está al frente.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        dependencies.wheel.onKey(event, dependencies.music) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        dependencies.wheel.onKey(event, dependencies.music) || super.onKeyUp(keyCode, event)

    override fun onResume() {
        super.onResume()
        revision.intValue++
    }
}
