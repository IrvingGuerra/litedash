package com.irvingguerra.litedash

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import java.util.Locale

/** El idioma principal de [config] (Android 6 no tiene lista de idiomas: ConfigurationCompat lo resuelve). */
fun primaryLocale(config: Configuration): Locale = ConfigurationCompat.getLocales(config)[0] ?: Locale.getDefault()

/** El idioma con el que se está dibujando la pantalla. */
@Composable
fun currentLocale(): Locale = primaryLocale(LocalConfiguration.current)
