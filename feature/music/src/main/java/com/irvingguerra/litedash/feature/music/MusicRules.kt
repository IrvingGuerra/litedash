package com.irvingguerra.litedash.feature.music

/*
 * Las decisiones del motor de música, sin nada de Android para poder probarlas con tablas de casos: a quién se le
 * habla y por dónde (play, pausa, siguiente), si la radio de fábrica se calla, si el Bluetooth suena de verdad, qué
 * sesión toma cada pieza y qué pasa cuando el celular (LiteDash Connect) llega o se va. [MusicRepository] junta los
 * datos, pregunta aquí y hace lo que sale.
 */

/** Por dónde se le habla a una app de música (o a la radio de fábrica). */
internal enum class Channel(val label: String) {
    /** El celular por LiteDash Connect. */
    PHONE("celular"),
    /** El reproductor propio de LiteDash (Archivos o la radio por internet). */
    OWN("reproductor propio"),
    /** El perfil de la radio de fábrica de su marca ([FactoryRadio]). */
    PROFILE("perfil"),
    /** Los controles de su sesión de medios. */
    SESSION("sesión"),
    /** La tecla multimedia a su receptor del botón, como el volante. */
    RECEIVER("receptor"),
    /** La tecla multimedia a su sesión aunque esté vacía (las de fábrica la abren solo para el volante). */
    SESSION_KEY("tecla a su sesión"),
    /** Su servicio de música (MediaBrowserService), como Android Auto. */
    SERVICE("servicio"),
    /** La tecla por el sistema de audio: Android la lleva a la app de música que toca. */
    SYSTEM("sistema"),
    /** La radio sin control: la calibración elige su perfil o abrir su app y regresar. */
    OPEN("abrir su app"),
}

/** Lo que se sabe de la app a la que va una orden, para decidir el camino ([playPlan], [pausePlan], [skipPlan]). */
internal data class Target(
    /** La pieza Radio (si no, la pieza Música). */
    val radio: Boolean,
    /** Lo que se ve viene del celular (LiteDash Connect). */
    val phone: Boolean = false,
    /** La app es LiteDash (el reproductor propio). */
    val own: Boolean = false,
    /** Hay a qué app hablarle (la elegida, la enlazada, la que sonó o la que se ve). */
    val app: Boolean = true,
    /** La pieza muestra algo de esa app (en pausa o sonando). */
    val shown: Boolean = false,
    /** Su sesión a la vista obedece la orden: trae canción de verdad (no supuesta) y anuncia esa acción. */
    val obeys: Boolean = false,
    /** Hay una sesión suya a la vista (aunque no obedezca). */
    val sessionShown: Boolean = false,
    /** La radio tiene abierto el perfil de su marca. */
    val profile: Boolean = false,
    /** Es una app de la pantalla (Bluetooth o FM de fábrica: suenan por fuera de Android y no avisan); si no, una app de música normal. */
    val source: Boolean = false,
    /** Tiene una sesión que anuncia play aunque no se vea (la pieza no está a la vista o aún no la tomó). */
    val session: Boolean = false,
    /**
     * La app de Bluetooth de marca no tiene receptor, sesión ni servicio y el celular no está con LiteDash Connect: no hay
     * por dónde darle play (reporte E48170, TopWay `com.tw.bt`). La música se controla desde el celular.
     */
    val silent: Boolean = false,
)

/**
 * El camino de una orden: se intenta cada canal de [route] en orden y se queda en el primero que tiene a quién.
 * [wake] = se está despertando la app (no había nada a la vista): la pieza dice "Abriendo…" y, si no contesta, se
 * abre. [serviceToo] = si contestó su receptor o su sesión, también su servicio (varias apps de Bluetooth tienen
 * servicio pero no le hacen caso, y otras solo despiertan por él).
 */
internal data class Plan(val route: List<Channel>, val wake: Boolean = false, val serviceToo: Boolean = false)

