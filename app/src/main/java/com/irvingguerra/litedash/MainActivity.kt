package com.irvingguerra.litedash

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
import com.irvingguerra.litedash.feature.apps.RepositorioApps
import com.irvingguerra.litedash.feature.auto.RepositorioAutos
import com.irvingguerra.litedash.feature.musica.RepositorioMusica
import com.irvingguerra.litedash.feature.tablero.AlmacenTablero
import com.irvingguerra.litedash.feature.tablero.EstadoTablero

class MainActivity : ComponentActivity() {
    private val dependencias by lazy {
        Dependencias(
            apps = RepositorioApps(applicationContext),
            musica = RepositorioMusica(applicationContext),
            prefs = Preferencias(applicationContext),
            tablero = EstadoTablero(AlmacenTablero(applicationContext)),
            autos = RepositorioAutos(applicationContext, BuildConfig.URL_CATALOGO),
        )
    }

    // Sube cada vez que se vuelve a la app (p. ej. después de dar un permiso en Ajustes).
    private val revision = mutableIntStateOf(0)

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
                Inicio(d, revision.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        revision.intValue++
    }
}
