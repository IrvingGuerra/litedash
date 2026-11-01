package com.irvingguerra.litedash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Favorite(val name: String, val number: String)

/** Contactos con estrella que tienen teléfono (los primeros [limit]). Sin historial: Play no lo permite. */
private suspend fun favorites(context: Context, limit: Int = 6): List<Favorite> = withContext(Dispatchers.IO) {
    val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    runCatching {
        context.contentResolver.query(phone, columns, "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1", null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use { c ->
            buildList<Favorite> {
                while (c.moveToNext() && size < limit) {
                    val f = Favorite(c.getString(0) ?: continue, c.getString(1) ?: continue)
                    if (none { it.name == f.name }) add(f)
                }
            }
        }
    }.getOrNull().orEmpty()
}

/** Pieza Favoritos: tus contactos con estrella; un toque los marca (abre el teléfono con el número). */
@Composable
fun FavoritesTile() {
    val c = LocalColors.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        return ShortcutTile(Icons.phone, stringResource(R.string.tile_favorites), stringResource(R.string.weather_enable)) {
            ask.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    var list by remember { mutableStateOf<List<Favorite>?>(null) }
    LaunchedEffect(Unit) { list = favorites(context) }
    val people = list ?: return
    if (people.isEmpty()) return ShortcutTile(Icons.phone, stringResource(R.string.tile_favorites), stringResource(R.string.favorites_empty)) { openDialer(context) }
    Card(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val fit = (maxWidth / 84.dp).toInt().coerceAtLeast(1)
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                for (p in people.take(fit)) {
                    Column(
                        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:${p.number}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                            Label(p.name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString(""), Typography.subtitle, c.accent, maxLines = 1)
                        }
                        Label(p.name.substringBefore(' '), Typography.small, maxLines = 1)
                    }
                }
            }
        }
    }
}