/**
 * Play. El orden es el que funciona hoy en cada caso (aprendido de los reportes):
 * - Celular → por el enlace (si ya no hay enlace, lo de abajo). Con algo a la vista que obedece → su sesión. LiteDash →
 *   su reproductor. Música sin app → la tecla del sistema; radio sin app → nada (la pieza abre sus ajustes).
 * - La radio con perfil: su sesión si se ve una (la HCN con sesión), si no
 *   el perfil (sin abrir la app; la HCN tiene receptor pero con la app cerrada no le hace caso).
 * - A la vista pero "a ciegas" (Bluetooth y FM de fábrica sin sesión o con una vacía): como el volante pero explícito,
 *   la tecla de play a su receptor o a su sesión; la música, si no, al sistema. La radio nunca al sistema (llevaría a
 *   Spotify): si no hubo a quién, su servicio y al final abrirla.
 * - La app de Bluetooth de marca sin ningún canal ([Target.silent]): nada (la tecla del sistema podía prender otra app).
 * - Nada a la vista, app de la pantalla: se despierta. Su receptor, si no su sesión, y además su servicio; sin nada, la
 *   música al sistema y la radio a abrir su app.
 * - Nada a la vista, app de música normal: UNA sola orden (dos a la vez se cancelaban: play y pausa, "doble play"). Con
 *   sesión, su sesión; si no, se despierta por su servicio y, solo si no tiene, la tecla a su receptor; al final el sistema.
 */
internal fun playPlan(t: Target): Plan = when {
    t.phone -> phoneFirst(playPlan(t.copy(phone = false)))
    t.obeys -> Plan(listOf(Channel.SESSION))
    t.own -> Plan(listOf(Channel.OWN))
    !t.app -> Plan(if (t.radio) emptyList() else listOf(Channel.SYSTEM))
    t.radio && t.profile -> if (t.sessionShown) Plan(listOf(Channel.SESSION)) else Plan(listOf(Channel.PROFILE), wake = true)
    t.shown && t.radio -> Plan(listOf(Channel.RECEIVER, Channel.SESSION_KEY, Channel.SERVICE, Channel.OPEN))
    t.silent && !t.radio -> Plan(emptyList())
    t.shown -> Plan(listOf(Channel.RECEIVER, Channel.SESSION_KEY, Channel.SYSTEM))
    t.radio -> Plan(listOf(Channel.RECEIVER, Channel.SESSION, Channel.SERVICE, Channel.OPEN), wake = true, serviceToo = true)
    t.source -> Plan(listOf(Channel.RECEIVER, Channel.SESSION, Channel.SERVICE, Channel.SYSTEM), wake = true, serviceToo = true)
    t.session -> Plan(listOf(Channel.SESSION))
    else -> Plan(listOf(Channel.SERVICE, Channel.RECEIVER, Channel.SYSTEM), wake = true)
}

/**
 * Pausa: el celular por el enlace; la radio con perfil, el perfil (suelta el audio); lo que obedece, su sesión; si no,
 * la tecla de pausa a su receptor o a su sesión y, al final, al sistema (la radio solo si nada más suena: si no, la
 * pausa le llegaría a esa otra app; eso lo revisa quien la manda). Sin a quién no se hace nada (nunca un play).
 */
internal fun pausePlan(t: Target): Plan = when {
    t.phone -> phoneFirst(pausePlan(t.copy(phone = false)))
    t.radio && t.profile -> Plan(listOf(Channel.PROFILE))
    t.obeys || t.own -> Plan(listOf(Channel.SESSION))
    !t.app -> Plan(listOf(Channel.SYSTEM).takeUnless { t.radio }.orEmpty())
    // Como el play: la app de Bluetooth de marca sin canal no recibe nada y la tecla del sistema pausaba otra app.
    t.silent && !t.radio -> Plan(emptyList())
    else -> Plan(listOf(Channel.RECEIVER, Channel.SESSION_KEY, Channel.SYSTEM))
}

/**
 * Siguiente o anterior: el celular; la radio con perfil, el perfil; lo que obedece, su sesión; si no, la tecla a su
 * receptor o a su sesión y la música al sistema (en la FM, siguiente es lo que la app haga con la tecla).
 */
