package com.irvingguerra.litedash.feature.music

import com.irvingguerra.litedash.feature.music.Channel.OPEN
import com.irvingguerra.litedash.feature.music.Channel.OWN
import com.irvingguerra.litedash.feature.music.Channel.PHONE
import com.irvingguerra.litedash.feature.music.Channel.PROFILE
import com.irvingguerra.litedash.feature.music.Channel.RECEIVER
import com.irvingguerra.litedash.feature.music.Channel.SERVICE
import com.irvingguerra.litedash.feature.music.Channel.SESSION
import com.irvingguerra.litedash.feature.music.Channel.SESSION_KEY
import com.irvingguerra.litedash.feature.music.Channel.SYSTEM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las decisiones del motor (MusicRules.kt) con tablas de casos, incluidos los de reportes reales. */
class MusicRulesTest {

    // —— Play: a quién y por dónde ——

    @Test
    fun playRouteTable() {
        val cases = listOf(
            // Algo a la vista que obedece (Spotify en pausa): su sesión.
            "música, sesión que obedece" to (Target(radio = false, shown = true, obeys = true) to Plan(listOf(SESSION))),
            // Bluetooth de chip propio a ciegas (TopWay `com.tw.bt` sin sesión, Lancer de Irving): tecla explícita, al final el sistema.
            "música a ciegas (TopWay com.tw.bt)" to (Target(radio = false, shown = true) to Plan(listOf(RECEIVER, SESSION_KEY, SYSTEM))),
            // Nada a la vista: se despierta (receptor, sesión y además su servicio; sin nada, el sistema).
            "música dormida" to (Target(radio = false) to Plan(listOf(RECEIVER, SESSION, SERVICE, SYSTEM), wake = true, serviceToo = true)),
            "música sin app" to (Target(radio = false, app = false) to Plan(listOf(SYSTEM))),
            "Archivos" to (Target(radio = false, own = true) to Plan(listOf(OWN))),
            // La HCN: perfil antes que su receptor (con la app cerrada no le hace caso a la tecla).
            "radio HCN con perfil" to (Target(radio = true, profile = true) to Plan(listOf(PROFILE), wake = true)),
            "radio con perfil y sesión a la vista" to (Target(radio = true, shown = true, sessionShown = true, profile = true) to Plan(listOf(SESSION))),
            // Radio sin receptor ni sesión ni perfil (TopWay sin enlace, NWD sin servicio): al final se abre (calibración).
            "radio sin control" to (Target(radio = true) to Plan(listOf(RECEIVER, SESSION, SERVICE, OPEN), wake = true, serviceToo = true)),
            "radio a ciegas" to (Target(radio = true, shown = true) to Plan(listOf(RECEIVER, SESSION_KEY, SERVICE, OPEN))),
            "radio sin app" to (Target(radio = true, app = false) to Plan(emptyList())),
            "radio por internet" to (Target(radio = true, own = true) to Plan(listOf(OWN))),
        )
        for ((name, case) in cases) assertEquals(name, case.second, playPlan(case.first))
    }

    @Test
    fun phoneGoesFirstAndFallsBackToTheUsualRoute() {
        val plan = playPlan(Target(radio = false, phone = true, shown = true, obeys = true))
        assertEquals(listOf(PHONE, SESSION), plan.route)
        assertEquals(listOf(PHONE, RECEIVER, SESSION_KEY, SYSTEM), pausePlan(Target(radio = false, phone = true, shown = true)).route)
        assertEquals(listOf(PHONE, RECEIVER, SESSION_KEY, SYSTEM), skipPlan(Target(radio = false, phone = true)).route)
    }

    @Test
    fun pauseNeverWakesAnything() {
        val cases = listOf(
            Target(radio = true, shown = true, profile = true) to listOf(PROFILE),
            Target(radio = false, shown = true, obeys = true) to listOf(SESSION),
            Target(radio = false, shown = true) to listOf(RECEIVER, SESSION_KEY, SYSTEM),
            // La radio sin app: nada (antes una pausa fallida terminaba en play).
            Target(radio = true, shown = true, app = false) to emptyList(),
            Target(radio = true, shown = true, own = true) to listOf(SESSION),
        )
        for ((t, route) in cases) {
            val plan = pausePlan(t)
            assertEquals(t.toString(), route, plan.route)
            assertFalse(SERVICE in plan.route || OPEN in plan.route || plan.wake)
        }
    }

