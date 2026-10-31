package com.irvingguerra.litedash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import com.irvingguerra.litedash.core.ui.LiteDashTema
import com.irvingguerra.litedash.feature.apps.RepositorioApps

class MainActivity : ComponentActivity() {
    private val repoApps by lazy { RepositorioApps(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Es la pantalla de inicio: "atrás" no la cierra.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            LiteDashTema {
                Inicio(repoApps)
            }
        }
    }
}