internal fun skipPlan(t: Target): Plan = when {
    t.phone -> phoneFirst(skipPlan(t.copy(phone = false)))
    t.radio && t.profile -> Plan(listOf(Channel.PROFILE))
    t.obeys || t.own -> Plan(listOf(Channel.SESSION))
    t.radio -> Plan(listOf(Channel.RECEIVER, Channel.SESSION_KEY))
    t.silent -> Plan(emptyList())
    else -> Plan(listOf(Channel.RECEIVER, Channel.SESSION_KEY, Channel.SYSTEM))
}

/**
 * ¿El botón de play/pausa manda play? El de la pantalla hace lo que muestra ([shownPlaying]: con ▮▮ pausa, con ▶ play);
 * el volante y la barra no ven el ícono ([shownPlaying] = null) y preguntan en el momento si suena ([soundsNow]).
 */
internal fun pressPlays(shownPlaying: Boolean?, soundsNow: () -> Boolean): Boolean = if (shownPlaying != null) !shownPlaying else !soundsNow()

/**
 * ¿Suena lo que el volante va a controlar, cuando lo que se ve no es de fiar? Manda la sesión de la app si dice algo
 * ([session]); si no, lo que LiteDash supone por lo último que le mandó ([shown], una canción «a ciegas»); y sin nada de
 * eso, si Android tiene audio ([audio]; null = no aplica), pero solo cuando no se pueden leer las sesiones
 * ([sessionsReadable] = hay acceso a los avisos): si se pueden y la app no tiene ninguna, el audio es de otra (la FM, la
 * voz del navegador) y tomarlo por ella mandaba pausa en vez de play.
 */
internal fun soundsNow(shown: Boolean?, session: Boolean?, audio: Boolean?, sessionsReadable: Boolean): Boolean =
    session ?: shown ?: (!sessionsReadable && audio == true)

/**
 * Tras despertar una app de música por su servicio ([Channel.SERVICE]), ¿va la tecla de respaldo a su receptor? Solo si
 * conectó pero no contestó al play: su sesión no suena ni carga ([busy]), Android no tiene audio ([audio]) y tiene
 * receptor ([receiver]). Va UNA vez y después, nunca junto con el play del servicio (eso era el doble play); si tampoco
 * así suena, [playCheck] la abre.
 */
internal fun serviceBackup(busy: Boolean, audio: Boolean, receiver: Boolean): Boolean = receiver && !busy && !audio

/**
 * La fuente del Bluetooth (TopWay, fuente 8) sigue pedida y no hace falta volver a escribirla: se pidió ([held]), nadie
 * se la quitó ([lost]) y el enlace TWUtil abrió de verdad ([linked]). Sin enlace (al arrancar el auto TWUtil aún no
 * contesta) el siguiente intento vuelve a abrir y pedirla.
 */
internal fun sourceStillHeld(held: Boolean, lost: Boolean, linked: Boolean): Boolean = held && !lost && linked

/**
 * ¿Se pospone abrir la app de fábrica encima de LiteDash? Con el auto en movimiento, siempre. Sin dato de velocidad
 * ([speedUnknown]: ningún widget usa el GPS ni hay OBD) también, salvo que la persona acabe de tocar el aviso «Controla
 * la música desde tu celular» o el ▶ de la pieza en la pantalla ([asked]): está viendo la pantalla y pide justo eso
 * (el volante y la barra no cuentan). Antes, en esas pantallas la receta se posponía para siempre y el Bluetooth nunca sonaba (reportes D677C4, 48FDB1).
 */
internal fun holdsForMotion(moving: Boolean, speedUnknown: Boolean, asked: Boolean): Boolean = moving && !(speedUnknown && asked)

/**
 * ¿Un play a esta app es música por Bluetooth (y toca su receta)? La app enlazada o reconocida como Bluetooth
 * ([source]) o, con el método Bluetooth, la sesión del Bluetooth de Android que toca por la app de marca sin sesión
 * ([standIn]; reportes E9C1EE, 9EB0D5 y D677C4, Essgoo): sin esto, el play por esa sesión no pedía la entrada de audio.
 */