    @Test
    fun skipTable() {
        assertEquals(listOf(PROFILE), skipPlan(Target(radio = true, profile = true, obeys = true)).route)
        assertEquals(listOf(SESSION), skipPlan(Target(radio = false, obeys = true)).route)
        // La FM sin perfil: la tecla (siguiente es lo que la app haga con ella), nunca al sistema.
        assertEquals(listOf(RECEIVER, SESSION_KEY), skipPlan(Target(radio = true)).route)
        assertEquals(listOf(RECEIVER, SESSION_KEY, SYSTEM), skipPlan(Target(radio = false)).route)
    }

    @Test
    fun whatIsAssumedAfterPlay() {
        val wake = playPlan(Target(radio = false))
        val blind = playPlan(Target(radio = false, shown = true))
        // Despertar: la de la pantalla (no avisa) se da por sonando; una normal con servicio, no (su sesión dirá).
        assertTrue(assumesPlay(wake, RECEIVER, source = true, hasService = true))
        assertFalse(assumesPlay(wake, RECEIVER, source = false, hasService = true))
        assertTrue(assumesPlay(wake, SESSION, source = false, hasService = false))
        assertFalse(assumesPlay(wake, SYSTEM, source = true, hasService = false))
        assertFalse(assumesPlay(wake, null, source = true, hasService = false))
        // A ciegas: si la tecla llegó.
        assertTrue(assumesPlay(blind, SYSTEM, source = false, hasService = false))
        assertTrue(assumesPlay(blind, SESSION_KEY, source = true, hasService = true))
        // Abrir al acabarse el plazo: solo la de la pantalla cuando el play se fue al sistema.
        assertTrue(opensAfterWake(wake, SYSTEM, source = true))
        assertFalse(opensAfterWake(wake, SYSTEM, source = false))
        assertFalse(opensAfterWake(wake, RECEIVER, source = true))
        assertFalse(opensAfterWake(blind, SYSTEM, source = true))
    }

    // —— Callar la radio (issue #17 y el riesgo de la 1.4.162) ——

    @Test
    fun radioIsQuietedOnlyWhenItIsKnownToSound() {
        val quietAt = 100L
        val cases = listOf(
            // 1.4.162: FM con receptor nunca tocada, sin perfil ni señal → antes PAUSE a ciegas (la prendía). Ahora nada.
            "sin ninguna señal" to (RadioSignals() to false),
            // HCN/TopWay antes del primer aviso (perfil sin decir): sin otra señal, nada.
            "sin perfil y sin decir" to (RadioSignals(profileOn = null) to false),
            "perfil abierto sin decir" to (RadioSignals(profileOpen = true) to true),
            "perfil abierto dice que no" to (RadioSignals(profileOn = false, profileOpen = true) to false),
            "perfil dice que no (aunque su sesión diga otra cosa)" to (RadioSignals(profileOn = false, sessionAt = 500) to false),
            // TopWay con perfil que dice que suena.
            "perfil dice que sí" to (RadioSignals(profileOn = true) to true),
            // Essgoo #17: la FM con sesión sonando.
            "su sesión suena" to (RadioSignals(sessionAt = 500) to true),
            "su aviso dice que suena" to (RadioSignals(heardAt = 500) to true),
            "LiteDash la prendió" to (RadioSignals(claimedAt = 500) to true),
            // Cada señal vale una vez: la de antes de la última vez que se calló ya no.
            "señal vieja" to (RadioSignals(sessionAt = 50, heardAt = 60, claimedAt = 90) to false),
        )
        for ((name, case) in cases) assertEquals(name, case.second, radioSounds(case.first, quietAt))
    }

    @Test
    fun aPlayThroughTwoPathsQuietsOnce() {
        // El play de la USB: [MusicRepository.play] no calla (lo hace el reproductor al empezar); si dos caminos llegaran, la
        // señal ya se gastó con la primera pausa.
        val signals = RadioSignals(sessionAt = 200)
        assertTrue(radioSounds(signals, quietAt = 0))
        assertFalse(radioSounds(signals, quietAt = 300))
    }