internal fun playsBluetooth(source: SourceKind?, standIn: Boolean): Boolean = source == SourceKind.BLUETOOTH || standIn

/**
 * ¿Vale volver a pedir una receta pospuesta ([retryPostponed])? Con dato de velocidad sí (al detenerse, pasa). Sin dato y
 * sin el toque al aviso, no: seguiría pospuesta igual, y abrir una app sola con el auto quizá andando no se hace.
 */
internal fun retriesPostponed(speedUnknown: Boolean, asked: Boolean): Boolean = !speedUnknown || asked

/** El celular primero; si ya no hay enlace, el camino de siempre. */
private fun phoneFirst(rest: Plan) = rest.copy(route = listOf(Channel.PHONE) + rest.route)

/**
 * Tras un play que llegó por [via] a una app que no dice qué hace, ¿se da por hecho que suena ([MusicRepository.Lens.assume])?
 * A ciegas (estaba a la vista): si la tecla llegó (receptor, sesión o sistema). Al despertarla: si contestó su receptor
 * o su sesión y es la de la pantalla ([source]: Bluetooth o FM, no avisan) o no tiene servicio que diga más.
 */
internal fun assumesPlay(plan: Plan, via: Channel?, source: Boolean, hasService: Boolean): Boolean = when {
    via == null -> false
    plan.wake -> (via == Channel.RECEIVER || via == Channel.SESSION) && (source || !hasService)
    else -> via == Channel.RECEIVER || via == Channel.SESSION_KEY || via == Channel.SYSTEM
}

/**
 * Al despertar una app que no tenía receptor, sesión ni servicio (el play se fue al sistema), se abre al acabarse el plazo
 * si sigue sin nada, solo si es la FM de la pantalla ([source]). La app de Bluetooth de fábrica nunca: solo la abre su
 * receta [Recipes.OPEN_FACTORY] (una vez y detenido); las de música normales las abre su vigilante.
 */
internal fun opensAfterWake(plan: Plan, via: Channel?, source: SourceKind?): Boolean =
    plan.wake && via == Channel.SYSTEM && source == SourceKind.RADIO

/**
 * Las señales de que la radio de fábrica suena, cada una con cuándo se supo (reloj de arranque). [profileOn] es lo que
 * dice su perfil (null = sin perfil abierto o aún no dice).
 */
internal data class RadioSignals(
    val profileOn: Boolean? = null,
    /** Su perfil está abierto (aunque todavía no diga si suena). */
    val profileOpen: Boolean = false,
    /** Su sesión dice que suena (y cuándo lo dijo); null = no. */
    val sessionAt: Long? = null,
    /** Su aviso (broadcast) dijo que suena; null = no. */
    val heardAt: Long? = null,
    /** LiteDash le dio play y nadie le dio pausa después; null = no. */
    val claimedAt: Long? = null,
)

/**
 * ¿Se calla la radio? Su perfil manda (dice sí o no; abierto y sin decir, sí); sin él, solo si se SABE que suena: su sesión, su aviso o el play que le dio LiteDash,
 * siempre que sea más nuevo que la última vez que se calló ([quietAt]): cada señal sirve para callarla una sola vez, así un
 * play que pasa por dos caminos no le manda dos pausas (en radios que toman cualquier tecla como alternar, la segunda
 * la prendería). Sin ninguna señal, no se sabe: no se toca.
 */
internal fun radioSounds(s: RadioSignals, quietAt: Long): Boolean = when {
    s.profileOn == false -> false
    // Con el perfil abierto y sin decir (la prendieron desde su app o un botón antes de abrirlo) se calla igual, como
    // hasta la 1.4.162: soltar el audio por su perfil nunca la prende.
    s.profileOn == true || s.profileOpen -> true
    else -> listOfNotNull(s.sessionAt, s.heardAt, s.claimedAt).any { it > quietAt }
}