    @Test
    fun howTheRadioIsQuieted() {
        assertEquals(Quiet.PROFILE, quietBy(profileOpen = true, receiver = true, session = true, profileCanOpen = true))
        assertEquals(Quiet.RECEIVER, quietBy(profileOpen = false, receiver = true, session = true, profileCanOpen = true))
        assertEquals(Quiet.SESSION, quietBy(profileOpen = false, receiver = false, session = true, profileCanOpen = true))
        // Radio sin receptor ni sesión que LiteDash prendió y la pieza Radio no está a la vista: su perfil solo para callarla.
        assertEquals(Quiet.PROFILE_LATER, quietBy(profileOpen = false, receiver = false, session = false, profileCanOpen = true))
        assertNull(quietBy(profileOpen = false, receiver = false, session = false, profileCanOpen = false))
    }

    @Test
    fun whenTheRadioIsConsidered() {
        assertTrue(quietsFor("com.tw.radio", internet = false, target = "com.tw.bt"))
        assertFalse(quietsFor(null, internet = false, target = "com.spotify.music"))
        assertFalse(quietsFor("com.tw.radio", internet = true, target = "com.spotify.music"))
        // Una sola app para radio y Bluetooth: la pausa le llegaría a la que se acaba de prender.
        assertFalse(quietsFor("com.example.media", internet = false, target = "com.example.media"))
    }

    @Test
    fun profilePlayConfirmation() {
        // TopWay/HCN: la radio dice que tiene el audio desde que se pidió.
        assertTrue(profileConfirmed(audioAt = 1_000, askedAt = 900, confirmsByAudio = false, androidAudio = false))
        assertFalse(profileConfirmed(audioAt = 800, askedAt = 900, confirmsByAudio = false, androidAudio = true))
        // NWD sin la clave de la fuente: solo por el audio de Android.
        assertTrue(profileConfirmed(audioAt = 0, askedAt = 900, confirmsByAudio = true, androidAudio = true))
        assertFalse(profileConfirmed(audioAt = 0, askedAt = 900, confirmsByAudio = true, androidAudio = false))
    }

    // —— ¿Suena el Bluetooth? ——

    @Test
    fun bluetoothSoundsTable() {
        assertNull(bluetoothSounds(phonePlaying = false, androidAudio = true, othersPlaying = false))
        assertEquals(true, bluetoothSounds(phonePlaying = true, androidAudio = true, othersPlaying = false))
        assertEquals(false, bluetoothSounds(phonePlaying = true, androidAudio = false, othersPlaying = false))
        assertEquals(false, bluetoothSounds(phonePlaying = true, androidAudio = true, othersPlaying = true))
    }

    @Test
    fun hcnWithAndroidBluetoothCountsAsTheBluetooth() {
        // Reporte E3F104 (mt8163 HCN/autochips): la sesión de com.android.bluetooth sonando NO es "otra app".
        val group = bluetoothGroup(avrcp = null, brand = "com.autochips.bluetooth", linked = null)
        assertTrue("com.android.bluetooth" in group)
        assertTrue("com.autochips.bluetooth" in group)
        assertTrue(HeadUnit.TOPWAY_BLUETOOTH in group)
    }

    // —— Qué sesión toma cada pieza ——

    private fun v(pkg: String, titled: Boolean = false, playing: Boolean = false, busy: Boolean = playing) = SessionView(pkg, titled, playing, busy)

    private fun pick(list: List<SessionView>, fixed: String? = null, anyApp: Boolean = true) =
        pickSession(list, { it }, own = "own", fixed = fixed, anyApp = anyApp, avrcp = "com.android.bluetooth")

    @Test
    fun ownPlayerPlayingAlwaysWins() {
        val own = v("own", busy = true)
        assertEquals(own, pick(listOf(v("com.tw.bt", titled = true, playing = true), own), fixed = "com.tw.bt"))
    }

    @Test
    fun topWayEmptySessionsArePassedOver() {
        // `com.tw.bt` acumula sesiones vacías: gana la que suena con canción.
        val good = v("com.tw.bt", titled = true, playing = true)
        val list = listOf(v("com.tw.bt"), v("com.tw.bt", playing = true), good, v("com.spotify.music", titled = true, playing = true))
        assertEquals(good, pick(list, fixed = "com.tw.bt"))
        assertEquals(list[1], pick(list.filter { !it.titled }, fixed = "com.tw.bt"))
        assertEquals(list[0], pick(listOf(list[0]), fixed = "com.tw.bt"))
    }

    @Test
    fun anyAppPrefersSoundingWithSongAndSkipsEmptyAvrcp() {
        val spotify = v("com.spotify.music", titled = true)
        val avrcpEmpty = v("com.android.bluetooth")
        assertEquals(spotify, pick(listOf(avrcpEmpty, spotify)))
        assertNull(pick(listOf(avrcpEmpty)))
        val sounding = v("com.example.radio", playing = true)
        assertEquals(sounding, pick(listOf(spotify, sounding)))
        // Archivos/Bluetooth sin app fija no muestran otras.
        assertNull(pick(listOf(spotify), anyApp = false))
    }