/** Por dónde se calla la radio. */
internal enum class Quiet(val label: String) {
    /** Su perfil abierto suelta el audio. */
    PROFILE("perfil"),
    /** La tecla de pausa a su receptor. */
    RECEIVER("tecla al receptor"),
    /** Pausa a su sesión. */
    SESSION("pausa a su sesión"),
    /** Su perfil, abriéndolo solo para soltar el audio (LiteDash la prendió y la pieza Radio no está a la vista). */
    PROFILE_LATER("perfil abierto para callarla"),
}

/**
 * Cómo se calla la radio que SABEMOS que suena ([radioSounds]): su perfil si está abierto; si no, la tecla a su receptor
 * y luego la pausa a su sesión (el orden de siempre); sin ninguno, abrir su perfil solo para eso ([profileCanOpen]). Nunca
 * por el sistema (la llevaría a la música) ni despertando una app detenida. Null = no hay cómo.
 */
internal fun quietBy(profileOpen: Boolean, receiver: Boolean, session: Boolean, profileCanOpen: Boolean): Quiet? = when {
    profileOpen -> Quiet.PROFILE
    receiver -> Quiet.RECEIVER
    session -> Quiet.SESSION
    profileCanOpen -> Quiet.PROFILE_LATER
    else -> null
}

/**
 * ¿Hay que callar la radio de fábrica porque va a sonar la música de [target]? Solo con la FM ([fm]) como radio escogida
 * (no la de internet) y si no es la misma app (pantallas con una sola app para radio y Bluetooth: la pausa le llegaría a
 * la que se acaba de prender).
 */
internal fun quietsFor(fm: String?, internet: Boolean, target: String?): Boolean = fm != null && !internet && target != fm

/** ¿El play de la radio por su perfil quedó confirmado? La radio dijo que tiene el audio desde [askedAt] o, si solo se puede saber por Android ([confirmsByAudio]), Android tiene audio y no es de otra app. */
internal fun profileConfirmed(audioAt: Long, askedAt: Long, confirmsByAudio: Boolean, androidAudio: Boolean): Boolean =
    audioAt >= askedAt || confirmsByAudio && androidAudio

/**
 * ¿El Bluetooth suena de verdad? Null = no se sabe si el celular suena (ya no, o solo se supone): esa revisión no cuenta.
 * Si suena: Android tiene audio y no es de una app fuera del grupo del Bluetooth.
 */
internal fun bluetoothSounds(phonePlaying: Boolean, androidAudio: Boolean, othersPlaying: Boolean): Boolean? =
    if (!phonePlaying) null else androidAudio && !othersPlaying

/**
 * El grupo del Bluetooth: todas las apps que tocan la música del celular (el AVRCP de Android, la de marca, la enlazada,
 * las de marca conocidas y las del Bluetooth de Android aunque no se haya hallado su servicio). Una sesión de cualquiera
 * de ellas sonando es el Bluetooth, no "otra app" (reporte E3F104: com.android.bluetooth sonando se tomaba por otra).
 */
internal fun bluetoothGroup(avrcp: String?, brand: String?, linked: String?): List<String> =
    listOfNotNull(avrcp, brand, linked) + HeadUnit.bluetoothApps + BluetoothAvrcp.ANDROID_PACKAGES

/** Una sesión de medios vista por la pieza: su app, si trae título, si suena y si suena o carga. */
internal data class SessionView(val pkg: String, val titled: Boolean, val playing: Boolean, val busy: Boolean)

/**
 * Qué sesión toma una pieza de [list] (ya filtrada a las suyas). El reproductor propio sonando o cargando siempre (lo
 * puso a sonar el usuario). Con app fija ([fixed]), solo de esa: la que suena con canción, si no la que tiene canción,
 * si no la que suena, si no cualquiera (las TopWay, `com.tw.bt`, acumulan sesiones vacías); si esa app no tiene
 * ninguna sesión, la de quien toca por ella ([standIns]). Sin app fija y si vale
 * cualquiera ([anyApp]): la que suena con canción, la que suena, la que tiene canción y, al final, cualquiera que no sea
 * la del Bluetooth de Android ([avrcp]: sin celular sonando es una sesión vacía).
 */