    // —— El celular con LiteDash Connect ——

    @Test
    fun phoneHoldsBluetoothWhilePlayingAndReturnsLater() {
        var s = PhoneState()
        // Llega en silencio: nada se toca.
        var step = phoneStep(s, connected = true, playing = false, bluetooth = true, mode = MusicMode.APP, held = false, recipeApplied = false)
        assertFalse(step.hold || step.started)
        s = step.state
        // Empieza a sonar por Bluetooth: se presta el método.
        step = phoneStep(s, connected = true, playing = true, bluetooth = true, mode = MusicMode.APP, held = false, recipeApplied = false)
        assertTrue(step.hold)
        assertTrue(step.started)
        s = step.state
        // Sigue sonando con la receta ya puesta: no vuelve a "empezar".
        step = phoneStep(s, connected = true, playing = true, bluetooth = true, mode = MusicMode.BLUETOOTH, held = true, recipeApplied = true)
        assertFalse(step.hold || step.started)
        // Se va con algo prestado: se regresa más tarde.
        step = phoneStep(step.state, connected = false, playing = false, bluetooth = false, mode = MusicMode.BLUETOOTH, held = true, recipeApplied = true)
        assertTrue(step.leaveLater)
        assertEquals(PhoneState(), step.state)
    }

    @Test
    fun userChoiceWinsUntilThePhoneLeaves() {
        val here = PhoneState(here = true, userChose = true)
        val step = phoneStep(here, connected = true, playing = true, bluetooth = true, mode = MusicMode.FILES, held = false, recipeApplied = false)
        assertFalse(step.hold)
        // Con Archivos elegido no empieza nada del Bluetooth.
        assertFalse(step.started)
        // Al volver a llegar, se olvida lo elegido.
        val back = phoneStep(PhoneState(userChose = true), connected = true, playing = true, bluetooth = true, mode = MusicMode.FILES, held = false, recipeApplied = false)
        assertTrue(back.hold)
    }

    @Test
    fun phoneOnItsOwnSpeakerHoldsButDoesNotCalibrate() {
        val step = phoneStep(PhoneState(), connected = true, playing = true, bluetooth = false, mode = MusicMode.APP, held = false, recipeApplied = false)
        assertTrue(step.hold)
        assertFalse(step.started)
        assertFalse(phoneStep(PhoneState(), connected = false, playing = false, bluetooth = false, mode = MusicMode.APP, held = false, recipeApplied = false).leaveLater)
    }

    // —— Abrir la app de Bluetooth, avance y carátula ——

    @Test
    fun essgooOpensTheBtMusicActivity() {
        // Issue #13 (Essgoo): "Bluetooth" y "BT Music" en la misma app.
        val classes = listOf("com.example.bt.MainActivity", "com.example.bt.Player")
        val labels = listOf("Bluetooth", "BT Music")
        assertEquals(1, pickMusicActivity(classes) { labels[it] })
        assertEquals(0, pickMusicActivity(listOf("com.syu.bt.act.BtMusicActivity", "com.syu.bt.Main")) { "" })
        assertEquals(-1, pickMusicActivity(listOf("a.Main", "a.Settings")) { "Bluetooth" })
    }

    @Test
    fun positionAdvancesButNotPastTheEnd() {
        val song = Song("t", "a", "app", null, playing = true, durationMs = 10_000, positionMs = 2_000, readAt = 1_000, speed = 1f, pkg = "p", seekable = true, actions = emptyList(), source = null, buffering = false, error = null)
        assertEquals(5_000, song.positionAt(4_000))
        assertEquals(10_000, song.positionAt(60_000))
        assertEquals(2_000, song.copy(playing = false).positionAt(60_000))
        assertEquals(59_000 + 2_000, song.copy(durationMs = 0).positionAt(60_000))
    }

    @Test
    fun coverKeyIgnoresCaseButNotSigns() {
        assertEquals(coverKey("Back In Black", "AC/DC"), coverKey("back in black", "ac/dc"))
        assertFalse(coverKey("Back In Black", "AC/DC") == coverKey("Back In Black", "ACDC"))
        assertNull(coverKey("", ""))
    }
}