internal fun <T> pickSession(
    list: List<T>, view: (T) -> SessionView, own: String, fixed: String?, anyApp: Boolean, avrcp: String?,
    standIns: List<String> = emptyList(),
): T? {
    list.firstOrNull { view(it).let { v -> v.pkg == own && v.busy } }?.let { return it }
    if (fixed != null) {
        // La app fija sin sesión propia (la de Bluetooth de marca, que solo es una pantalla): vale la de quien toca por
        // ella ([standIns]: el Bluetooth de Android), si trae canción o suena. Reportes E9C1EE y 9EB0D5 (Essgoo).
        val mine = list.filter { view(it).pkg == fixed }.ifEmpty {
            list.filter { view(it).let { v -> v.pkg in standIns && (v.titled || v.busy) } }
        }
        return mine.firstOrNull { view(it).let { v -> v.titled && v.playing } }
            ?: mine.firstOrNull { view(it).titled }
            ?: mine.firstOrNull { view(it).playing }
            ?: mine.firstOrNull()
    }
    if (!anyApp) return null
    return list.firstOrNull { view(it).let { v -> v.titled && v.playing } }
        ?: list.firstOrNull { view(it).playing }
        ?: list.firstOrNull { view(it).titled }
        ?: list.firstOrNull { view(it).pkg != avrcp }
}

/** Lo que se sabe del celular con LiteDash Connect (antes, banderas sueltas). */
internal data class PhoneState(
    /** Está conectado. */
    val here: Boolean = false,
    /** El usuario eligió un método desde que llegó: eso gana y ya no se le presta el Bluetooth. */
    val userChose: Boolean = false,
    /** Su música suena y llega a la pantalla por Bluetooth (lo que la calibración puede hacer sonar). */
    val onBluetooth: Boolean = false,
)

/**
 * Qué hacer con un aviso del celular. [hold] = la música pasa a Bluetooth guardando el método de ahora; [started] = la
 * música del celular empieza a sonar por Bluetooth (callar la radio, su receta y la revisión); [leaveLater] = se fue y
 * hay algo prestado que regresar (se espera: Android cierra y reabre Connect a ratos).
 */
internal data class PhoneStep(val state: PhoneState, val hold: Boolean = false, val started: Boolean = false, val leaveLater: Boolean = false)

/**
 * La máquina del celular (Irving, 2026-11-11: sonaban a la vez el Spotify de la pantalla y el del celular). Mientras está
 * conectado ([connected]) y su música suena ([playing]) por Bluetooth o por su bocina, la música de la pantalla pasa a
 * Bluetooth (si el usuario no eligió otra cosa desde que llegó). Cada vez que empieza a sonar por Bluetooth
 * ([bluetooth]), o si aún no hay receta aplicada ([recipeApplied]), "empieza": la radio pudo quitarle la fuente. Cuando
 * se va, si había algo prestado ([held] o una receta), se regresa más tarde.
 */
internal fun phoneStep(
    s: PhoneState, connected: Boolean, playing: Boolean, bluetooth: Boolean, mode: MusicMode, held: Boolean, recipeApplied: Boolean,
): PhoneStep {
    if (!connected) return PhoneStep(PhoneState(), leaveLater = held || recipeApplied)
    val onBluetooth = playing && bluetooth
    val first = !s.here
    val state = PhoneState(here = true, userChose = s.userChose && !first, onBluetooth = onBluetooth)
    val hold = playing && !state.userChose && !held && mode != MusicMode.BLUETOOTH
    val modeAfter = if (hold) MusicMode.BLUETOOTH else mode
    val started = onBluetooth && modeAfter == MusicMode.BLUETOOTH && (!s.onBluetooth || !recipeApplied)
    return PhoneStep(state, hold = hold, started = started)
}
