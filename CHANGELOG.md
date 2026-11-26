# Cambios

Todos los cambios que nota quien usa LiteDash. Formato
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/), versiones
[SemVer](https://semver.org/lang/es/).

## [1.5.23] - 2026-11-25

Vidrio líquido de verdad, Diseños con fondo de día y de noche, y un inicio más fácil de deslizar.

### Nuevo
- **Vidrio líquido:** las tarjetas de vidrio dejan ver tu fondo nítido y lo doblan en la orilla, como un cristal
  grueso, con un destello fino en las esquinas. Funciona desde Android 6; en pantallas de poca memoria va sin doblar.
- **Diseños con fondo de día y de noche:** cada Diseño trae dos fondos hermanos y, con «Día y noche», LiteDash pone
  solo el que toca. Los fondos son nuevos, con color y formas que lucen el cristal.
- **Flecha / Auto también en el widget Mapa:** durante la guía el cambio sale en el widget (si mide al menos 240 dp) y
  lo que elijas se conserva al abrirlo en grande y al regresar.

### Cambiado
- **Elegir un Diseño pide confirmación** («¿Aplicar el diseño…?») en vez del botón Aplicar; Deshacer sigue ahí.
- **Deslizar cambia de página desde cualquier widget** (antes solo desde un hueco). El mapa y tu auto conservan su gesto.
- **Pista** lleva tu auto en la carretera; **Ruta**, el auto más grande; **Nube**, un fondo mucho más claro.
- Las miniaturas de los Diseños muestran el auto de fábrica.

### Quitado
- **Widget Pila:** quien tenía una conserva en su lugar el widget que estaba a la vista.
- **Acomodos:** quedan los Diseños. Tu inicio no cambia al actualizar.
- **«Filo de luz»** en Tarjetas: el cristal ya trae su propio brillo.
- **Deslizar para cambiar de canción** en los widgets Música y Radio (chocaba con cambiar de página); en grande sigue.
- **Tu auto sobre el mapa de fondo:** ahí siempre va la flecha.

## [1.5.22] - 2026-11-25

Dos cosas que funcionaban y se habían perdido: aprender las luces por el brillo y el sonido del Bluetooth de fábrica.

### Arreglado
- **Luces del auto por el brillo de la pantalla:** en muchas pantallas lo único que cambia al prender los faros es el
  brillo. «Aprender luces» lo vuelve a ofrecer (lo eliges tú, como «Brillo») y lo reconoce aunque el brillo no quede
  en el mismo número exacto. Desde la 1.4.162 decía «Ningún ajuste cambió» en esas pantallas.
- **Bluetooth de fábrica sin sonido** (pantallas sin GPS ni OBD en uso, como algunas Essgoo y Autochips): al tocar ▶
  en la pieza Música, LiteDash vuelve a pedirle a la pantalla que pase su sonido a Bluetooth. Antes solo lo hacía al
  tocar el aviso «Controla la música desde tu celular», que desaparecía en cuanto el celular empezaba a sonar.

## [1.5.21] - 2026-11-25

Vincula tu celular sin cámara: con un código de 6 dígitos.

### Nuevo
- **Vincular con código:** en Ajustes → Celular, debajo del código QR sale un código de 6 dígitos. Si tu celular no
  puede leer el QR (cámara rota o sin permiso), en LiteDash Connect toca «Escribir un código» y ponlo. Lo demás es
  igual: confirmas en el celular y listo. Necesita LiteDash Connect 0.7.0 o posterior.
- El código cambia cada 5 minutos, al usarse y si alguien falla tres veces: no se puede adivinar probando.

## [1.5.20] - 2026-11-25

Diseños: cambia todo tu inicio de un toque. Y la ruta con internet ya funciona en pantallas con Android 9 o anterior.

### Nuevo
- **Diseños:** 8 estilos completos (Grafito, Nube, Pista, Ruta, Columna, Neón, Arena y Bosque). Un toque pone acomodo,
  fondo, tema, tarjetas, colores, mapa y velocímetro, con **«Deshacer»**; después todo se edita como siempre. Están en
  Ajustes → Inicio y apariencia → Inicio y al acomodar. Todos gratis.
- **Widget Pila:** de 2 a 5 widgets en el mismo lugar; desliza o toca los puntitos para cambiar.
- **Bienvenida nueva** en tres pasos: tu Diseño, tu nombre y tu auto.
- **Filo de luz** a tu gusto en las tarjetas de vidrio: sin filo, delgado o marcado (Tarjetas).

### Cambiado
- **Vidrio nuevo:** las tarjetas de vidrio dejan ver mejor tu fondo, con reflejo y sombra, y de fábrica ya no llevan
  el filo blanco (si te gustaba, elige «Delgado» o «Marcado»).
- Las plantillas ahora se llaman **«Acomodos»**. Las instalaciones nuevas arrancan con el Diseño Grafito; si actualizas,
  tu inicio no cambia.

### Arreglado
- **Ruta con internet en Android 9 y anteriores:** decía «No se pudo trazar la ruta en línea» siempre, porque esas
  pantallas no pueden conectarse al servidor de rutas. Ahora el pedido pasa por litedash.dev, que solo lo reenvía.
- **Luces del auto:** aprenderlas ya no se pierde si tu pantalla cambia de modo al prender los faros, el estado se
  vuelve a leer al regresar a LiteDash y, si ningún ajuste cambió, te dice qué revisar y deja mandar un reporte.
- Un Diseño conserva tu tema «Día y noche» si ya lo usabas.
- **Bluetooth de fábrica:** tocar ▶ ya no abre la app de Bluetooth de la pantalla por su cuenta; solo el aviso
  «Controla la música desde tu celular».

## [1.5.19] - 2026-11-25

Mapas de México a la mitad de peso, listos para lo que viene (carriles, salidas, radares).

### Nuevo
- **Mapas más ligeros:** las calles ya se guardan una sola vez. México completo baja de 825 a ~400 MB (Estado de
  México de 56 a 31 MB) y se ve igual. Por ahora México; los demás países irán cambiando sin que hagas nada.
- **«Actualizar»** junto a cada estado que ya tenías bajado, con su tamaño nuevo: no es obligatorio y tu mapa de hoy
  sigue funcionando.
- El mapa, las calles y la búsqueda de un estado se bajan en una sola descarga, que se puede pausar y seguir.
- Calles privadas y de fraccionamientos también se dibujan.

### Arreglado
- **Elegir imagen (Android 11–12):** en pantallas donde la memoria no se deja leer, «Recientes» ya muestra tus fotos
  en vez de carpetas vacías.
- Una ruta a un fraccionamiento cerrado ya no dice «sin camino»: te lleva a la calle más cercana.
- Si se va la luz a media descarga de un mapa, no se pierde lo bajado; «Cancelar» responde siempre.

## [1.5.18] - 2026-11-24

Navegar ya no te saca de tu inicio: la guía vive en tu mapa, con tu música y tus widgets donde los dejaste.

### Nuevo
- **La navegación dentro del widget Mapa:** al tocar «Ir» no cambia la pantalla; tu widget empieza a guiarte con la
  vuelta, la llegada y «Terminar». Según su tamaño muestra más o menos; tócalo para verlo en grande.
- **Dos vistas, un botón:** en el mapa en grande (y en el widget grande) cambia entre **vista flecha** y **vista del
  auto**.
- **El mapa de fondo también navega**, con las tarjetas de la guía flotando en el hueco de tus widgets.
- **Aviso de la próxima vuelta** arriba cuando estás en otra página, en Ajustes o con la música en grande; tocarlo te
  regresa al mapa. El botón Navegar de la barra lleva un punto mientras hay guía.
- **Más cerca:** la vista flecha se acerca más a la calle cuando vas despacio.

### Cambiado
- Se quitó la pantalla de navegación aparte (y su reproductor): la música es tu widget de siempre.
- Con una guía en curso, «Navegar» (barra y volante) te lleva a ella; la lupa del mapa en grande cambia de destino.

### Arreglado
- «Ver ruta» ya no se queda abierta al llegar, y al terminar el mapa vuelve a centrarse en ti.

## [1.5.17] - 2026-11-24

Mapas nuevos: más limpios, con ruta aunque no hayas bajado tu estado y una navegación que se ve como debe.

### Nuevo
- **Ruta con internet sin bajar el mapa:** si no tienes el mapa de tu estado (o vas a otro), la ruta se traza en línea
  y la navegación te guía igual. Del lado de tu auto sale «baja el mapa» con su botón; al terminar, aparece tu auto con
  la franja azul sin dejar de navegar. Sin internet y sin mapa, te pide bajarlo como antes.
- **Colores nuevos del mapa** (azul pizarra de noche, claro de día) y tres opciones en Ajustes → Apps → Navegación:
  **De fábrica**, **Como mi interfaz** y **Color propio**.
- **La ruta «fluye»:** flechitas suaves avanzan sobre la línea hacia donde vas (se apaga en «Animar la ruta»; en
  pantallas lentas quedan quietas).
- **Navegación más cerca:** el mapa de la derecha va inclinado y a nivel de calle, se aleja con la velocidad y se
  aplana antes de cada vuelta. Muévelo con el dedo y «Seguirme» lo regresa.
- Glorietas con «toma la salida N».

### Cambiado
- Widget, mapa de fondo y mapa en grande siempre planos; sin edificios, más ligeros.
- El mapa en grande abre directo al buscador: sin brújula ni «Marcar aquí» (dejar presionado sigue marcando).
- Flecha de tu posición redondeada.

### Arreglado
- La lista del buscador se cierra al tocar fuera o con atrás.
- Al empezar a navegar detenido, el mapa ya mira hacia donde sale la ruta.

## [1.5.16] - 2026-11-24

Ajustes más ordenados y fáciles de encontrar, y mapas de un país completo de un solo toque.

### Nuevo
- **Mapa de un país completo:** en Ajustes → Apps → Navegación → Sin conexión, busca tu país (por ejemplo «México»): sale
  primero con **«Descargar completo»** y su tamaño, y abajo todos sus estados. Se bajan uno tras otro, con avance y Cancelar.
- **«Marcar aquí»** en el mapa en grande: toca el botón del pin, mueve el mapa hasta la mira y marca el punto sin dejar
  presionado.
- **Agregar widget** directo desde Ajustes → Inicio y apariencia → Inicio.
- **Reiniciar el viaje** desde las opciones de los widgets Viaje y Carretera (con confirmación).
- Botón «Usar LiteDash Connect» cuando la música por Bluetooth de tu pantalla solo se controla desde el celular.

### Cambiado
- **Ajustes reorganizados:**
  - Inicio y apariencia: Inicio · **Idioma y unidades** (km/°C arriba, a la vista) · Barra · Interfaz · **Tema y brillo**.
  - El fondo (imagen o mapa) se elige en Inicio; «Ocultar la barra de Android» pasó a Barra.
  - **Clima** ahora es un módulo (Ajustes → Módulos), como Taller y Llantas.
  - **Acerca de y ayuda** (antes Sistema): «Reportar un problema» tiene su propia pestaña.
  - Módulos → Permisos muestra todos los permisos y solo los pide al tocarlos.
  - Taller con pestañas siempre y «Adaptador y viajes»; Llantas: Sensores · Presión y avisos · Calibrar · En el inicio.
- **Idiomas y países:** se ven unos cuantos y «Ver todos».
- **Un solo aspecto del mapa** para todos los widgets y el mapa en grande (Ajustes → Apps → Navegación); se quitó el mapa
  «Completo», que no servía para la ruta ni la búsqueda.
- **Guía:** atrás ya no la termina (solo el botón rojo), se cierra sola 30 s después de llegar y sin velocidad dice «—».
- **Teléfono:** la app que elijas la usan el widget, la barra y el volante.
- Tocar el auto lo muestra en grande de fábrica. Nombres iguales en barra y volante, y textos sin jerga.
- Botón de Android Auto / CarPlay con un ícono nuevo.
- Tema claro: textos grises más legibles. Escalas redondas en mpg y gal/h.

### Arreglado
- Lo que habías elegido en el widget Teléfono, Android Auto / CarPlay o en el aspecto de un widget Mapa se conserva.
- La ruta ya no se queda esperando la red al calcularse; las vueltas obligatorias se respetan mejor.
- Se borran solas cachés viejas del mapa que ya nadie usaba (hasta ~25 MB).

## [1.5.15] - 2026-11-23

Auditoría completa de la semana: muchos arreglos chicos en toda la app. Lo que más se nota:

### Nuevo
- **Botón de Android Auto / CarPlay en la barra** (y acción para el volante). Abre tu app de proyección (ZLink, AutoKit,
  CarLetter, Open Headunit…) o la que elijas en **Ajustes → Apps → Android Auto / CarPlay**. Como cualquier botón, se
  puede quitar o mover. El botón de Bluetooth vuelve a abrir solo Bluetooth.
- **«Oscurecer el fondo»** junto a tu fondo de pantalla: deja tu imagen más limpia o más cerrada (#34).
- **Botones + y −** en el mapa en grande, para pantallas donde el gesto de dos dedos no responde.
- **Aleatorio** en la música de Archivos, a un toque en la música en grande.
- **Las descargas de mapas se reanudan:** si se corta la red siguen donde iban (solas al volver internet, o al tocar
  «Descargar»), y antes de bajar se avisa si no cabe.

### Cambiado
- **Ajustes → Apps → Navegación** ahora es como Música y Radio: arriba eliges **«Navegar con»** (Mapa de LiteDash o App
  de mapas) y abajo sale lo de tu elección. «Tu app de mapas» acepta cualquier app con «Otra app…».
- **Mapas sin conexión más fáciles de encontrar:** la lista dice si se está bajando o si falló (con «Reintentar»), los
  mapas por estado se ven con cualquier estilo de mapa, y el buscador encuentra el estado o el país en cualquier idioma.
- **Idiomas:** los 21 idiomas descargables están a la vista (antes solo salían al escribir). Con «Como el sistema» y
  un sistema que no está en español, LiteDash sale en inglés o en el idioma que ya hayas bajado.
- **Tarjetas de vidrio sobre el mapa de fondo:** ahora van como cristal ahumado en vez de un panel plano (#32), y el
  widget Clima ya se ve como las demás (#33).
- La búsqueda de lugares ordena mejor: primero lo que se llama igual que lo que escribes y lo que queda cerca.
- Con la barra oculta, cualquier toque en un lugar libre la asoma. En el cajón de apps, «Elegir» deja ocultar una app
  o ver su información sin dejar presionado. Aplicar una plantilla sobre una página con widgets pide confirmación.

### Arreglado
- **«Abrir LiteDash al encender»** fallaba en algunos encendidos; además ahora reconoce el encendido de más pantallas
  (TS10 / QF, Autochips, Jancar).
- **Música:** el botón de la pantalla hace siempre lo que muestra (ya no hay que tocar play dos veces); el volante ya
  distingue si suena la radio o la música; el Bluetooth de Android 6–8 muestra la canción; en pantallas cuyo Bluetooth
  no se puede controlar, el widget lo dice en vez de enseñar un play que no hace nada.
- **Guía:** si te sales de la ruta lo dice, al recalcular la ruta ya no arranca hacia atrás, y al terminar la guía la
  ruta se vuelve a trazar desde donde estás.
- **Clima:** en Android 6–10 podía quedarse en «Buscando el clima…»; sin internet muestra la temperatura pronosticada.
- **OBD:** más estable con adaptadores clon; ya no insiste con el permiso del cable USB; «Desconectar» se respeta.
- **Llantas:** en pantallas sin pila de reloj se conserva el historial, así la fuga lenta sí puede avisar.
- **Pantallas sin pila de reloj:** cuando la fecha está mal, LiteDash lo dice en vez de «revisa tu internet».
- Tu auto → Modelos: el catálogo ya no dice «Buscando…» y «No se pudo bajar» a la vez, y tiene «Reintentar».
- Con tema claro e imagen de fondo ya se leen el menú de Ajustes y lo que va sin tarjeta.
- Más de cien arreglos menores de la auditoría (memoria, trabajo en segundo plano, textos y permisos).

## [1.5.14] - 2026-11-18

### Cambiado
- **Acomodar la barra sin dejar presionado:** en Ajustes → Inicio y apariencia → Barra, toca un botón de la barra (o
  una de sus dos rayas, o tus favoritas) y muévelo con las flechas, o quítalo con «Quitar». En muchas pantallas de auto
  dejar presionado y arrastrar no respondía bien. En el inicio se sigue pudiendo arrastrar como antes.

## [1.5.13] - 2026-11-18

### Cambiado
- **Vidrio nuevo, tipo cristal:** las tarjetas de vidrio ya casi no llevan relleno: se ve tu fondo a través, menos
  desenfocado y con los colores más vivos, con un filo de luz que nace en la esquina. Si tu imagen es muy clara (o muy
  oscura con el tema claro), el fondo detrás de cada tarjeta se atenúa solo lo necesario para que el texto se lea.
- **«Cuánto se ven» ahora va de cristal a lisa:** en Ajustes → Inicio y apariencia → Interfaz, todo a la izquierda es
  puro cristal y hacia la derecha la tarjeta se va llenando con su color. De fábrica viene como cristal.
- En las páginas con el mapa de fondo las tarjetas conservan su relleno de antes (el mapa se mueve y no se desenfoca).

## [1.5.12] - 2026-11-18

### Cambiado
- **La muestra de las tarjetas es tu inicio de verdad:** en Ajustes → Inicio y apariencia → Interfaz, la muestra usa el
  pedazo de tu fondo a la escala del inicio y la tarjeta lleva el mismo vidrio que tus widgets (antes mostraba el fondo
  entero en chico y sin desenfoque, y no se parecía a lo que veías después).
- **Widget Música angosto y alto:** los botones van al centro cuando no hay lugar para la carátula a un lado; y si el
  widget es un poco más alto, la carátula sale chica junto al título.

## [1.5.11] - 2026-11-18

### Cambiado
- **La ruta y la guía en 3D, más claro por qué piden el mapa:** el aviso ahora dice que usan el mapa descargado de tu
  estado o país aunque tengas internet, y lleva un botón directo a la descarga. Si todavía no hay mapa de tu zona, el
  botón «Mapas disponibles» te lleva a la lista, donde también puedes pedir el tuyo.

### Arreglado
- **El control de volumen con tarjetas de vidrio** ya no sale translúcido encima del inicio (no se leía): siempre es
  una tarjeta lisa.
- **Carátula cortada en el widget Música:** en un widget angosto y no muy alto la carátula salía como una franja
  delgada arriba; si no cabe bien, ya no se pone y el título y los botones van centrados.

## [1.5.10] - 2026-11-18

### Arreglado
- **El botón de play/pausa del volante ya pausa a la primera:** si la música (Spotify u otra app, o tus archivos) ya
  estaba sonando y el widget Música no estaba a la vista o aún no sabía que sonaba, el botón del volante mandaba «play»
  otra vez en lugar de «pausa», y no había forma de pausarla sin tocar la pantalla. Ahora LiteDash pregunta en ese
  momento si la app está sonando antes de decidir.

## [1.5.9] - 2026-11-18

### Agregado
- **Botón de Inicio en la barra:** te regresa al tablero y cierra lo que tengas en grande (mapa, música) o abierto. Sale
  solo al principio de la barra; se puede mover o quitar como los demás.
- **La barra se acomoda también desde sus ajustes:** en Ajustes → Inicio y apariencia → Barra ahora se ve la barra
  misma; ahí arrastras los botones para ordenarlos, los quitas con la x y mueves las dos rayas que la parten.

### Cambiado
- **La ruta se ajusta a la pantalla:** al tocar «Ruta», el mapa encuadra el camino completo usando todo el espacio
  libre, a lo ancho o a lo alto según la ruta. Antes se alejaba de más: la ruta se veía chica y a veces el mapa de tan
  lejos no cargaba.
- Tus apps favoritas, cuando aún no eliges ninguna, se marcan con una estrella al acomodar la barra (tenían el mismo
  ícono que «Todas las apps»).

## [1.5.8] - 2026-11-18

### Cambiado
- **«Usar app de mapas externa», ahora sí para todo:** la opción de Ajustes → Apps → Navegación decide un solo
  camino. Apagada (de fábrica), todo usa el mapa de LiteDash: el toque al widget Mapa, Casa, Trabajo, la barra y el
  volante; ya no aparece el botón de tu app de mapas en el widget ni «Abrir en…» en la tarjeta del lugar. Prendida, el
  widget sigue mostrando el mapa con tu flecha, pero un toque abre directo tu app de mapas (Waze, Google Maps…), igual
  que Casa, Trabajo, la barra y el volante.
- **Casa y Trabajo en el mapa de LiteDash:** con la app externa apagada, tocarlos abre el lugar en el mapa de LiteDash,
  listo para trazar la ruta.
- **El widget Mapa ya no tiene brújula:** esa es de la navegación (sigue en el mapa en grande y en la guía).

## [1.5.7] - 2026-11-18

### Cambiado
- **Los países con mapa, a la vista:** en Ajustes → Apps → Navegación → Sin conexión → Por estado ahora se ven siempre
  los países disponibles (México, Estados Unidos, Puerto Rico, Canadá y Colombia); toca uno y salen sus estados. Antes
  estaban escondidos tras «Otros estados» y mucha gente pedía un país que ya estaba.
- **Sin conexión abre en «Por estado»** aunque la pantalla aún no sepa dónde estás (abría en «Por zona» y los estados ni
  se veían), y enciende el GPS mientras está abierta para poner tu estado arriba.
- El reporte técnico incluye qué mapas tienes bajados.

## [1.5.6] - 2026-11-18

### Cambiado
- **El aviso de la ruta ya no confunde:** decía «Para la ruta sin internet, baja el mapa de tu estado» y muchos entendían
  que no tenían internet. LiteDash traza la ruta con el mapa descargado de tu estado; ahora el aviso dice cuál hace falta
  («hace falta el mapa de Jalisco») y aclara que las zonas solo sirven para ver el mapa.
- **Botón «Bajar mapa» en ese aviso:** te lleva directo a Ajustes → Apps → Navegación → Sin conexión.
- Si todavía no hay mapa de donde estás, el aviso lo dice en vez de mandarte a bajar algo que no existe.

## [1.5.5] - 2026-11-18

### Cambiado
- **Vueltas naturales en la guía:** en las esquinas la ruta azul ya no hace un pico cuadrado; traza la curva que da un
  auto al girar, y tu auto la sigue.

### Arreglado
- **Tidal y otras apps de música que no salían en la lista** (Ajustes → Apps → Música → App): ahora también aparecen
  las que se declaran app de música aunque no ofrezcan su biblioteca, y Tidal, Qobuz, SoundCloud y Pandora siempre que
  estén instaladas. (Cualquier otra se sigue pudiendo elegir con «Otra app».)

## [1.5.4] - 2026-11-18

### Arreglado
- **La línea azul de la ruta no aparecía en Android 8.1 y anteriores:** en muchas pantallas la ruta no se dibujaba en la
  guía («Ir») ni en el mapa de junto. Ahora se dibuja solo lo que se ve y funciona en cualquier pantalla.
- **La guía ya no recalcula sin parar** cuando sales de un estacionamiento o el GPS anda lejos de la calle (la tarjeta
  parpadeaba entre «Recalculando» y «Esperando ubicación»).
- La ruta se vuelve a calcular sola si tu ubicación real resultó estar lejos de la última conocida, y también cuando
  llega la lista de estados si abriste sin internet.
- En la guía, la hora, la velocidad y la próxima vuelta ya no se enciman con la barra de estado de Android.
- Ver toda la ruta ajusta bien el zoom en pantallas de densidad baja o muy alta.

### Cambiado
- **Guía más limpia, estilo Tesla:** la ruta es una franja azul lisa (sin orilla blanca ni halo) y las calles de doble
  sentido llevan una sola raya amarilla delgada en vez de dos.
- **Mapa más ligero:** detenido ya no se redibuja 30 veces por segundo (el GPS baila aunque no te muevas); los pedazos
  del mapa se dibujan sin volver a leer el disco, en hilos de menor prioridad que la interfaz, y con un tope de memoria.
- Cada ruta y cada recálculo reusan el ruteador ya abierto (antes se volvía a abrir el archivo de calles cada vez).

## [1.5.3] - 2026-11-18

### Arreglado
- **Android 8 y anteriores:** al elegir un lugar en el buscador del mapa, el teclado se volvía a abrir y tapaba la
  tarjeta del lugar (Ruta, Favorito, Casa/Trabajo). Ahora el teclado se esconde y la tarjeta aparece.

## [1.5.2] - 2026-11-18

### Nuevo
- **Mapas sin conexión de tres países más:** **Estados Unidos** (los 50 estados, Washington D. C. y Puerto Rico),
  **Canadá** (las 10 provincias y los 3 territorios) y **Colombia** (los 33 departamentos). Cada estado completo en una
  descarga; los grandes pesan más (California ~700 MB): bájalos con buen internet.

### Cambiado
- **La ruta usa mucha menos memoria:** solo lo que recorre, no todo el estado (en California, una ruta por la ciudad
  pasa de ~46 MB a ~1 MB), así que los estados grandes funcionan también en pantallas de 1 GB.
- Si un viaje no cabe en la memoria de tu pantalla, LiteDash te lo dice («Este viaje es muy largo…») en vez de
  cerrarse.

## [1.5.1] - 2026-11-17

### Cambiado
- **Un toque al mapa abre la navegación de LiteDash.** El widget Mapa siempre abre el mapa de LiteDash en grande
  (buscador, ruta y guía); tu app de mapas (Waze, Google Maps…) está en su botón de la esquina.
- El botón de navegar de la barra y el del volante también abren el mapa de LiteDash. Si prefieres tu app de mapas,
  prende «Abrir tu app de mapas desde la barra y el volante» en Ajustes → Apps → Navegación (si ya la habías cambiado,
  se queda como la dejaste).

## [1.5.0] - 2026-11-17

**LiteDash 1.5: Navegación sin conexión.** Todo lo de la 1.4.168 a la 1.4.184 en una versión: sin claves de API ni
cuentas.

### Nuevo
- **Descarga tu estado o una zona** y busca, traza tu ruta y síguela sin internet.
- **Guía estilo Tesla:** tu auto en 3D sobre la ruta azul, calles con rayas, edificios en 3D, la próxima vuelta y tu
  música junto al mapa.
- **Brújula** en el widget Mapa, el mapa en grande y la guía.
- **¿No está tu país? Pídelo** desde Ajustes → Apps → Navegación → Sin conexión.
- Mapa de fondo y páginas en el inicio, apariencia de vidrio en toda la app y la barra de apps por partes.

## [1.4.184] - 2026-11-17

### Nuevo
- **Pide el mapa de tu país.** En Ajustes → Apps → Navegación → Sin conexión, «¿No está tu país? Pídelo» abre un
  reporte ya listo: escribes tu país y región y lo agregamos para que funcione sin internet.

### Arreglado
- La brújula del widget Mapa no hacía nada al primer toque: ahora apunta como está girado el mapa y cambia al instante.
- En celulares acostados el teclado del buscador del mapa se cerraba al abrirlo.

## [1.4.183] - 2026-11-17

### Cambiado
- En la guía, la música va abajo solo del lado del mapa (como Tesla); tu auto llega hasta abajo de la pantalla.

## [1.4.182] - 2026-11-17

### Nuevo
- **La guía, como Tesla.** La ruta va en azul con brillo y pasa por debajo de todo tu auto; las calles se ven
  rellenas, con banqueta, orillas y raya amarilla al centro en las de doble sentido (blanca punteada en las anchas de
  un solo sentido), y los edificios se levantan en 3D con su altura real (donde el mapa la trae).
- **La música en la guía.** Abajo, a lo ancho, como en Tesla: ves qué suena y cambias de canción sin salir.
- **Brújula en el mapa.** Un botón redondo en el widget Mapa, en el mapa en grande y en la guía: apunta al norte y un
  toque cambia entre «siempre al norte» y «hacia donde vas». En Ajustes, la orientación ahora tiene tres opciones:
  Automático (como siempre), Hacia donde vas y Siempre al norte.
- **La barra oculta se asoma con un toque** (#27). Con «Ocultar la barra», toca un espacio vacío del inicio y aparece
  unos segundos encima, sin mover tus widgets.

### Cambiado
- En la guía, «Ver ruta» y «Terminar» ahora son botones redondos a la derecha del mapa, junto a la brújula.
- Los avisos (llantas, motor, «LiteDash no es tu inicio»…) flotan arriba encima de los widgets en vez de empujarlos
  hacia abajo (#27); con el mapa o la música en grande solo quedan los del auto.
- El buscador de apps va arriba, entre el título y la ✕, y la lista gana casi una fila en pantallas bajas (#28).
- La ruta se dibuja en azul en todos los mapas.

### Arreglado
- En Android 6 el teclado del buscador ya no se queda abierto encima de la guía.

## [1.4.181] - 2026-11-17

### Cambiado
- Al acomodar la barra de apps, los demás íconos se recorren mientras arrastras uno (como los widgets del inicio); ya
  no hay que soltarlo para ver cómo queda.

## [1.4.180] - 2026-11-17

### Nuevo
- **La barra de apps, a tu manera.** Al acomodarla (déjala presionada) aparecen dos rayas: lo que dejes antes de la
  primera va a la izquierda, entre las dos al centro y después de la segunda a la derecha. Arrastra los íconos para
  ordenarlos o cambiarlos de parte, y las rayas también. En la barra lateral es arriba, centro y abajo.

### Cambiado
- **Colores más fáciles de elegir.** En Ajustes → Inicio y apariencia → Interfaz todo lo del color vive en
  «Tarjetas»: primero las combinaciones listas y, al tocar la paleta (el último círculo), se abren el color de las
  tarjetas y el de la interfaz por separado.

## [1.4.179] - 2026-11-17

### Cambiado
- **Fondo de fábrica más sobrio.** Sin imagen de fondo, el resplandor detrás del vidrio ya no lleva el ámbar: ahora
  son luces neutras (plata y acero), en oscuro y en claro. Si eliges un color de interfaz, el resplandor lo toma.

## [1.4.178] - 2026-11-17

### Nuevo
- **Toda la app con la piel que elijas.** El estilo de las tarjetas ya no es solo del inicio: la barra de abajo, la
  música y el mapa en grande, Ajustes, el Taller y todos los paneles llevan el mismo vidrio y el mismo color.
- **Vidrio de fábrica.** Las tarjetas dejan ver tu fondo de pantalla desenfocado; sin imagen, un resplandor suave con
  tu color. Si prefieres lo de antes: Ajustes → Inicio y apariencia → Interfaz → Tarjetas → «Lisas».
- **Colores vivos y combinaciones listas.** Azul, verde, vino, naranja, violeta, arena… o el que quieras. Tú eliges
  el color y LiteDash lo acomoda al tema: profundo de noche y claro de día, para que todo se siga leyendo.

### Cambiado
- Las tarjetas son lisas o de vidrio; las «translúcidas» pasan a vidrio.

### Arreglado
- Con vidrio, el velocímetro y los medidores ya no se veían apagados: su aro y sus marcas ahora se leen sobre
  cualquier fondo.

## [1.4.177] - 2026-11-17

### Arreglado
- **El escáner OBD por Bluetooth se queda conectado.** Antes se desconectaba al rato de no ver sus widgets (al abrir
  Ajustes, la música o el mapa en grande, o cambiar de página) y volvía a conectar desde cero; varios adaptadores
  ELM327 fallaban al reconectar. Ahora conecta una vez y así se queda hasta que tú lo desconectes o apagues el módulo.
- Si el Bluetooth de la pantalla está apagado o tarda en prender al encender el auto, el Taller lo dice claro y
  conecta solo en cuanto prende. «Encender Bluetooth» abre los ajustes de Bluetooth de Android si la pantalla no lo
  prende sola, y «Apagar Bluetooth» ahora pide confirmar (estaba junto a los adaptadores).
- Cada intento de conexión es más ligero (una ronda en vez de dos) y, con el adaptador dormido, reintenta a lo más
  cada minuto en vez de cada cinco.
- Deslizar sobre el widget de Música o Radio cambia de canción o estación aunque la pantalla no muestre los datos de
  lo que suena (igual que sus botones).
- El mapa ya no se va a medio océano cuando el GPS manda «0, 0» por falta de señal: conserva tu último punto.

## [1.4.176] - 2026-11-17

### Nuevo
- **La guía vuelta por vuelta, sin internet:** con la ruta lista toca «Ir» y se abre la pantalla de conducción. A la
  izquierda tu auto de espaldas sobre las calles, con la ruta como una franja del ancho del auto (como Tesla); a la
  derecha el mapa con la próxima vuelta («50 m · Gira a la izquierda hacia Av. Insurgentes», y «Luego…»), la hora de
  llegada, lo que falta y la barra de avance. De pie, el auto arriba y el mapa abajo.
- Si te sales de la ruta, recalcula al instante en tu pantalla; al llegar te dice «Llegaste».
- «Ver ruta» enseña toda la ruta y «Terminar» te regresa a donde estabas, sin tocar tu inicio.

## [1.4.175] - 2026-11-17

### Nuevo
- **Tu ruta, sin internet:** en la tarjeta de un lugar toca «Ruta» y LiteDash la calcula en tu pantalla con las
  calles de tu estado (respeta sentidos y vueltas prohibidas). Ves la ruta dibujada sobre el mapa, cuánto mide, cuánto
  tardas (sin contar el tráfico), a qué hora llegas y por qué calle vas. La guía vuelta por vuelta llega en la
  siguiente etapa; mientras, «Abrir en tu app de mapas».
- Las calles para la ruta vienen con el mapa de tu estado. Si ya lo tenías bajado, al pedir tu primera ruta te ofrece
  bajarlas (Colima pesa 1.7 MB y el Estado de México, el más grande, 22 MB).
- Si el lugar está en otro estado o no tienes bajado el tuyo, te lo dice y te deja abrirlo en tu app de mapas.

### Cambiado
- Con la tarjeta de un lugar abierta, el mapa pone el lugar en la parte que la tarjeta deja libre.

## [1.4.174] - 2026-11-16

### Nuevo
- **Busca a dónde ir en el mapa:** en el mapa en grande (o con la lupa del widget Mapa) escribe un lugar, una calle,
  una colonia o un pueblo y los resultados salen al instante, **sin internet**, del mapa de tu estado (más cerca
  primero, aunque escribas sin acentos o con un error de dedo). Si no está, «Buscar en internet» lo busca en todo el
  mundo con una sola consulta. Toca un resultado y el mapa va ahí con un pin y su tarjeta: a cuánto queda, abrirlo en tu
  app de mapas, guardarlo de favorito o ponerlo de Casa o Trabajo. Tus recientes y favoritos quedan como atajos.
- **Deja presionado el mapa** para marcar cualquier punto (un rancho, una entrada) y abrirlo en tu app de mapas.
- **Lugares desde otras apps:** un lugar compartido (WhatsApp, el navegador…) se puede abrir con LiteDash y sale en su
  mapa.
- La búsqueda sin internet se baja sola con el mapa de tu estado (y a quien ya lo tenía, la primera vez que busca).

### Cambiado
- **Sin conexión, más claro:** elige «Por estado» (el mapa de todo tu estado) o «Por zona» (un círculo alrededor de
  ti); si tu estado aún no está, usa la zona.

## [1.4.173] - 2026-11-16

### Cambiado
- **«Más idiomas» con buscador:** en vez de la lista completa, escribe tu idioma (en el tuyo o en el de la app:
  «alemán» o «german» encuentran «Deutsch») y bájalo. Si aún no está, te dice dónde ayudar a traducirlo.

## [1.4.172] - 2026-11-16

### Arreglado
- **«Más idiomas» ya muestra todos los idiomas** al actualizar la app (antes podía quedarse con la lista vieja hasta un
  día).

### Cambiado
- **Buscador en «Otros estados»** del mapa sin conexión: escribe el nombre y aparece (sin escribir, los de tu país).

## [1.4.171] - 2026-11-16

### Nuevo
- **El mapa de todo tu estado, sin internet:** en Ajustes → Apps → Navegación → Sin conexión aparece «Mapa de tu
  estado»: LiteDash sabe en cuál estás y lo bajas de una vez (de 4 a 45 MB, según el estado). Los 32 estados de México
  ya están; también puedes bajar otros, actualizarlos o borrarlos. Tus zonas siguen igual.

## [1.4.170] - 2026-11-16

### Nuevo
- **Plantilla «Mapa de fondo»** (Plantillas → Mapa): una columna de widgets a la derecha (abajo en pantallas de pie) y
  el mapa vivo detrás, contigo en el espacio libre.

### Cambiado
- **Tu auto en el mapa de fondo se ve grande**, de espaldas y con su sombra, a la medida de tu pantalla (antes salía
  en miniatura).
- Las plantillas deciden el fondo de la página: «Mapa de fondo» pone el mapa y las demás lo quitan (ya no quedan el
  mapa de fondo y el widget Mapa a la vez).
- En una página con mapa de fondo, «Agregar» ya no ofrece el widget Mapa; si eliges el mapa de fondo en una página que
  ya lo tiene, te avisa y te deja quitarlo.

## [1.4.169] - 2026-11-16

### Nuevo
- **Páginas en tu inicio:** al acomodar, «+» agrega otra página (hasta 5) con sus propios widgets; deslizas en un
  espacio libre para cambiar de página y los puntitos de abajo dicen en cuál vas. Con una sola, todo igual que antes.
- **El mapa como fondo:** cada página elige su fondo, tu imagen o el mapa vivo. El mapa te sigue detrás de tus widgets,
  contigo en el espacio que dejan libre, y si Waze o Maps te están guiando, su siguiente vuelta sale ahí. Puedes verte
  como flecha o como tu auto (entonces el mapa gira hacia donde vas).
- **Tarjetas a tu gusto:** en Inicio y apariencia → Interfaz → Tarjetas eliges su color y si son lisas, translúcidas o
  de vidrio (con tu imagen de fondo desenfocada detrás), y cuánto se ven.

## [1.4.168] - 2026-11-16

### Cambiado
- **Mapas sin conexión dentro de las reglas del servidor de mapas:** cada descarga es de 10, 20 o 30 km (OpenFreeMap
  permite hasta 3 000 teselas o 50 MB por descarga). Lejos del ecuador, si la zona sale más grande, te lo dice antes.
  Las zonas que ya bajaste se quedan.

## [1.4.167] - 2026-11-16

### Nuevo
- **23 idiomas:** a español, inglés, francés, portugués y turco se suman alemán, italiano, ruso, ucraniano, polaco,
  checo, neerlandés, sueco, rumano, húngaro, griego, chino, japonés, coreano, hindi, tailandés, indonesio y vietnamita.
  Se bajan al elegirlos en Ajustes → Inicio y apariencia → Interfaz → Idioma → Más idiomas.
- **Revisa tu idioma:** la lista dice cuáles revisó un hablante nativo y cuáles son traducción automática. Si ves un
  error, repórtalo en litedash.dev/translate.
- Las novedades de cada versión salen en inglés si no usas la app en español.

### Arreglado
- Con «Usar app externa» apagado, el mapa en grande ya no muestra el botón para abrir tu app de mapas.
- Al cambiar de idioma regresas a Ajustes, donde estabas, y no al inicio.
- Las zonas del mapa sin conexión dicen su tamaño en millas si usas millas.
- En el explorador de fotos y carpetas, «atrás» sube de carpeta en vez de cerrar todo.
- En pantallas de pie ya no se cortan la ayuda de «Acomoda tu inicio» ni la descripción de los widgets.

## [1.4.166] - 2026-11-16

### Arreglado
- **El escáner OBD por Bluetooth vuelve a ser estable** (issue #23): desde la 1.4.163 se desconectaba cada vez que
  abrías Ajustes y al volver se reconectaba de cero, y muchos adaptadores ELM327 fallan al reconectar tan rápido. Ahora
  sigue conectado mientras usas la app, espera un momento antes de reabrir la conexión y, al volver a LiteDash, reintenta
  de inmediato si se había quedado esperando. Si tu auto no da el voltaje, ya no cuenta como falla.
- **Música por Bluetooth sin sonido con el volumen en 0:** si la pantalla pregunta «¿Se escucha?» y el volumen de la
  música está en 0, ahora te lo avisa, y contestar «No» ya no guarda que nada funciona en tu pantalla.

## [1.4.165] - 2026-11-16

### Nuevo
- **El modo estacionado muestra lo que suena** (issue #19): carátula, canción y artista (o la estación de radio) debajo
  del reloj. El saludo solo sale si guardaste tu nombre en Ajustes → Inicio y apariencia → Interfaz → Tu nombre.

### Arreglado
- **Pantallas muy bajas** (8.8" de 1280×480 y parecidas, issue #21): Ajustes, la vista grande de Llantas, la música en
  grande y el modo estacionado se acomodan solos para caber, sin cambiar el tamaño de tu inicio. Los cuadros de cada
  llanta ya no se enciman.
- La lista de Archivos sin música (o sin permiso) ahora se desplaza: el botón «Elegir carpeta» ya no queda fuera en
  pantallas bajas.

## [1.4.164] - 2026-11-16

### Arreglado
- **Música por Bluetooth en pantallas con app de Bluetooth de marca** (Essgoo y parecidas, issue #13): la canción y el
  artista ya se muestran, y play, pausa, siguiente y anterior controlan la música en vez de abrir «BT Music» cada vez.
  También sirve deslizar la tarjeta para cambiar de canción.
- **El botón de Bluetooth de la barra** abre la pantalla de emparejar («Bluetooth»), no la de la música («BT Music»),
  cuando la app trae las dos.
- Los reportes ya no se llenan con el mismo aviso de Bluetooth repetido: se ve lo que pasó antes.

## [1.4.163] - 2026-11-16

Auditoría total: cada parte de la app revisada archivo por archivo, con una segunda revisión independiente antes de publicar.

### Nuevo
- **Más idiomas, descargables:** francés, turco y portugués en Ajustes → Inicio y apariencia → Interfaz → Idioma →
  Más idiomas. Se bajan al elegirlos (la app sigue igual de ligera) y lo que falte de traducir sale en inglés. Cualquiera
  puede aportar un idioma: ver `translations/` en GitHub.
- **Ocultar la barra de LiteDash** y una pestaña **Barra** en Ajustes → Inicio y apariencia con toda su configuración
  (botones, apps favoritas, posición, estilo y tamaño). Oculta, el inicio gana su lugar sin mover tus widgets; para verla
  y entrar a Ajustes, deja presionado cualquier lugar del inicio.
- **Códigos de falla en tu idioma:** las descripciones del Taller también se traducen con los idiomas descargables.
- Los radios de descarga del mapa sin conexión se muestran en millas si usas millas.

### Cambiado
- **Llantas: la calibración y el historial van con el sensor**, no con la posición: al rotar las llantas o cambiar un
  sensor de lugar, su ajuste se va con él. Tu calibración actual se conserva.
- **Sensores de llantas por Bluetooth:** solo se acomodan solos con la pantalla de Llantas abierta, y uno que quitaste
  ya no regresa por su cuenta.
- **Avisos de llantas con lecturas viejas:** si ningún sensor ha hablado hace rato, solo se avisa de presión baja y
  fuga lenta (ya no de temperatura, pila ni fuga rápida con datos viejos).
- **Enlace con LiteDash Connect más sencillo:** la pantalla busca al celular por su hotspot, su aviso por la red y la
  última dirección; con LiteDash en segundo plano ya no insiste cada rato.
- La pestaña «Actualizaciones» de Sistema ahora es «Acerca de» (en la versión de GitHub ahí sigue el botón para
  buscar versiones). Se quitó «Cámaras · Pronto» de Módulos.
- El actualizador (versión de GitHub) siempre pide confirmar y verifica la descarga antes de instalar.

### Arreglado
- **Radio FM de fábrica:** LiteDash ya solo la calla cuando sabe que está sonando; antes podía mandarle una pausa a
  ciegas y, en radios que alternan con cualquier tecla, prenderla.
- **Música:** reproducir, pausar y cambiar de canción siguen un solo camino para todas las fuentes (app, archivos,
  Bluetooth y radio), con menos casos raros al cambiar de una a otra.
- **Escáner OBD:** una sola regla de reintentos (ya no se queda intentando si nadie lo usa) y mensajes de error que
  dicen qué pasó.
- **Clima:** un pronóstico guardado de hace días ya no se muestra como el de hoy.
- **Fotos de widgets y fondos** tomadas con la cámara ya no salen acostadas; elegir una imagen dañada avisa en vez de
  fallar en silencio.
- **Barra lateral con muchos botones** en pantallas chicas: Ajustes siempre queda a la vista.
- Con LiteDash abierto dos veces (como inicio y como app), el volante y los avisos ya solo los atiende la ventana al frente.
- Decenas de correcciones menores de textos, memoria y casos poco comunes encontradas en la revisión.

## [1.4.162] - 2026-11-16

Revisión completa de la semana: errores reportados, pedidos de la comunidad y limpieza.

### Arreglado
- **El mapa arranca en lo más cerca e inclinado**, también detenido (antes se abría alejado y plano).
- **El mapa ya no parpadea al acercar o alejar:** el zoom es continuo, las calles no desaparecen mientras llegan las
  nuevas, tu flecha y los nombres no cambian de tamaño, y la inclinación baja poco a poco al alejarlo. Centrar es una
  sola animación y abrir el mapa en grande ya no empieza vacío.
- **Bluetooth de fábrica con dos pantallas** («Bluetooth» y «BT Music» en la misma app): al dar play se abre la de la
  música, no la de emparejar.
- **La radio FM de fábrica ya no se queda sonando** encima de tu música: al dar play a la música, LiteDash la calla.
- **«No logramos que suene» falso** con el Bluetooth: la revisión del sonido espera más y ya no confunde la app de
  Bluetooth de la pantalla con otra app.
- **Música de una memoria USB** en pantallas con Android 7 a 10 que no la encontraban; si Android no da el sonido,
  ahora se avisa en vez de quedarse callado.
- **Volumen en pantallas NWD** (y otras donde lo lleva el equipo): los botones de LiteDash y del volante ahora mandan
  la tecla de volumen de la pantalla.
- **Llantas en modo Demo:** ya no sale el aviso de llanta baja en el inicio (era de la demostración).
- **Escáner OBD por Bluetooth:** reintenta solo cuando la conexión falla al primer intento (adaptadores de baja
  energía y clones clásicos).
- **Día y noche con las luces del auto:** si tu pantalla no avisa de los faros, lo dice y te deja usar la hora del
  sol; ya no aprende ajustes que no son de luces.
- **Reloj** con la hora correcta cuando la pantalla cambia de zona horaria después de encender.
- **Taller:** si negaste el permiso de Bluetooth, hay un botón para darlo en los ajustes de Android.
- El mapa en grande abierto desde la barra usa el aspecto de tu widget Mapa; los acomodos guardados dañados se
  reparan al cargar; los atajos a los ajustes de Android aparecen en pantallas que esconden Ajustes.

### Nuevo
- **Cualquier app de navegación:** en Ajustes → Apps → Navegación aparecen todas las apps de mapas instaladas
  (OsmAnd, Organic Maps, Sygic, HERE, Magic Earth, Vela Maps y más), no solo cinco.
- **Desliza la tarjeta de música** (o de radio) a la izquierda o a la derecha para pasar a la siguiente o regresar.

### Cambiado
- El respaldo de Android ya no incluye los mapas sin conexión ni los autos descargados (ocupaban tanto que no se
  respaldaba nada), ni el enlace con tu celular.
- Los reportes traen más datos para diagnosticar llantas, volumen, memorias USB y el escáner.

## [1.4.161] - 2026-11-14

### Cambiado
- **Música y radio en grande, rediseñadas:** con espacio, carátula grande a un lado y, al otro, el título centrado, la
  barra de avance con el tiempo que va y el total, y botones más grandes y con aire.
- **Barra de avance en la música de tus archivos** (USB o carpeta): antes no salía porque esas canciones no traían
  duración. El widget Música, si es alto, también muestra los tiempos.
- **El mapa es el mismo en el widget y en grande:** lo que muevas o acerques se conserva al abrirlo y al cerrarlo.
- **Centrar se ve:** el mapa se desliza hasta ti y luego se acerca paso a paso, en vez de saltar.

## [1.4.160] - 2026-11-14

### Cambiado
- **Tu posición siempre es una flecha, más grande.** Antes, sin rumbo del GPS, se veía un punto.
- **El mapa se inclina en lo más cerca**, también detenido o al acercarlo con dos dedos (antes solo de viaje), y se
  acerca un poco más (como Waze). Alejado, plano.
- **Centrar** tiene un ícono de mira y te regresa a lo más cerca, inclinado. Detenido, el mapa ya no se centra solo
  si lo moviste; de viaje sí, tras 20 segundos.
- **Mapas sin conexión:** toca una zona guardada para verla en el mapa con su círculo, y volver a bajarla o borrarla.
- Los tamaños se ven en MB, GB o TB.

## [1.4.159] - 2026-11-14

### Cambiado
- **El mapa de viaje se ve como Waze:** al manejar se acerca mucho más (un par de calles a los lados), tu flecha va
  hasta abajo para ver más camino adelante y la inclinación es más suave.

## [1.4.158] - 2026-11-14

### Arreglado
- **El mapa siempre mira hacia adelante, como Waze:** en pantallas cuyo GPS no da el rumbo (o lo da en 0°), la flecha
  avanzaba de lado. Ahora, si el rumbo del GPS no es confiable, LiteDash lo calcula con tu avance.

## [1.4.157] - 2026-11-14

### Agregado
- **Opciones del mapa en Ajustes → Apps → Navegación:** «App de mapas», «Mapa de LiteDash» (estilo, orientación, zoom y
  colores, con vista previa; se aplica a todos tus widgets Mapa) y «Sin conexión».
- **Elige la zona a descargar en un mapa:** se ve el círculo del radio y puedes arrastrarlo a otro lugar («Mi ubicación»
  regresa a ti). Ahora también de 100 y 200 km, con el espacio libre y un aviso si la zona es grande.

### Cambiado
- **El mapa siempre es plano.** De viaje (andando o con Waze/Maps navegando) se acerca hasta ver un par de calles y se
  inclina como Waze, siempre hacia donde vas; si lo alejas con dos dedos vuelve a ser plano. Se quitó la opción «Vista».
- **Velocímetro sin fondo:** sobre tu fondo de pantalla el aro y los textos apagados ahora son translúcidos (antes se
  veía una mancha gris).

### Arreglado
- La música de una carpeta o USB muestra el título y el artista que trae la canción (antes, el nombre del archivo).

## [1.4.156] - 2026-11-14

### Agregado
- **Mapa sin conexión:** en Personalizar mapa → Sin conexión bajas tu zona (10, 25 o 50 km; te dice cuánto pesa) y el
  mapa minimalista funciona sin internet. Abajo del mapa se ve si es «Mapa en línea» o «Mapa sin conexión». Las zonas
  guardadas se pueden volver a bajar o borrar.
- **Nombres de las calles** en el mapa minimalista, siempre derechos (también en la vista inclinada, como en Waze), y
  la calle donde vas en una pastilla abajo.
- **Edificios** de cerca y avenidas con más color: se ve por dónde se cruza la ciudad.
- **Mueve el mapa con un dedo:** aparece un botón para regresar a ti, y si lo dejas, regresa solo a los 20 segundos.

### Cambiado
- Tu flecha se desliza entre una lectura del GPS y la siguiente en vez de brincar cada segundo (quieto no se mueve nada).

### Arreglado
- En algunas pantallas (TS10) quedaba un hueco abajo después de encender el auto: LiteDash vuelve a medir la pantalla
  mientras termina de arrancar. El reporte ahora dice el tamaño de la ventana y de las barras del sistema.

## [1.4.155] - 2026-11-14

### Agregado
- **Fondos de LiteDash:** en Fondo de pantalla hay 10 fondos para elegir (auroras, la Tierra de noche desde la Estación
  Espacial, la Vía Láctea, dunas al atardecer y abstractos propios). Se bajan de litedash.dev solo cuando tocas uno, así
  que la app no pesa más; ya bajado queda guardado y no vuelve a usar internet. Sin conexión, sigue tu foto.
- **Mapa con dos dedos:** júntalos para alejar y sepáralos para acercar; ya no se aleja con toques.

### Cambiado
- Con «Usar app externa en accesos directos» apagado, el widget Mapa ya no trae el botón a Waze o Maps ni hay toque que
  te saque de LiteDash: todo abre el mapa de LiteDash en grande.

## [1.4.154] - 2026-11-14

### Agregado
- **Música y radio en grande con un toque:** toca el widget Música o Radio fuera de sus botones y la pantalla Música crece
  desde él a pantalla completa (la radio, ya en su pestaña). La barra de apps se queda para cambiar entre música y mapa.
- **Archivos, Bluetooth o App desde la música en grande:** arriba de lo que suena eliges de dónde suena, sin ir a Ajustes.
- **Ajustes → Apps → Navegación → Usar app externa en accesos directos:** prendido (como siempre), el botón del mapa de la
  barra, el del volante y un toque al widget Mapa abren Waze o Maps. Apagado, abren el mapa de LiteDash a pantalla
  completa, con la siguiente vuelta, Casa y Trabajo y un botón a tu app de mapas.

### Cambiado
- La pantalla Música ya no ocupa media pantalla: llena el tablero y crece con una animación (desde su widget o desde el
  centro si la abres con la barra o el volante).
- Plantillas: solo dos grupos, pantallas horizontales y verticales (antes chicas, medianas y grandes); cada plantilla se
  acomoda sola al tamaño de tu pantalla y no se repiten las que en la tuya quedan iguales.

## [1.4.153] - 2026-11-14

### Agregado
- **Calibración del sonido:** la primera vez que suenan el Bluetooth de tu celular o la radio de la pantalla, LiteDash
  prueba solo las formas conocidas de hacerlos sonar en tu marca de pantalla (TopWay, FYT, NWD, HCN, Autochips, Jancar y
  más) y se queda con la que funciona. Si no puede saber si ya se oye, te pregunta en el mismo widget: «¿Se escucha?» Sí /
  No. Si nada funciona, te ofrece mandar un reporte con lo que se probó.
- **Ajustes → Apps → Música → Calibrar sonido:** qué ajuste usa tu pantalla para el Bluetooth y la radio, y «Calibrar otra
  vez» si algo deja de sonar.
- **Lo que funcionó en una pantalla ayuda a las demás:** el ajuste que encontró la tuya va en las estadísticas anónimas
  (puedes apagarlas en Ajustes → Sistema → Estadísticas) y, revisado por nosotros, otras pantallas iguales lo usan a la
  primera, sin preguntar.

### Cambiado
- Las pantallas NWD y Jancar con su propia app de Bluetooth ahora se reconocen como fuente Bluetooth.

## [1.4.152] - 2026-11-13

### Arreglado
- **Pantallas TopWay: la música del celular por Bluetooth no se oía.** En las pantallas con la app de Bluetooth de
  fábrica `com.tw.bt` (chip propio) la canción salía sonando en la pantalla y en el celular, pero no llegaba a las
  bocinas: la pantalla solo deja pasar ese audio cuando alguien elige su entrada de Bluetooth, y eso lo hacía únicamente
  la app de fábrica al entrar a su Música. Ahora, cuando tu celular con LiteDash Connect empieza a sonar, LiteDash elige
  esa entrada solo; al irse el celular o al cambiar de método, regresa al audio normal de la pantalla.

## [1.4.151] - 2026-11-13

### Arreglado
- **Pantallas angostas (celular de pie):** en Ajustes → Módulos los nombres ya no se cortan ("Escáner OBD", "Llantas
  (TPMS)"): van en dos renglones si hace falta y la etiqueta «Pronto» baja debajo del nombre. En el Taller, el campo de
  la dirección del OBD por Wi‑Fi conserva su ancho y los botones pasan al renglón de abajo.

## [1.4.150] - 2026-11-13

### Arreglado
- **OBD por Wi‑Fi en Android 17:** sin el permiso de red local (Dispositivos cercanos) el adaptador nunca conectaba y
  no se decía por qué. Ahora el Taller lo pide al conectar; si ya lo negaste, te lleva a los permisos de Android y, al
  volver con el permiso dado, conecta solo.

## [1.4.149] - 2026-11-13

### Arreglado
- **Ajustes → Celular en Android 17:** al entrar, Android abría y cerraba sin parar su ventana "Elige un dispositivo
  para conectar" y la pantalla parpadeaba. Android 17 pide un permiso nuevo para la red local (Dispositivos cercanos):
  ahora LiteDash lo pide con un botón «Permitir» y, mientras no lo tenga, no intenta buscar al celular.

## [1.4.148] - 2026-11-12

### Agregado
- **Cambia tu auto desde el celular** (con LiteDash Connect 0.6.0): color, acabado, brillo, nombre y el modelo del
  catálogo. La pantalla lo aplica al momento; si no tiene internet, el celular le pasa el auto por el enlace (solo
  imágenes, revisadas con su huella antes de instalarse).
- **El estado de tu auto en el celular:** si lo pide LiteDash Connect, la pantalla le manda una foto del viaje, del
  motor (códigos, batería y temperatura) y de las llantas, de los módulos que tengas prendidos.
- **Reportes con los dos lados:** cuando mandas un reporte desde el celular, va también el diagnóstico de la pantalla.

### Arreglado
- **Ajustes → Apps → Música (Archivos):** "Elegir carpeta" y "Quitar carpeta" ya no se enciman con las pestañas de
  "Tu música" en pantallas de 1024×600.

## [1.4.147] - 2026-11-12

### Agregado
- **Tu auto en LiteDash Connect:** al conectarse, la pantalla le manda al celular tu auto tal como se ve en el inicio
  (modelo, color, acabado y nombre). Si lo cambias en Ajustes → Tu auto, el celular se actualiza en un segundo. La
  imagen solo viaja cuando cambia y nunca sale del enlace cifrado entre tu pantalla y tu celular.

## [1.4.146] - 2026-11-12

### Cambiado
- **Confirmaciones más claras:** eliminar un auto descargado, eliminar todos, actualizar todos, quitar el código Pro y
  borrar los códigos del Taller ya no preguntan "¿Seguro? Sí · Cancelar" dentro del mismo botón: ahora sale una alerta al
  centro, estilo iOS, con la acción en rojo cuando no se puede deshacer. Borrar códigos pasa de dos pasos a uno, con la
  advertencia a la vista.

### Arreglado
- **Desvincular el celular** (Ajustes → Celular) y **Olvidar** un Bluetooth guardado ahora preguntan antes; antes lo
  hacían al primer toque.

## [1.4.145] - 2026-11-12

### Cambiado
- **Estadísticas anónimas:** además de lo de antes, cuentan qué radio y qué Bluetooth trae **de fábrica** tu pantalla
  (solo el nombre técnico de las apps que vienen con el sistema, como `com.hcn.autoradio`, y si tiene Bluetooth de baja
  energía; nunca las apps que tú instalas). Así sabemos a qué radios y Bluetooth darles soporte primero. Se apagan igual que
  siempre en Ajustes → Sistema → Estadísticas.

## [1.4.144] - 2026-11-12

### Cambiado
- En Ajustes → Celular, el renglón "Datos" ahora se llama **Enlace** (es la conexión con LiteDash Connect, no tus
  datos móviles).

## [1.4.143] - 2026-11-12

Revisión completa de todo lo del celular y LiteDash Connect (tres auditorías independientes).

### Arreglado
- **Tu música ya no se pausa sola:** la música de la pantalla pasa a Bluetooth solo cuando tu celular **está sonando**
  (antes, con solo conectarse, aunque viniera en la bolsa). Si el audio lo lleva Android Auto o un cable, no se toca
  nada; y cuando el celular se va, se regresa a lo que tenías sin pausar lo que suene.
- **Android Auto / CarPlay de la pantalla (ZLink y parecidas) nunca se pausan** al cambiar de método de música.
- **"Bluetooth solo para llamadas":** cuando tu celular se conecta al estéreo solo para llamadas, la música sigue
  sonando en el celular. LiteDash lo dice tal cual en el widget Música (con LiteDash Connect 0.4.4) y en
  Ajustes → Celular explica cómo arreglarlo: conectar el celular desde el estéreo, o activar «Audio multimedia» para
  ese estéreo en el Bluetooth del celular.
- En Ajustes → Celular, el renglón **Sonido** ya no dice "Bluetooth conectado" cuando solo están conectadas las llamadas.
- **Botón de Bluetooth** (barra y Ajustes → Celular): en pantallas con ZLink abre ZLink, que es quien maneja ahí el
  Bluetooth del celular; antes abría los ajustes de Android.
- Buscar al celular gasta menos: ya no revisa la red de la SIM ni toca a los vecinos cada minuto (solo al cambiar la
  red, con Ajustes → Celular abierto o con "Buscar ahora"), y se recupera si el Bluetooth de la pantalla se apaga y
  se prende.

## [1.4.142] - 2026-11-11

### Arreglado
- **Ya no dice "Sin sonido" cuando sí se oye:** si tu celular manda el audio por Android Auto o por cable, LiteDash lo
  reconoce (con LiteDash Connect 0.4.3). Cuando el celular solo suena en su bocina, el widget pregunta
  "¿Sin sonido? Conecta el Bluetooth" en vez de afirmarlo.
- En Ajustes → Celular, el renglón Sonido dice "Por Android Auto" o "Por cable" cuando es el caso, y explica qué hacer si
  el Bluetooth quedó "solo para llamadas": conectarlo desde el estéreo.

## [1.4.141] - 2026-11-11

### Agregado
- **La pantalla nota cuando llega tu celular:** con LiteDash Connect 0.4.2 y su permiso de Bluetooth cercano, la
  pantalla lo ve por Bluetooth (sin emparejar nada) y lo busca al instante, en vez de esperar al siguiente intento.
  Necesita el Bluetooth de la pantalla prendido; si no, todo sigue como antes.
- "Enviar un reporte" ahora dice si tu pantalla puede ver a tu celular por Bluetooth: nos ayuda a preparar la conexión
  sin hotspot.

## [1.4.140] - 2026-11-11

### Agregado
- **Botón de Bluetooth en la barra**, junto al de navegar: abre la app de Bluetooth de tu estéreo (o los ajustes de
  Bluetooth de la pantalla) para conectar tu celular sin buscarla. Se quita y se mueve como los demás botones.

### Quitado
- El experimento de Android Auto de la versión de GitHub: no se puede publicar en las tiendas. LiteDash vuelve a
  pesar **1.9 MB**.

## [1.4.139] - 2026-11-11

### Cambiado
- **Con tu celular conectado (LiteDash Connect), la música pasa sola a Bluetooth:** lo que sonaba en la pantalla se
  pausa y el widget muestra y controla lo de tu celular. Cuando el celular se va, regresa al método que tenías
  (Archivos o tu app). Si cambias el método a mano mientras tanto, se queda el que elegiste.
- **Aviso de "Sin sonido":** si la canción está sonando en la bocina de tu celular porque falta el Bluetooth, el
  widget Música lo dice. Necesita LiteDash Connect 0.4.1.
- En Ajustes → Celular, el renglón **Sonido** ahora también funciona en pantallas con Bluetooth propio del estéreo.

## [1.4.138] - 2026-11-11

### Cambiado
- **Ajustes → Celular** (antes "Cuenta"): conectar tu celular con LiteDash Connect ahora se entiende. Si la pantalla
  no tiene red, te dice cómo tenerla antes de mostrar el código. Ya vinculado ves en verde **Conectado** o en ámbar
  **Buscando tu celular…** con el motivo, y dos renglones: **Datos** (el enlace con tu celular) y **Sonido** (el
  Bluetooth, que es por donde viaja el audio).
- Botón **Buscar ahora** para conectar en el momento.
- En Música, "Vincular celular" ahora se llama **Sonido por Bluetooth**, para no confundirlo con vincular LiteDash Connect.

## [1.4.137] - 2026-11-11

### Arreglado
- **Tu celular con LiteDash Connect ahora conecta en cualquier red:** con la pantalla en el hotspot del celular, con
  el celular en el **hotspot de la pantalla** (antes se quedaba en "Esperando a tu pantalla…") o en el mismo Wi‑Fi.
- **Se vuelve a conectar solo:** si el celular llega después, o Android cerró LiteDash Connect, la pantalla lo sigue
  buscando sin que abras Ajustes. Mejor todavía con LiteDash Connect 0.3.2.

## [1.4.136] - 2026-11-11

### Agregado
- **Experimento: LiteDash en Android Auto** (solo la versión de GitHub). Instala LiteDash en tu celular, prende en
  Android Auto los ajustes de desarrollador → **Fuentes desconocidas**, y LiteDash aparece entre sus apps con tu
  tablero de siempre. Por ahora solo con toques (sin deslizar) y sin lo que viene del equipo del auto (OBD, llantas,
  radio). Es una prueba: cuéntanos cómo se ve en tu auto.

## [1.4.135] - 2026-11-11

### Cambiado
- **La animación de entrada viene prendida:** al abrir LiteDash se arma su logo (o aparece tu imagen). Si ya la habías
  apagado, sigue apagada. Se cambia en Inicio y apariencia → Inicio → Animación de entrada; un toque la salta.

## [1.4.134] - 2026-11-11

### Cambiado
- **Animación de entrada nueva:** sin imagen propia, el logo de LiteDash se arma al abrir: el guion entra desde la
  izquierda y los dos puntos caen saltando hasta su lugar. Con tu imagen sigue como antes. Se prende en Inicio y
  apariencia → Inicio → Animación de entrada; un toque la salta.

## [1.4.133] - 2026-11-11

### Cambiado
- **Elegir imagen, un solo camino:** en Android 12 o anterior (casi todas las pantallas de auto) se abre siempre el
  explorador de LiteDash, ahora con **fotos en miniatura** y **Recientes** (las últimas fotos de tu pantalla) antes de
  las memorias. En Android 13 o más nuevo se abre el selector de fotos del sistema. Ya no hay que probar varios
  selectores ni el botón "¿No se abrió?".

### Corregido
- En pantallas con Android 10, el explorador ya puede leer las carpetas de la memoria.

## [1.4.132] - 2026-11-11

### Corregido
- **Elegir imagen (fondo, logo de entrada, widget Imagen) en pantallas con Android 12 o anterior:** te mandaba a
  Play Store ("No es posible acceder") y LiteDash se quedaba trabado hasta forzar su cierre. Ahora abre el
  selector de archivos de tu pantalla o el explorador de LiteDash.

## [1.4.131] - 2026-11-11

### Agregado
- **Calibrar cada llanta** (Ajustes → Llantas → Calibrar): un ajuste por llanta, con lo que marca cada una junto,
  para igualarlas al manómetro. Si ya tenías el ajuste de antes, cada llanta empieza con ese valor.
- **Radio de las pantallas Hengchen/Junsun** (app `com.hcn.autoradio`): el widget Radio ya muestra la estación
  (frecuencia y nombre RDS) y play la enciende aunque su app esté cerrada, sin abrirla.

### Corregido
- **Llantas: ya no avisa "fuga" cuando la llanta solo se enfría.** Al manejar el aire se calienta y la presión
  sube; al estacionarte baja sin perder aire. Ahora LiteDash compara las presiones a la misma temperatura, así que
  solo avisa cuando de verdad pierde aire.
- El reporte de la pantalla trae más datos para leer la canción de ZLINK en pantallas Hengchen.

## [1.4.130] - 2026-11-10

### Agregado
- **La música de tu celular en la pantalla** (con LiteDash Connect 0.3.0): lo que suena en tu celular (Spotify,
  YouTube Music…) se ve en el widget Música con su carátula, título y avance, y lo controlas desde la pantalla
  (play, pausa, siguiente, anterior y adelantar). Llega por el enlace con tu celular, así que funciona aunque tu
  pantalla no muestre la canción por Bluetooth. Se ve con el método Bluetooth (y con App si no eliges una app).

### Corregido
- **Widget Reloj**: en un widget angosto, el clima ya no se encima sobre la hora (si no caben los dos, la hora va
  sola), y en uno de poca altura la hora se achica para que quepan la fecha y el clima sin cortarse.
- Si la conexión con el celular se cae, la pantalla vuelve a intentar sola cada vez más espaciado (hasta cada
  minuto) en lugar de rendirse tras un intento.

## [1.4.129] - 2026-11-10

### Cambiado
- **Ajustes → Cuenta** (nuevo): ahí está el código QR para vincular tu pantalla con la app **LiteDash Connect** de
  tu celular, donde ahora creas tu cuenta de LiteDash (con Google o con tu correo). Ya vinculado, ves si están
  conectados y puedes desvincular. Siempre está disponible: sin celular vinculado no usa la red ni corre nada.
- Ya no hay módulo Celular en Ajustes → Módulos (pasó a Cuenta). Si ya tenías tu celular vinculado, sigue igual.

## [1.4.128] - 2026-11-10

### Agregado
- **Módulo Celular** (Ajustes → Módulos, apagado de fábrica): vincula tu pantalla con la app **LiteDash Connect**
  de tu celular escaneando un código QR. El enlace va cifrado por la red local (el hotspot de tu celular o el
  mismo Wi-Fi), sin cuenta y sin internet. Es el primer paso: por ahora la pantalla y el celular se reconocen y
  muestran si están conectados.

## [1.4.127] - 2026-11-10

### Cambiado
- **Pantalla Música en celulares y pantallas chicas**: la carátula ya no tapa el título ni aplasta los botones. Se
  acomoda sola: a un lado en pantallas anchas, arriba si hay lugar y, en pantallas bajas (un celular acostado),
  chica junto al título con los botones a su lado. Lo mismo en Radio.
- **Widget Música mediano**: ya no pierde la carátula; si en fila no se lee el título, pone la carátula chica junto
  al título y los botones abajo.
- **Ajustes → Apps → Música (Archivos)**: una sola tarjeta "Tu música". "Elegir carpeta" y el nuevo "Quitar carpeta"
  van arriba de la biblioteca (ya no se repiten abajo).

## [1.4.126] - 2026-11-10

### Agregado
- **Cada widget con sus opciones**: al acomodar el inicio, toca un widget y su engrane abre "Personalizar" con
  el widget en vivo. Reloj (clima, fecha, saludo), Clima (máxima y mínima, próximos días), Viaje (qué datos),
  Llantas y Taller (con o sin el auto), Música y Radio (carátula, anterior y siguiente), App (nombre), Teléfono y
  Android Auto (qué app abren) e Imagen (presión de las llantas, apagada de fábrica).
- **Pantalla Música**: un botón en la barra abre la música a media pantalla con tu auto al lado, con Música y
  Radio. Ahí están tu biblioteca y las estaciones; los widgets ya no traen listas.
- **Barra editable**: al acomodar el inicio, quita botones con su ✕ o mantén y arrastra para cambiarlos de lugar.
  "Barra" agrega botones (Navegar, Teléfono, Voz…) y apps, y cambia su lugar, tamaño y estilo. Botón **Fondo** al
  acomodar.

### Cambiado
- **Cuadrícula el doble de fina** y los widgets **se hacen a un lado** al arrastrar uno encima (o intercambian
  lugar). Al acomodar, solo el widget que tocas muestra sus botones. Tus acomodos se conservan.
- **Barra nueva**: flotante al centro (o a lo ancho). De fábrica: Apps, volumen, Música, Navegar, voz y Ajustes. El
  volumen es una barra que flota hacia arriba. Ya no hay botón de hora (va arriba).
- **Widgets rediseñados**: medidores de una fila con anillo y velocímetro con arco; Viaje, Clima (con el color del
  cielo), Reloj, Llantas (con su estado), Taller (anillo de salud), Música (la carátula tiñe la tarjeta), Radio
  (frecuencia grande), Mapa (la vuelta en una tarjeta) y accesos con su color. Los números se ajustan al tamaño.
- **Agregar al inicio** por grupos, con lo que hace cada widget.
- **Velocímetro en grande** con el aro más grueso; el aro Neón con brillo difuminado.
- Ajustes: sale la sección "Barra de apps" (ahora se edita en la barra misma).

### Corregido
- El widget Imagen mostraba la presión de las llantas sin pedirlo.
- Con la interfaz escalada, Agregar dejaba una columna vacía.

## [1.4.125] - 2026-11-09

### Cambiado
- **Volumen en un solo botón**: en la barra ahora es una bocina con el nivel; al tocarla se abre su barra para
  deslizar (con − y +) y se cierra sola a los 4 segundos.
- **Tus apps favoritas, al centro de la barra.**
- **El botón de voz viene apagado**: si lo quieres, préndelo en Ajustes → Inicio y apariencia → Barra de apps.
- **El reloj ya no repite el clima** si tienes el widget Clima en el inicio, y ahora tiene ajustes: al acomodar,
  su engrane deja quitarle el clima.

## [1.4.124] - 2026-11-09

### Corregido
- **Widgets que no se podían quitar ni mover**: con LiteDash abierto dos veces (como inicio y como app), después
  de cambiar el tamaño de la interfaz el inicio quedaba con el acomodo de otro tamaño y, al acomodar, la ✕, mover
  y cambiar de tamaño no hacían nada. Ahora el acomodo lo decide la ventana que estás usando.

## [1.4.123] - 2026-11-09

### Agregado
- **Elige tu música desde el widget**: con Archivos, si el widget de Música es suficientemente grande trae un
  botón de lista (arriba a la derecha) con tu biblioteca: carpetas, artistas, álbumes y todas. Tocas una canción
  y regresa a lo que suena. El widget de Radio por internet hace lo mismo con las estaciones.

### Corregido
- **Taller: códigos de falla incompletos**: con tres o más códigos solo salían los dos primeros. Además, con los
  adaptadores que mandan encabezados (los que antes solo daban voltaje) ya se leen los códigos, se borran y sale
  el número de serie.
- **El escáner se reconecta de verdad**: si se caía (auto apagado un rato) lo intentaba una sola vez; ahora hasta
  cinco, y abrir el Taller lo vuelve a conectar.
- **Radio por internet**: tocar la estación que ya suena (o play en el volante) ya no la corta y la vuelve a
  conectar; las estaciones empiezan sin esperar a que baje su logo; en pausa ya no se queda "en diferido".
- **Tu app de música**: al cambiarla, el widget ya no sigue mostrando ni controlando la anterior.
- **Radio sin enlazar**: play ahora lleva a Ajustes → Apps → Radio (antes caía en Música).
- **Viaje**: el tiempo y el promedio ya no se vuelven locos cuando la pantalla corrige su reloj al tener internet.
- **Recordatorios**: "Hecho" en uno muy atrasado lo pasa a la siguiente fecha que sí falta.
- **Noche por sensor de luz**: si el sensor se daba por descompuesto (mucho rato con la misma luz) ya no se
  volvía a usar hasta reiniciar.
- **Vincular celular**: "Buscando…" ya no se queda pegado, el permiso se nota al darlo y el celular recién
  emparejado aparece en Guardados.
- **Tu auto**: un cuadro dañado del auto ya no cierra la app; subir a calidad Alta con un modelo 3D nuevo
  pendiente primero lo actualiza (antes mezclaba cuadros); el aviso de "modelo 3D nuevo" ya no se pierde al
  reabrir; las descargas cortadas ya no dejan archivos ocupando espacio.
- **Llantas en Salud**: muestra el problema más grave (una fuga antes que una pila baja).
- **"Hazla tu pantalla de inicio"** se quita al volver de elegirla en Android 9 o anterior.
- Plantilla Tarjetas en pantallas angostas: ya no pone widgets más chicos que su mínimo.

### Cambiado
- Auditoría completa del código: menos trabajo repetido al dibujar velocímetros y el mapa, la lista de archivos
  ya no lee la memoria mientras dibuja, el mapa guarda la mitad en disco (hasta ~50 MB entre sus dos estilos) y
  limpieza de código sin uso.

## [1.4.122] - 2026-11-09

### Corregido
- **Escáner OBD que solo mostraba el voltaje**: algunos adaptadores ELM327 (clones) mandan cada respuesta con
  su encabezado aunque se les pida que no; LiteDash no entendía esas respuestas y solo leía el voltaje del
  adaptador. Ahora las entiende. Con los adaptadores que ya funcionaban no cambia nada.
- **Música y Radio a media altura sin carátula**: en ese tamaño el widget dejaba de mostrar la carátula; ahora
  se achica para caber.
- **Radio sin "Sin datos de la canción"**: abajo de la estación ya no sale ese texto.

### Cambiado
- **Un toque a Música, Radio o Mapa ya no los abre a media pantalla** (confundía). Los botones siguen igual; el
  mapa aleja el zoom con un toque. Si el widget ocupa media pantalla o más, se ve completo ahí mismo.
- **Tu música en Ajustes**: con Archivos, en Ajustes → Apps → Música está tu biblioteca (carpetas, artistas,
  álbumes y todas) para elegir qué escuchar.
- **Favoritas en la Barra de apps**: pasaron de Apps a Inicio y apariencia → Barra de apps y solo aparecen si
  están prendidas. Ahí mismo se quitan (x) y se agregan con el buscador.
- Los reportes del Taller traen los datos del escáner OBD (adaptador, protocolo y qué contesta el auto).

## [1.4.121] - 2026-11-09

### Agregado
- **Honda Pilot 2003–2005 en el catálogo** (pedida en Ko-fi). Aparece en Tu auto → Modelos sin actualizar.

### Corregido
- **La foto de un auto del catálogo se actualiza cuando el auto se rehace**: antes, si ya la habías visto,
  se quedaba la foto vieja (la RAV4 2009–2012 seguía con los faros negros en la lista aunque el auto bajado
  ya venía bien).

## [1.4.120] - 2026-11-09

### Agregado
- **Autos nuevos en el catálogo** (pedidos por quienes donaron en Ko-fi): **Toyota Corolla 2011–2013** y
  **Peugeot 301 2013–2016**. No hace falta actualizar para verlos: aparecen en Tu auto → Modelos.

### Mejorado
- **Cada auto dice los años en que se ve igual** (su generación), por ejemplo "RAV4 2016–2018", en vez de un
  solo año. Si buscas un año, como "2017", te salen los autos que se ven igual ese año.

## [1.4.119] - 2026-11-09

### Agregado
- **Llantas en la carretera y en el auto 3D, a tu gusto**: al acomodar tu inicio, el botón de ajustes de
  la carretera tiene "Ver la presión de las llantas", y el auto 3D ahora también tiene su botón de ajustes
  para lo mismo (y si van en las ruedas o en las esquinas). Lo de Ajustes → Llantas sigue aplicando a todos.
- **El widget Llantas siempre está en "Agregar"**: antes solo salía si ya tenías sensores. Agregarlo prende
  Llantas con tus últimos sensores o, si nunca los configuraste, te lleva a elegirlos.

### Mejorado
- **Widgets con menos margen por dentro**: el ícono del widget App ahora llena su tarjeta (con fondo o sin
  él), y Viaje, Clima, accesos, medidores, Salud, Llantas y Música aprovechan más su espacio.

## [1.4.118] - 2026-11-09

### Corregido
- **La radio FM de las pantallas NWD ya se controla desde LiteDash** (Allwinner K2401, T3 y otras con la
  app `com.nwd.radio`): LiteDash ahora le habla directo al sintonizador de la pantalla, como Radio+ y
  OpenRadioFM. Siguiente y anterior buscan la estación, se ve la frecuencia y el nombre de la estación, y
  play/pausa cambian a la radio sin abrir su app. Antes ningún botón hacía nada y play solo abría la app.
- Si tu pantalla NWD no deja hacerlo, todo sigue como antes (play abre la radio). Si te pasa, manda un
  reporte de Radio desde Ajustes → Sistema → Tu pantalla con la radio sonando.

## [1.4.117] - 2026-11-09

### Mejorado
- **Actualizar ya no falla por un tropiezo de GitHub**: si GitHub no responde un momento (como los
  "504" de hoy) o se corta la red, LiteDash lo vuelve a intentar solo, hasta tres veces. Lo mismo al bajar
  autos del catálogo.
- Si la descarga sí falla, el aviso lo dice ("No se pudo descargar") en vez de "No se pudo revisar".

## [1.4.116] - 2026-11-09

### Mejorado
- Más limpieza en música y radio: tu app de música (método App) se guarda en un solo lugar (se pasa sola
  al actualizar) y se quitó lo último que ya no se usaba. Nada cambia en lo que ves.

## [1.4.115] - 2026-11-09

Auditoría de música y radio: arreglos y limpieza.

### Corregido
- **La música del celular por Bluetooth (sin app) ya no se pierde** después de abrir la música o la radio
  en grande: se vuelve a conectar sola.
- **La pausa de la radio ya no pausa tu música**: si suena otra app, LiteDash no da la radio por sonando
  ni le manda la pausa al sistema.
- **Con Archivos o App, la pieza Música ya no muestra el Bluetooth** de la pantalla, y al dejar Archivos
  la USB se detiene (antes un play podía hacerla sonar con otro método).
- **El volante controla la música** aunque la radio de la pantalla muestre su estación en pausa.
- Al pasar la radio a internet, la FM de la pantalla suelta el audio (no suenan las dos).
- "Abrir la app de Bluetooth" funciona también con la app de Bluetooth de la marca sin enlazar.
- El widget Radio por internet ya no pide el acceso a notificaciones.
- El reporte del Taller dice "USB" otra vez (decía "Archivos").

### Mejorado
- Código más limpio en música y radio: se quitó lo que ya no se usaba y lo repetido.

## [1.4.114] - 2026-11-09

### Corregido
- **Radio que solo suena abriendo su app** (NWD sin control y otras): al dar play se abre la radio, se
  regresa solo y ahora el widget queda sonando (botón de pausa y la radio a la vista), en vez de quedarse
  como si nada. La pausa ya no vuelve a abrir la app: se manda como el botón del volante.

## [1.4.113] - 2026-11-09

### Corregido
- **"Ocultar la barra de Android" se mantiene**: al volver de otra app, cerrar los avisos o prender la
  pantalla, la barra se vuelve a ocultar sola (en algunos teléfonos, como Samsung, regresaba y había que
  apagar y prender la opción).

## [1.4.112] - 2026-11-09

### Corregido
- **Abrir LiteDash al encender** ahora funciona en pantallas sin pila de reloj (las que prenden con la
  fecha en 1970 o 2015 hasta tener internet): antes podía no abrirse nunca.
- **Ajustes nunca se sale de la barra**, aunque la barra sea grande y la pantalla baja: si no cabe todo,
  se recorta lo demás.
- **Radio FM de las NWD:** play ya no se da por bueno con cualquier audio que suene; si la radio no
  tomó el audio, se abre su app como antes.

### Mejorado
- La opción "Hora en la barra" solo aparece con la barra a un lado (abajo no aplica).
- El reporte dice si "Abrir al encender" no pudo abrir por falta del permiso.

## [1.4.111] - 2026-11-09

### Mejorado
- **Una sola radio a la vez**, como la música (Ajustes → Apps → Radio): eliges "FM de la pantalla" o
  "Por internet" y esa es la que suena en el widget Radio. Ya no hay que escoger en el widget.
- **La opción elegida lleva palomita** en Música (Archivos, Bluetooth o App) y en Radio.

## [1.4.110] - 2026-11-09

### Nuevo
- **Barra de apps a tu gusto** (Ajustes → Inicio y apariencia → Barra de apps): a la izquierda, a la
  derecha o abajo; compacta, normal o grande; el volumen al final, al principio o sin él; y puedes
  quitar la hora, el botón de todas las apps, las favoritas o el de voz. Ajustes siempre se queda.

### Corregido
- **Los botones de volumen de la barra** ahora funcionan en pantallas que manejan el volumen con su
  propio chip (pasaba en una NWD): si Android no cambia el volumen, LiteDash aprieta la tecla de
  volumen como el botón físico. Ahí no se muestra el número, porque el de Android no es el real.

## [1.4.109] - 2026-11-09

### Mejorado
- **Radio FM de las pantallas NWD** (`com.nwd.radio`, Allwinner T3/T5 y otras): play y pausa sin abrir
  la app de radio, siguiente/anterior y la estación (frecuencia y nombre RDS) en el widget Radio. Si la
  pantalla no responde, se abre la radio como antes. Gracias al proyecto OpenRadioFM por la información.

## [1.4.108] - 2026-11-09

### Nuevo
- **Abrir LiteDash al encender** (Ajustes → Inicio y apariencia → Hazlo tu inicio): para pantallas que
  regresan su launcher de fábrica cada vez que apagas y prendes el auto. Al encender, LiteDash se pone
  al frente solo. Desde Android 10 pide el permiso "Mostrar sobre otras apps". Apagado de fábrica.
- El reporte dice cuál es el inicio predeterminado de tu pantalla y qué pasó al encender, para saber
  qué marca lo cambia.

## [1.4.107] - 2026-11-08

### Cambiado
- **Música: un solo método.** En Ajustes → Apps → Música eliges cómo escuchas: **Archivos** (antes
  "USB": la música de tus memorias con el reproductor de LiteDash), **Bluetooth** (tu celular) o
  **App** (tu app de música). Solo ese suena en el widget Música; al cambiar, el anterior se pausa.

### Corregido
- **Ya no se cierra la app al elegir una imagen** en pantallas sin galería ni "Archivos": si tu
  pantalla no trae el selector, LiteDash abre su propio explorador de la memoria.
- **"Elegir carpeta" de la música ya abre** en pantallas sin el selector de Android (antes no pasaba
  nada): se abre el explorador propio.
- **Bluetooth con título y artista en pantallas TopWay** (y otras con varias sesiones): LiteDash ahora
  toma la sesión que sí trae la canción, no la primera vacía. Si la música por Bluetooth estaba
  enlazada a otra app (como ZLink), se cambia sola a la app de Bluetooth de tu pantalla.
- La música por el Bluetooth de Android ya no depende del permiso de ver el nombre del celular.
- La carátula por internet se vuelve a buscar cuando regresa la señal.

## [1.4.106] - 2026-11-08

### Mejorado
- **Una sola música a la vez:** en la música en grande, las fuentes (USB, tu app y Bluetooth) ahora
  son pestañas claras y solo una suena: al tocar otra, la que estaba sonando se pausa sola. Antes
  podía quedar, por ejemplo, Spotify sonando a la vez que el Bluetooth.

## [1.4.105] - 2026-11-08

### Mejorado
- **Música por Bluetooth más clara:** Ajustes → Apps → Música ya no pregunta "elige app o enlaza
  tu celular"; ahora solo está "Vincular celular", que se adapta a tu pantalla: con el Bluetooth
  de Android te deja emparejar (o quitar) el celular, y con su propia app de Bluetooth te abre esa
  app (y, si no la reconoce, te deja elegirla). Se aclara que el título y el artista los manda la
  pantalla, no LiteDash.

### Corregido
- En pantallas con chip de Bluetooth propio, LiteDash ya no confunde la app de música con la de
  radio: prefiere las apps de Bluetooth de marca conocida.

## [1.4.104] - 2026-11-07

### Arreglado
- Se quita una línea de diagnóstico interno que salió por error en la 1.4.103. Sin cambios para quien viene de la 1.4.102.

## [1.4.102] - 2026-11-07

### Nuevo
- **Carátula para la música por Bluetooth:** si el celular no manda la carátula de la canción (pasa
  con el Bluetooth), LiteDash la busca por el título y el artista en iTunes Search y la guarda para
  volver a verla sin internet. Si no hay carátula, todo queda igual que antes.

## [1.4.101] - 2026-11-07

### Nuevo
- **Bluetooth sin app externa (Bluetooth de Android):** en pantallas que usan el Bluetooth de
  Android, LiteDash ahora lee la música del celular directo (título, artista, álbum y duración) y
  la controla desde la música en grande — play, pausa, siguiente y anterior — sin abrir la app de la
  pantalla. Ajustes → Apps → Música → "Vincular celular" para emparejar (u olvidar) tu celular,
  hacer visible la pantalla y buscar celulares cerca. En pantallas con chip propio todo queda igual:
  se enlaza su app de Bluetooth.

## [1.4.100] - 2026-11-06

### Mejorado
- **Los widgets crecen lo que quieras mientras quepan:** ya no tienen tamaño máximo (la música y la
  radio se quedaban en 3 filas y en pantallas verticales regresaban a su tamaño al soltarlas). Lo que
  ves al arrastrar la esquina es lo que queda. El auto y la carretera ahora se pueden achicar hasta 2×2.

### Corregido
- **El acomodo del inicio ya no "se reinicia"** cuando la pantalla cambia un poco de tamaño (una barra
  del sistema que aparece o se va): LiteDash usa tu último acomodo, ajustado, en vez de la plantilla.

## [1.4.99] - 2026-11-06

### Nuevo
- **Acabado de la pintura:** Mate · Satinado · Brillante · Metálico, con un deslizador de brillo, en
  Ajustes → Tu auto → Mi auto, junto al color. Satinado es el de siempre. Los otros bajan una vez una
  capa ligera (menos de 1 MB) para tu modelo; el giro va igual de rápido.

## [1.4.98] - 2026-11-06

### Cambiado
- Tocar el auto para ver las llantas vuelve al fundido de antes: la animación de la 1.4.97 no se veía bien.

## [1.4.97] - 2026-11-06

### Mejorado
- **Tocar el auto para ver las llantas, con animación nueva:** el auto se inclina como si la cámara
  subiera y llega al auto visto desde arriba. Todo lo hace la tarjeta gráfica con imágenes ya
  cargadas (antes se cambiaban fotos y a veces se trababa). El auto de arriba se prepara solo,
  un momento después de ver el inicio, para que el primer toque no espere.

## [1.4.96] - 2026-11-06

### Nuevo
- **Código de desarrollador** en Ajustes → Sistema → Tu pantalla, para quienes prueban versiones en desarrollo.

## [1.4.95] - 2026-11-05

### Cambiado
- El catálogo ya no tiene el botón "Pedirlo": los autos nuevos se agregan en el orden de quienes
  apoyan el proyecto en Ko-fi.

## [1.4.94] - 2026-11-05

### Mejorado
- **Catálogo de autos con filtro por marca.** Toca una marca y ves sus modelos; con "Todas", el
  catálogo completo agrupado por marca, sin tener que escribir. Los autos que ya bajaste también
  salen ("Descargado") y con un toque los eliges.
- **¿No está tu auto?** Si no aparece, toca "Pedirlo" y nos llega el pedido para agregarlo.

## [1.4.93] - 2026-11-05

### Arreglado
- **Cambiar de idioma con LiteDash abierto dos veces** (como inicio y como app) ya cambia el idioma
  de verdad: antes a veces solo cambiaban las unidades y los textos se quedaban en el idioma anterior.

## [1.4.92] - 2026-11-05

### Arreglado (mantenimiento)
- **Cambiar de idioma ya no deja una franja abajo ni cambia tu acomodo.** Al cambiar el idioma,
  el inicio se armaba un poco más bajo (sin la orilla de abajo) y, con ese otro alto, mostraba otro
  acomodo de widgets. Ahora queda igual que siempre y sin repetir la animación de entrada.

### Mejorado
- El inicio arranca un poco más rápido: los autos bajados se leen en segundo plano mientras se arma
  la pantalla, y se actualizó la guía de arranque de la app.
- Por dentro: la descarga verificada de autos y actualizaciones es una sola.

## [1.4.91] - 2026-11-05

### Arreglado (auditoría de la 1.4.86 a la 1.4.90)
- Radio TopWay: si pausas, cambias a internet o quitas la FM justo después de dar play, ya no se
  vuelve a abrir la radio ni dice "sonando" con el audio apagado. La radio solo se da por encendida
  cuando el sintonizador lo dice (antes se podía confundir con la propia orden de LiteDash).
- Prender o apagar la radio por internet ya no borra la estación FM si la FM sigue siendo la escogida.
- Música: el play de respaldo ya no se confunde si cambias de app en esos segundos, ya no se salta
  la app si el widget mostraba otra en pausa, y la app se abre una sola vez.

## [1.4.90] - 2026-11-05

### Arreglado
- **Música: play ya despierta a Spotify (y apps parecidas) aunque esté dormida.** A veces la app
  decía que reproducía pero no sonaba hasta abrirla. Ahora, si a los 4 segundos no suena nada,
  LiteDash la abre, regresa solo al inicio y le vuelve a dar play.
- Los reportes guardan más eventos (60) y la lista de estaciones de la radio ya no los llena.

## [1.4.89] - 2026-11-05

### Arreglado
- **Mapa inclinado: ya llega hasta arriba.** En widgets cuadrados o altos el mapa se acababa a media
  vista y arriba quedaba solo cielo. Ahora se dibuja hasta la orilla de arriba (lo lejano con menos
  detalle) y solo se desvanece al final.

## [1.4.88] - 2026-11-05

### Cambiado
- Radio TopWay: al dar play también se pide la fuente de audio de la radio, como lo hacen otras
  apps libres para estas pantallas; así es más seguro que encienda la FM sin abrir su app.

## [1.4.87] - 2026-11-05

### Cambiado
- **Radio de pantallas TopWay: play ya no abre la app de radio.** LiteDash le pide el audio al
  sintonizador como lo hace la radio de fábrica, y la estación suena desde el inicio. Si tu pantalla
  no contesta en 2 segundos, abre la radio como antes y regresa sola. Pausa usa el mismo código que
  tu pantalla manda.

## [1.4.86] - 2026-11-05

### Arreglado
- **Radio: "Por internet" y "Quitar" ya hacen caso.** Al escoger Por internet (o quitar la radio de
  tu pantalla) el widget seguía mostrando la estación FM con el botón de pausa, y play y pausa le
  seguían hablando a la FM. Ahora se olvida de la FM en ese momento y suena la de internet.

## [1.4.85] - 2026-11-05

### Cambiado
- **Más ligera: de 1.75 a 1.71 MB.** Una librería chica de dibujo ahora va comprimida dentro de la
  APK; antes cada copia se alineaba a 16 KB y el relleno pesaba más que ella.
- Abre un poco más rápido: el perfil de arranque se volvió a generar.
- Las pastillas de las llantas en las ruedas ya no hacen trabajo de más al girar el auto.

### Arreglado (auditoría de la 1.4.82 a la 1.4.84)
- Llantas por OBD: si pasas el adaptador a otro auto con otro tipo de conexión, ya no deja de leer los
  medidores; la búsqueda de tu marca es una vez por conexión (no cada 5 minutos) y no se reconecta
  sin fin si apagaste el adaptador. Una marca se recuerda hasta que da una presión creíble.
- Llantas por OBD con el auto parado: dice "conectado" aunque los sensores aún no manden nada.
- El aviso "mientras se baja dónde van las ruedas" ya no se queda en autos que no lo traen (la moto).

## [1.4.84] - 2026-11-05

### Agregado
- **Llantas: "Auto (OBD)".** Lee la presión de los sensores de fábrica de tu auto con el mismo
  adaptador OBD del Taller, sin comprar otro kit. Se prueba según la marca (Mitsubishi, Nissan,
  Toyota/Lexus, Hyundai/Kia, Chevrolet/GM, Ford, Mazda y Subaru) y se queda con la que contestó.
  No todos los autos lo dan (muchos de México no traen sensores); si el tuyo no, manda un reporte.

### Cambiado
- **Llantas en las ruedas:** solo salen las de las llantas que se ven; las que tapa el auto ya no se
  muestran (un problema en ellas lo dice el aviso de arriba). En vista de tres cuartos ahora salen
  las dos del lado que se ve.

## [1.4.83] - 2026-11-05

### Arreglado
- **Llantas en las ruedas:** si antes habías tocado "Mostrar", se quedaban en las esquinas; ahora pasan
  solas a las ruedas (quien elija "En las esquinas" las conserva ahí). En Llantas → Inicio un aviso dice
  cuando todavía falta bajar dónde van las ruedas de tu auto (necesita internet una vez).

## [1.4.82] - 2026-11-05

### Agregado
- **Llantas en las ruedas:** la presión de cada llanta va en una fila justo debajo del auto 3D, cada
  pastilla bajo su rueda, y se reacomodan al girarlo (las de las ruedas que tapa el auto, más tenues).
  Es lo de fábrica; en Llantas → Inicio → "Sobre el auto" puedes elegir En las ruedas, En las
  esquinas o No. Dónde pisa cada rueda se baja aparte (unos 3 KB por auto): no tienes que volver a
  bajar tu auto. Mientras no llega (o en la moto), van en las esquinas.

## [1.4.81] - 2026-11-05

### Arreglado (auditoría de la 1.4.79 y la 1.4.80)
- Radio de fábrica sin control: si das play y te vas a otra app desde el inicio antes de 3 segundos,
  LiteDash ya no te saca de ella para regresar. Dos toques seguidos dan un solo regreso.
- Mapa sin GPS: la ubicación por red ya no apaga el velocímetro; solo mueve el punto del mapa.
- Radio TopWay: se conecta sin trabar el inicio, y la estación ya no parpadea cada 10 minutos.
- Los reportes ya no pueden fallar con apps de radio de manifiesto raro.

## [1.4.80] - 2026-11-05

### Agregado
- **Radio de las pantallas TopWay** (TS7, TS10, TS18): el widget Radio muestra la estación (frecuencia y
  nombre) y siguiente/anterior cambian de estación sin abrir la radio. Play la sigue abriendo para que suene
  y pausa la detiene. Si algo no funciona en tu pantalla, manda un reporte de Radio: trae lo que dijo la radio.

## [1.4.79] - 2026-11-05

### Arreglado
- **Mapa en "Buscando tu ubicación…"** en pantallas sin antena GPS (o sin conectar): ahora también usa la
  ubicación por Wi-Fi o red, como Google Maps. Si hay GPS, manda el GPS.
- **Radio de fábrica que no se deja controlar** (p. ej. las pantallas TopWay): play abre la radio para que
  suene y a los 3 segundos regresa solo al inicio.

### Agregado
- **Ajustes → Sistema → Ajustes de Android → Ubicación (GPS)**: atajo para prender o revisar la ubicación.
- Los reportes traen el estado de la ubicación (sin decir dónde estás) y, si tu radio no se deja controlar,
  lo que deja ver para que la próxima versión la controle.

## [1.4.78] - 2026-11-05

### Arreglado (auditoría de la 1.4.77)
- **Color de la interfaz**: los íconos y textos encima del color elegido (play, flecha de navegación,
  esquinas al acomodar) toman el que más contraste (con naranja, ámbar o cian ya no salen blancos). Un
  color casi negro o casi blanco del selector libre se ajusta para que interruptores y barras se sigan viendo.
- La nota "avisos de llantas apagados" sigue en ámbar, y en las gráficas del Taller la segunda línea ya
  no se confunde con la primera si eliges azul o cian.
- **Esfera con aro Neón o Cuadros**: el número del centro ya no se acerca a los de la escala, y el brillo
  del neón ya no se recorta arriba.

## [1.4.77] - 2026-11-05

### Agregado
- **Color de la interfaz** (Inicio y apariencia → Interfaz): elige el color de los interruptores,
  botones, play y barras de avance; las tarjetas y el fondo no cambian. "Natural" regresa al de fábrica.
  Las advertencias (Salud, Llantas) siguen en ámbar para que no se confundan.

### Cambiado
- **Velocímetros más simples**: la **Esfera** es la de fábrica y su aro puede ser **Liso, Neón o Cuadros**.
  Arco y Anillo se quitaron; tus medidores pasan solos a la Esfera (Neón y Cuadros, con ese aro) sin
  perder sus colores ni sus opciones.
- **Inicio y apariencia reordenado** como lo buscarías: **Inicio** (acomodo, barra de apps, barra de
  Android, fondo de pantalla, animación de entrada), **Interfaz** (color, tamaño, idioma, tu nombre,
  clima y modo estacionado), Día y noche, Unidades y, al final, Widgets.

## [1.4.76] - 2026-11-04

### Arreglado (auditoría de la 1.4.72 a la 1.4.75)
- **Música Bluetooth y radio FM**: el botón ya no se queda pegado en play o en pausa cuando la app de
  la pantalla avisa la canción sin decir si suena, o publica la estación sin estado. Cada tecla llega
  una sola vez (algunas apps la tomaban como play/pausa y no cambiaban). Despertar la app ya no
  puede arrancar Spotify al mismo tiempo. Al desconectar el celular deja de mostrarse como "sonando".
- **Enviar un reporte**: ya no puede fallar por casualidad mientras suena la música, ni mandarse dos
  veces con un doble toque. El reporte ya no lista todas tus apps instaladas.
- **Imagen al frente del velocímetro**: "Aplicar a todos" ya no comparte (ni borra) la imagen de otro
  velocímetro, y quitar la imagen ya no deja un hueco.

### Mejorado
- **Mucha menos memoria con imágenes**: el fondo, la imagen de estacionado y las de los widgets se
  cargan al tamaño en que se ven (el fondo de una pantalla de 7" pasó de ~11 MB a ~1.5 MB). Una foto
  muy grande ya no puede cerrar la app en Android 6 y 7, y al irte a otra app LiteDash suelta la
  memoria del auto.

## [1.4.75] - 2026-11-04

### Agregado
- **Al frente del velocímetro: nada, tu auto o una imagen tuya.** Si no tienes auto descargado puedes
  poner una imagen de tu galería (y quitarle el fondo, como en el widget Imagen).

### Arreglado
- Con algo al frente, los números ya no se suben de más ni chocan con la escala; en la Aguja la
  velocidad va dentro del centro y en Barra ya no se corta.

### Cambiado
- Los números al centro de los velocímetros vuelven a ser más grandes (la 1.4.74 los achicó de más).

## [1.4.74] - 2026-11-04

### Arreglado
- **Música Bluetooth y radio FM de la pantalla**: muchas apps de fábrica no le dicen a Android qué suena.
  Antes, play sí sonaba pero el botón seguía en play y no se veía nada. Ahora, al darle play, la pieza
  muestra la fuente y pasa a pausa; pausa y play se mandan como teclas separadas (varias apps de fábrica
  ignoraban la tecla alternada) y también a su sesión aunque esté vacía. Si la app avisa la canción por
  su notificación, se muestra. La radio nunca despierta a otra app (como Spotify) por error.

### Agregado
- **Enviar un reporte** (Ajustes → Sistema → Tu pantalla), en lugar de "Copiar info": eliges con qué hay
  un problema (Música → USB, Bluetooth o tu app; Radio → FM o internet; Llantas, Taller…), qué pasa y,
  si quieres, una nota. Nos llega con datos técnicos de la pantalla, sin datos personales, y te da un
  código del reporte.
- **Velocímetro "Esfera"**: esfera con marcas y números por dentro y el número al centro con brillo.
- **Auto al frente** en el velocímetro: tu auto parado delante de la esfera (con cualquier estilo).
- Se reconoce **Open Headunit** (antes Headunit Revived) como app de Android Auto.

### Cambiado
- Los números al centro de los velocímetros Arco, Anillo, Degradado, Neón y Cuadros son un poco más chicos.

## [1.4.73] - 2026-11-04

### Agregado
- **Nissan Tiida 2010** en el catálogo de autos (sedán, de cualquier color, con su vista desde arriba para
  Llantas y el Taller). El catálogo ya tiene 13 modelos.

## [1.4.72] - 2026-11-04

### Mejorado
- **Mapa inclinado más ligero**: lo lejano (que se ve chico por la perspectiva) se dibuja con menos
  detalle, como los navegadores. Se ve igual y pide muchas menos teselas: en 7" de 64 a 35 y en
  pantallas anchas (1920×720) de 122 a 49. Menos datos, memoria y batería.

## [1.4.71] - 2026-11-04

### Cambiado
- **El mapa minimalista es el de fábrica** (quien eligió "Completo" lo conserva). En Personalizar mapa,
  "Mapa" se llama ahora "Estilo".
- El mapa da crédito completo a OpenFreeMap, OpenMapTiles y OpenStreetMap (en dos líneas si no cabe).

### Arreglado (auditoría del mapa y los widgets)
- **Mapa inclinado**: ya no se queda recargando sin parar y, en widgets altos, ya muestra las calles de
  adelante. El minimalista dibuja hasta 16 veces menos por tesela de cerca.
- El mapa y el GPS descansan cuando algo los tapa (auto en grande, Llantas, estacionado), dejan de
  insistir si el servidor dice que no, y su caché ya no entra al respaldo de Android.
- Acomodar con un widget en grande lo cierra primero; si cambias de plantilla, la media pantalla se cierra
  sola; del otro lado sale el auto 3D o la carretera, como en la pantalla dividida.
- Las plantillas Lateral y Panel ya no dejan huecos en pantallas bajas.
- Carretera mueve sus rayas con la velocidad aunque ocultes los números.
- Llantas aparece cuando el auto de arriba ya está pintado; al acomodar, el botón del mapa compacto ya no
  tapa el de tamaño.
- Quitar fondo calcula una intensidad a la vez (menos memoria en pantallas de 1 GB).
- Arranque más rápido con el perfil de inicio al día.

## [1.4.70] - 2026-11-04

### Cambiado
- Mapa, Música y Radio crecen **en la mitad donde estaban**: si el widget está en la mitad derecha,
  a la derecha; si está a la izquierda o en medio, a la izquierda (en pantallas paradas, arriba o abajo).
- Si el widget ya ocupa media pantalla o más, **no se mueve**: la música y la radio muestran ahí mismo
  lo que traen en grande, y en el mapa el toque aleja el zoom.

## [1.4.69] - 2026-11-04

### Cambiado
- **Navegar y Mapa son uno solo**: el widget Navegar se volvió Mapa (en tu inicio, en el mismo lugar).
  El mapa muestra la siguiente vuelta mientras navegas, abre tu app de mapas y, en una fila o sin
  ubicación, se ve como el Navegar de antes con Casa y Trabajo.
- **Mapa, Música y Radio en grande a media pantalla**: al tocarlos crecen a la mitad y en la otra
  mitad queda tu auto (o la carretera o tu imagen), del lado elegido para la pantalla dividida. El mapa
  en grande trae Casa, Trabajo y tu app de mapas.

### Agregado
- **Plantillas nuevas**: Mapa y auto, Copiloto, Ruta, Música y radio, y Vertical con mapa (pestaña
  Mapa nueva); las de siempre ahora usan el mapa. Las miniaturas muestran el ícono de cada widget.

## [1.4.68] - 2026-11-04

### Agregado
- **Mapa minimalista** (Personalizar mapa → Mapa → Minimalista): solo calles, agua y parques, sin
  íconos ni nombres, en los colores de LiteDash (claro u oscuro), nítido a cualquier zoom y también
  inclinado. Datos de OpenFreeMap (gratis, sin cuenta); LiteDash los dibuja. De fábrica sigue el completo.

## [1.4.67] - 2026-11-04

### Agregado
- **Personalizar mapa** (botón de ajustes del widget al acomodar el inicio), con vista previa en vivo:
  - Orientación: **norte arriba** o **hacia donde vas** (el mapa gira y tu flecha apunta adelante).
  - Vista: **plano** o **inclinado 3D** (el mapa se acuesta hacia el horizonte, como al navegar).
  - Zoom: automático según la velocidad, o fijo cerca, medio o lejos.
  - Colores: como el tema, siempre claros o siempre oscuros.

## [1.4.66] - 2026-11-04

### Cambiado
- **Ajustes: Inicio y Apariencia en una sola sección**, "Inicio y apariencia", justo después de Tu auto,
  con pestañas Acomodo · Widgets · General · Día y noche · Imágenes · Unidades.

## [1.4.65] - 2026-11-04

### Agregado
- **Widget Mapa**: las calles de OpenStreetMap con tu posición y rumbo, zoom según la velocidad (un toque
  lo cambia), modo oscuro y las calles guardadas para verlas sin señal. Pesa unos 16 KB, sin librerías.
- **Quitar el fondo de tu foto** en el widget Imagen: separa el auto de un fondo liso (pared, cielo,
  estudio), con 4 intensidades y vista previa.
- **Qué suena en el Bluetooth de fábrica**: en pantallas cuya app de música Bluetooth no lo comparte,
  LiteDash lo lee de los avisos que mandan (Autochips, Microntek, Syu, Clinkway y otras).

### Cambiado
- Tocar el auto con Llantas activo abre las llantas directo con un fundido suave (la animación de la
  cámara se rehace más adelante). LiteDash usa la mitad de memoria con Llantas activo.

Ideas tomadas del launcher abierto StradaOS (GPL-3.0).

## [1.4.64] - 2026-11-04

### Arreglado
- **Tocar el auto para ver las llantas, fluido de verdad**: el giro entra mientras el auto termina de
  crecer (sin pausa) y a ritmo parejo, y la cámara sube sin brincos: cada imagen se funde con la
  siguiente mientras el auto se acerca o aleja. Antes se veía cuadro por cuadro.
- Abrir y cerrar Llantas se traba menos (el fundido ya no redibuja toda la pantalla aparte).
- Taller: al conectar el adaptador con el widget Salud y el Taller abiertos, los códigos se leen una vez.

### Cambiado
- La app pesa 42 KB menos (sin licencias ni metadatos que no se usan al correr).
- Reconoce **Headunit Revived** (Android Auto por cable en pantallas que no lo traen) en el widget
  Android Auto / CarPlay.

## [1.4.63] - 2026-11-04

### Agregado
- **Carretera con datos del viaje a tu gusto**: distancia, tiempo, promedio y rumbo, cada uno con su
  interruptor (de fábrica, solo la distancia, como antes).
- **Mostrar la velocidad** en Carretera es un interruptor: apágala si ya tienes el widget Velocímetro
  aparte; prendida, eliges solo el número o el velocímetro.

### Arreglado
- El auto en Carretera ya no se ve cortado abajo y va sin reflejo (el reflejo es del auto 3D).

## [1.4.62] - 2026-11-04

### Agregado
- **Carretera**: elige cómo ver la velocidad (solo el número, el velocímetro o nada) desde su botón de
  ajustes al acomodar el inicio, y con Llantas activo, la presión de cada llanta junto a su rueda.

### Cambiado
- **Viaje** ya no repite la velocidad: solo los datos del viaje (y su propio ícono).
- Recordatorios: la fecha se elige ahí mismo, sin ventana emergente.
- El clima se puede apagar (Ajustes → Apariencia) y se actualiza solo mientras ves el inicio.
- Una actualización que se está bajando sigue aunque cierres Ajustes.

### Arreglado
- **Radio FM de la pantalla**: play/pausa, anterior y siguiente ya la controlan como el volante, sin abrirla.
- El cajón y la barra de apps se enteran al instante de apps instaladas o quitadas.
- Ubicación aproximada (Android 12+) aceptada para el viaje, la carretera y el clima.
- Día y noche ya no se queda atorado después de apagar la pantalla.
- Tocar el auto para ver las llantas usa mucha menos memoria (pantallas de 1 GB sin cerrarse) y el giro
  con el dedo ya no se degrada después.
- El reflejo del auto en Carretera ya no se asoma debajo de los widgets de abajo.
- Con las llantas o el OBD apagados, Android ya no ofrece abrir LiteDash al conectar un chip USB.

## [1.4.61] - 2026-11-04

### Cambiado
- **Privacidad**: la radio por internet manda tu ubicación redondeada a unos 10 km para buscar estaciones
  cercanas (antes iba exacta). Política de privacidad al día.

## [1.4.60] - 2026-11-04

### Arreglado
- **Tocar el auto para ver las llantas**: el auto crece y, ya en grande, **gira** hasta quedar de espaldas
  antes de que suba la cámara, fluido y sin parpadeo (los cuadros del giro se preparan antes de tocar;
  antes se veían dos autos encimados).

## [1.4.59] - 2026-11-04

### Arreglado
- **Tocar el auto para ver las llantas ya es fluido**: los cuadros de la subida se preparan con calma
  mientras ves el inicio (antes se preparaban al tocar y la animación se trababa). El auto se funde a
  su vista de espaldas mientras crece, la cámara sube y aparecen las llantas.

### Cambiado
- La barra lateral (y la de abajo) ya no tienen el botón del auto ni el testigo de llantas: el auto
  está en Ajustes y los avisos de llantas siguen arriba del inicio.

## [1.4.58] - 2026-11-04

### Arreglado
- **Música: cambiar de Spotify a USB y regresar**: el primer toque a USB ya toca la música (antes abría la
  biblioteca y Spotify seguía sonando), tocar tu app pausa la USB y regresa a ella, y nada se traba.
- La actualización anterior podía dejar como "tu app de música" una app de la pantalla que LiteDash solo
  había adivinado; ya no.
- **Llantas por Bluetooth**: si el Bluetooth se apaga y se prende (al dar marcha), LiteDash lo dice y vuelve a
  escuchar solo; ya no muestra presiones viejas como si fueran actuales.
- Llantas: cambiar la calibración ya no dispara avisos falsos de fuga; el receptor USB puede quedarse con
  "usar siempre" (no pide permiso en cada encendido); el historial se guarda sin trabar la pantalla.
- El widget Taller ya no dice "Todo bien" sin haber leído los códigos de falla.
- El auto visto desde arriba es del mismo color en el Taller y en Llantas.
- Otros arreglos de la auditoría: reproductor (errores al cargar, aviso en pausa), Bluetooth del celular
  (otros aparatos ya no lo confunden), cable OBD USB desconectado, menos trabajo en segundo plano.

### Cambiado
- **Música**: botones USB · tu app de música · Bluetooth (sin "Apps"). El reproductor USB ya no se prende: siempre está.
- **Módulos**: OBD, Llantas y Cámaras, más la pestaña **Permisos** (Avisos y Ubicación). Un módulo apagado
  **desaparece** del menú, del inicio y de los avisos.
- **Sistema** (antes "Acerca de" y "Ajustes de Android"): Actualizaciones · Ajustes de Android · Estadísticas · Pantalla.
- **Llantas** en pestañas: Llantas · Presión y avisos · Inicio · Diagnóstico. **Apariencia**: General · Pantalla · Unidades.
- En el inicio ahora se llaman **widgets**.

### Nuevo
- **Modo estacionado** con tu propia imagen, además del reloj.

## [1.4.57] - 2026-11-03

### Cambiado
- **Música más simple**: en la música en grande, **USB** es siempre el reproductor de LiteDash (lee tu
  memoria), **Bluetooth** la música de tu celular y **Apps** lo que suene en cualquier app; tu app de
  música sale como botón con su nombre. Ya no se "enlaza una app de USB": si tenías una, se queda
  como tu app de música y no pierdes nada.
- **Ajustes → Apps → Música** en tres partes: Reproductor USB (con su **carpeta de música**: automática
  o la que elijas), Tu app de música y Música por Bluetooth.

## [1.4.56] - 2026-11-03

### Arreglado
- **Música USB en pantallas que no dejan leer la memoria** (p. ej. AC8227L con Android 13): la
  biblioteca ahora tiene **"Elegir carpeta"**. Eliges la carpeta de tu música con el selector de
  Android y LiteDash la recuerda; funciona aunque la pantalla no indexe ni deje leer el USB.
- Con **dos puertos USB**, LiteDash busca la música en todas las memorias, no solo en la primera.

### Cambiado
- Más claro: el botón "Sistema" ahora se llama **"Apps"** (la música de tus apps), y el aviso dice
  "Memoria USB conectada (…): toca USB para escucharla". Si LiteDash ve la memoria pero no puede
  leerla, lo dice así y ofrece elegir la carpeta.

## [1.4.55] - 2026-11-03

### Arreglado
- **Música USB que no sonaba**: en pantallas donde Android no indexa la memoria USB, el reproductor
  de LiteDash ahora busca las canciones en sus carpetas. La primera vez que tocas USB se abre la
  biblioteca (con tus canciones o un aviso claro si no hay), en vez de quedarse en "Sistema" sin hacer nada.
- **Música por Bluetooth de la pantalla**: anterior, siguiente y play/pausa ahora funcionan con las
  apps de Bluetooth que no comparten qué suena (se mandan como los botones del volante), y la pieza
  ya no abre la app en cada toque ("Abriendo música…" que regresaba sin hacer nada).

### Nuevo
- **Toca tu auto y ve sus llantas**: con Llantas activo, al tocar el auto 3D del inicio la cámara
  sube por detrás hasta verlo desde arriba con sus cuatro llantas (otro toque regresa). Se elige en
  Ajustes → Llantas: verlo en grande o ver sus llantas.
- **Módulos más claros**: cada módulo con su interruptor; prendido, una flecha te lleva a sus
  ajustes; apagado, se ve gris.

### Cambiado
- Las vistas de llantas se abren con un fundido simple (sin números que cuentan ni tarjetas que salen).
- **Música en grande**: las fuentes (Sistema, USB, Bluetooth) van arriba junto a los botones; ya no
  queda una franja vacía.
- Con el reproductor USB de LiteDash prendido, la "app de USB" de Ajustes → Apps → Música se ve gris
  y explica que no se usa.
- Las pastillas de llantas ya no salen en piezas de auto o imagen muy chicas (se amontonaban).

## [1.4.54] - 2026-11-03

### Nuevo
- **Llantas (TPMS)**: LiteDash lee la presión y la temperatura de tus llantas sin la app del
  fabricante. Funciona con los **kits con receptor USB** que se venden para pantallas Android
  (los genéricos de 433 MHz con chip CH340, FTDI, CP210x o PL2303) y con las familias más comunes
  de **sensores Bluetooth** (BR/SYTPMS, ZEEPIN/Sysgration, Bekubee y otras). Modo Demo para verlo
  sin sensores. Se prende en Ajustes → Módulos → Llantas.
- **Tu auto desde arriba con sus cuatro llantas**: presión grande, temperatura, estado y su línea
  de los últimos 30 minutos; la que tiene problema brilla. Toca una y ves sus últimas 24 horas.
- **Acomodar llantas**: toca una rueda para emparejar su sensor (receptor USB), asignarlo o dejar
  que LiteDash lo identifique (Bluetooth: desinfla un poco esa llanta), o intercambiarla con otra.
  **Rotar llantas** con un toque: adelante↔atrás, en cruz o izquierda↔derecha.
- **En el inicio, a tu gusto**: la presión en las esquinas de la pieza del auto (también sobre tu
  logo o foto), la pieza **Llantas** en tres tamaños, un aviso arriba y un testigo en la barra
  lateral. Todo se prende y apaga por separado.
- **Avisos**: presión baja, fuga rápida, fuga lenta, temperatura alta, pila del sensor baja y
  sensor sin señal, con tu presión recomendada (la de la etiqueta de la puerta).
- **Unidades a tu gusto** (Ajustes → Apariencia → Unidades): km/h o mph, °C o °F (por separado)
  y psi, bar o kPa. "Automático" sigue como antes.
- Tarjeta **Llantas** en la Salud del Taller.

## [1.4.53] - 2026-11-03

### Nuevo
- **Salud del auto** en el Taller: tu auto visto desde arriba con un punto sobre la parte que
  pide atención (ámbar) o tiene falla (rojo, late despacio), y al lado una tarjeta por sistema
  (motor, combustible y aire, enfriamiento, batería y carga, transmisión, emisiones) con su
  estado, su dato en vivo y una línea de los últimos 90 segundos. Toca una parte con códigos y
  te lleva a ellos. Sin adaptador se ve la misma pantalla apagada, nunca una vacía.
- **Tu auto real desde arriba**: los autos del catálogo traen una vista aérea pintada de tu
  color (un archivo chico aparte que se baja solo la primera vez que abres el Taller; no hay
  que volver a bajar el auto). Sin ella, una silueta.
- La pieza **Taller** del inicio muestra el mini auto con sus puntos y cuántos avisos hay.

### Cambiado
- Taller reorganizado en Salud · Códigos · Sensores · Gráficas · Más (la verificación va con los
  códigos; conexión, alertas, 0–100 y viajes en Más).
- **Códigos** en tarjetas: el código grande en su color, qué significa, qué parte del auto
  señala y si está guardado, pendiente o permanente. Los sensores de oxígeno cuentan como
  emisiones.
- **Sensores** en tarjetas con número grande y su línea de 90 s; los importantes primero.
- **Gráficas** con líneas más finas: toca o arrastra para ver el valor de ese momento.
- **La música y la radio en grande ocupan todo el tablero** en vez de la mitad con las piezas
  apretadas al lado; para verlas junto al auto está la pantalla dividida.

### Arreglado
- La fila de Wi-Fi del adaptador OBD ya no se sale de la tarjeta en pantallas angostas.

## [1.4.52] - 2026-11-03

### Cambiado
- **La radio ya es una pieza aparte.** La pieza Música queda para tus apps (Spotify…), la memoria
  USB y el Bluetooth; la nueva pieza **Radio** controla la radio FM de tu pantalla (play,
  siguiente, anterior y abrirla) y, solo si la activas, la radio por internet. Si tienes las dos,
  en la radio en grande eliges cuál.
- **Ajustes → Apps** tiene ahora pestaña **Radio** (la app de radio de tu pantalla y el interruptor
  de internet) y en **Música** está el interruptor del reproductor USB: ya no hay que ir a Módulos.
- Los mandos al volante controlan la radio cuando es lo que suena.
- Radio por internet: si los servidores de estaciones no contestan a la primera se reintenta, y la
  última lista de "Cerca de ti" se guarda para mostrarla mientras tanto.

## [1.4.51] - 2026-11-03

### Nuevo
- **Reproductor USB propio** (Ajustes → Módulos, apagado de fábrica): LiteDash toca la música de
  tu memoria USB por carpetas, artistas y álbumes, con carátulas, y sigue sonando con Waze encima.
  Se abre desde la música en grande: toca "USB" y, con el botón de lista, su biblioteca. Recuerda
  dónde ibas y para solo si sacas la memoria.
- **Radio por internet** (Módulos, apagada de fábrica): las estaciones de tu zona según el GPS
  (base abierta Radio Browser, sin cuentas), las más escuchadas de tu país si no hay ubicación,
  buscador por nombre y favoritas. Toca "Radio" en la música en grande.
- Los dos usan los mismos controles de siempre (pieza Música, música en grande, volante y el
  aviso de Android) y pesan 39 KB en total. La radio trae su propio decodificador de AAC en vivo
  (el formato de casi todas las estaciones de México), que Android no sabe arrancar por sí solo.
  Por ahora solo se listan estaciones seguras (https).
- En la música en grande, mientras una estación conecta dice "Conectando…", y si falla lo dice
  en palabras; los botones ya no se mueven con nombres largos.

## [1.4.50] - 2026-11-03

### Nuevo
- **Fuentes de música como en un estéreo**: USB, Bluetooth y Radio. LiteDash enlaza cada fuente
  con la app de tu pantalla que la reproduce (la adivina por su nombre; en Ajustes → Apps →
  Música la cambias) y en la música en grande aparecen como botones: un toque y suena esa fuente.
- **Bluetooth con nombre**: cuando el celular no manda la canción, la pieza muestra qué celular
  está conectado (desde Android 12, con el mismo permiso de Bluetooth del Taller).
- **Memoria USB**: al ponerla, la pieza avisa que está puesta y su nombre.
- Play también despierta a los reproductores de la pantalla que no ofrecen su biblioteca
  (les manda el botón de play directo, como el del volante).

### Arreglado
- En Android 6 a 10, la pieza Viaje y la carretera tiraban la app al perder el GPS o apagar la
  ubicación.
- Un cable OBD por USB ya guardado vuelve a pedir permiso al reconectarlo (antes quedaba en
  error hasta tocarlo en el Taller) y, si lo desconectas, el Taller lo nota al instante.
- El consumo ya no pide dos datos al auto cuando con uno basta: los medidores refrescan más rápido.
- La carretera: el efecto de velocidad ya no da un salto al arrancar y se apaga suave al parar.
- El registro de viajes ya no escribe el archivo en el hilo de la pantalla.

### Cambiado
- Auditoría de todo lo nuevo desde la 1.4.32: sin código repetido (enlaces OBD, medidores,
  música), el color "Dinámico" es el mismo en todos los estilos de velocímetro (ámbar al 60 %),
  menos memoria (las fotos de piezas que quitas y la animación de entrada se sueltan) y menos
  trabajo por cuadro en la carretera.

## [1.4.49] - 2026-11-03

### Nuevo
- **Tres piezas en vez de una**: Auto 3D, Carretera e Imagen ya son piezas separadas y puedes
  poner las que quieras juntas (por ejemplo, el auto 3D y la carretera lado a lado). Cada una
  crece a pantalla completa con un toque.
- **Pieza Imagen** con su propia foto: cada pieza de imagen tiene la suya (se elige con el botón
  de ajustes al acomodar, o tocándola si aún no tiene). Sin foto muestra el logo de LiteDash.
- **Pantalla dividida**: nuevo ajuste "Al lado del mapa" para elegir el auto 3D o la carretera.

### Cambiado
- Se quitó "Qué muestra la pieza del auto" de Tu auto: tu pieza se convierte sola en la que
  tenías (carretera o imagen, con tu foto) y la pantalla dividida sigue mostrando lo mismo.
- La pieza de imagen sin foto ya no muestra el nombre de la marca.

## [1.4.48] - 2026-11-03

### Nuevo
- **Funciona desde Android 6.0** (antes pedía Android 8): pantallas viejas y con poca memoria
  ya pueden usar LiteDash completo. Lo que ese Android no tiene, como la pantalla dividida
  (Android 12L o más nuevo), aparece como no disponible.
  - En Android 6 y 7.0 las descargas (autos, actualizaciones) y el clima funcionan aunque el
    equipo no traiga los certificados más nuevos.
  - Ícono propio para Android 6 y 7.

### Arreglado
- El ícono de Wi-Fi o datos de la barra ahora sale desde que abres LiteDash en todos los equipos.

## [1.4.47] - 2026-11-03

### Cambiado
- Por dentro, todo lo que LiteDash guarda en tu pantalla (ajustes, acomodos, recordatorios,
  viajes, autos) ahora usa nombres en inglés. Al actualizar se copia solo: no pierdes nada ni
  tienes que volver a acomodar. El CSV de viajes que exportas trae los encabezados en inglés.

## [1.4.46] - 2026-11-02

### Cambiado
- Estadísticas anónimas: los datos en la nube ahora usan nombres en inglés. Lo que se manda es
  lo mismo y tu pantalla conserva su número.

## [1.4.45] - 2026-11-02

### Arreglado
- Estadísticas anónimas: el número al azar de tu pantalla ya no viaja en el respaldo de Android,
  así una pantalla nueva restaurada desde otra se cuenta aparte, sin juntarse con la vieja.

## [1.4.44] - 2026-11-02

### Nuevo
- **Ocultar la barra de Android** de arriba (Ajustes → Apariencia → Pantalla, apagado de
  fábrica): LiteDash ya trae su hora y señal. Para verla un momento, desliza desde arriba.
- **Estadísticas anónimas** (Ajustes → Acerca de): una vez al día LiteDash cuenta tu pantalla
  con un número al azar, la versión, Android, el idioma y el tamaño de pantalla, para saber cuánta
  gente la usa. Nada de ubicación, cuentas ni datos tuyos, sin librerías extra (+1 KB). Se apaga
  con un toque. Detalle en litedash.web.app/privacidad.

### Arreglado
- **Pantalla dividida con el auto a la derecha y al cambiar de lado (⇄):** en algunos equipos no
  se armaba o se trababa. Ahora el auto espera a quedar en su lado antes de abrir la app, y cambiar
  de lado ya no cierra nada. Los botones del auto bajaron a la esquina para no tapar el viaje.

## [1.4.43] - 2026-11-02

### Nuevo
- **OBD avanzado**, en el Taller, con 3 pestañas nuevas:
  - **Gráficas en vivo:** hasta dos datos juntos (revoluciones, velocidad, temperatura, turbo…)
    de los últimos 90 segundos, cada uno con su escala, su mínimo y su máximo.
  - **0–100:** cronómetro de aceleración (0–60 mph en millas). Te paras, aceleras y mide solo;
    guarda tu mejor tiempo.
  - **Viajes:** con **Registrar viajes** (apagado de fábrica) guarda un resumen de cada viaje
    con el OBD conectado: distancia, tiempo, velocidad promedio y máxima, revoluciones,
    temperatura del motor y rendimiento (km/l). Los exportas a **CSV**. Nada sale de tu pantalla.

## [1.4.42] - 2026-11-02

### Nuevo
- **Pantalla dividida para mapa y música** (Ajustes → Apps → Pantalla dividida, apagada de
  fábrica): al abrir Waze, Maps, Spotify u otra app de mapa o de música desde LiteDash, se abre en
  una mitad y **tu auto** (3D o en carretera) en la otra, sin las demás piezas. Solo con la pantalla
  acostada y en Android 12L o más nuevo. Tu inicio no cambia: al regresar está como lo dejaste.
  - Eliges si el auto va a la **izquierda o a la derecha**, en Ajustes o ahí mismo con el botón de
    las flechas, arriba del auto. El otro botón deja la app en pantalla completa.
- "Copiar info de mi pantalla" dice si tu pantalla permite la pantalla dividida.

## [1.4.41] - 2026-11-02

### Nuevo
- **3 velocímetros elegantes sin aguja** en Personalizar medidor:
  - **Degradado:** un arco de rayitas que se encienden en degradado, con un filo claro por fuera.
  - **Neón:** un arco liso que brilla como luz de neón.
  - **Cuadros:** cuadritos a lo largo del arco que se van llenando.
- **2 colores en degradado:** **morado a azul** y **cian neón**. Al elegir uno de los estilos nuevos
  se ponen solos si seguías con el ámbar.

### Arreglado
- En el velocímetro de **aguja**, los números van por fuera del arco: la aguja ya no los tapa.

## [1.4.40] - 2026-11-02

### Cambiado
- La **animación de entrada** pasó a **Apariencia → Pantalla** y tiene **su propia imagen**, aparte
  de la de la pieza del auto. Sin imagen sale el logo de LiteDash. Si ya la tenías prendida, se
  queda con la imagen que usabas.
- El velocímetro marca **0** en vez de un guion mientras todavía no hay velocidad.

## [1.4.39] - 2026-11-02

### Nuevo
- **Velocímetros y medidores a tu gusto:** al acomodar el inicio, el botón de ajustes de cada
  velocímetro o medidor abre **Personalizar medidor**, con vista previa que se mueve:
  - **5 estilos:** Arco, **Aguja** clásica con números, **Digital** con barra de segmentos,
    **Anillo** completo y **Barra** deportiva tipo luces de cambio.
  - **Color:** ámbar, el de tu auto, **dinámico** (verde, ámbar y rojo según el valor), verde,
    azul, rojo o blanco. Lo que preocupa sigue saliendo en rojo.
  - **Hasta** dónde llega el velocímetro (160, 200, 240 o 320 km/h), **marcas**, **números** y
    **zona roja** (en el tacómetro desde 6 500 rpm).
  - **Aplicar a todos los medidores** de un toque.

### Cambiado
- La carretera ya no usa el ámbar: con velocidad brilla en blanco, y se funde con el fondo sin
  que se note la pieza.

## [1.4.38] - 2026-11-02

### Cambiado
- Las plantillas de velocímetro (Solo velocímetro y Velocímetro con widgets) lo ponen sin tarjeta,
  flotando sobre el fondo.
- El arco de los medidores engrosa con su tamaño: en grande ya no se ve como un hilo.

## [1.4.37] - 2026-11-02

### Nuevo
- **Fondo del inicio** (Ajustes → Apariencia → Pantalla, o en Plantillas → Fondo): una foto tuya
  detrás de todo el inicio, con un velo del tema para que se lea la hora.
- **Piezas sin tarjeta:** al acomodar el inicio, el botón junto a la ✕ quita o pone el fondo de
  tarjeta de cada pieza (el velocímetro, el tacómetro o la hora flotan sobre tu fondo).
- **La carretera se emociona con la velocidad:** entre más rápido vas, más se encienden el
  horizonte y las orillas, y pasan líneas de velocidad a los lados. Parado, tranquila.
- **Plantillas en pestañas:** Auto, Carretera (ya con la vista de carretera), Velocímetro (sin
  auto ni OBD: **Velocímetro con widgets** con la hora y la fecha a un lado, y el clima y el
  viaje al otro) y Fondo, estilo BYD (**Fondo con tarjetas** y **Fondo con columna**, con la
  hora flotando).

### Cambiado
- En "Qué muestra la pieza del auto" queda una sola opción de imagen (tu logo o la foto que
  elijas); la foto a todo lo ancho ahora es el fondo del inicio.

### Arreglado
- La hora en una columna angosta ya no se corta.

## [1.4.36] - 2026-11-02

### Nuevo
- **La pieza del auto muestra lo que tú quieras** (Ajustes → Tu auto → Qué muestra la pieza del
  auto): el **auto 3D**, el **logo** de tu marca, una **imagen** tuya de la galería o la
  **carretera**: tu auto de espaldas en una carretera cuyas rayas avanzan con tu velocidad (OBD o
  GPS), con la velocidad y los km del viaje arriba. Parado no se anima nada.
- **Cuatro plantillas nuevas:** **Solo velocímetro** (grande al centro, nada más), **Auto y
  velocímetro** (el velocímetro al lado del auto), **Deportiva** (el auto con revoluciones,
  velocidad y temperatura del motor abajo) y **Mínima** (la hora en grande y la música, sin auto).
- En las miniaturas de las plantillas, la pieza principal va resaltada aunque no sea el auto.

## [1.4.35] - 2026-11-02

### Nuevo
- **Bluetooth desde el Taller:** si la pantalla lo tiene apagado, un botón lo enciende (y otro
  lo apaga) sin buscar dónde lo esconde el launcher de la marca.
- **Ajustes → Ajustes de Android:** atajos a los ajustes del sistema (todos, Bluetooth, Wi-Fi,
  pantalla, sonido, apps, fecha y hora, idioma, pantalla de inicio), que algunas pantallas
  esconden detrás de su launcher.

### Arreglado
- Con la barra de apps al costado, las piezas de abajo quedaban pegadas al borde en pantallas sin
  barra de gestos; ahora llevan su margen.

## [1.4.34] - 2026-11-02

### Nuevo
- **Luces del auto como señal de noche** (Apariencia → Pantalla → ¿Cuándo es de noche?):
  LiteDash aprende el ajuste con el que tu pantalla avisa de los faros (una foto de los
  ajustes con las luces apagadas y otra con las luces prendidas) y desde entonces el tema
  Día y noche y el brillo siguen a los faros. Si tu pantalla no avisa, lo dice.
- **Escáner OBD por cable USB**: cables con chip CH340, CP210x, FTDI, PL2303 o puerto serie
  estándar; aparecen en el Taller al conectarlos, Android pide permiso una vez y LiteDash
  prueba las velocidades comunes. (Por probar con un cable real.)
- **Nueve datos más del motor** en el Taller: acelerador, gasto de gasolina (L/h), ajustes
  de mezcla corto y largo, presión de gasolina, avance del encendido, tiempo con el motor
  encendido, distancia con la luz de falla y temperatura del aceite. **Tres medidores
  nuevos** para el inicio: acelerador, temperatura del aceite y gasto de gasolina.

### Mejorado
- El consumo en km/l usa el gasto de gasolina del auto cuando lo reporta (más exacto que
  calcularlo con el flujo de aire).

## [1.4.33] - 2026-11-02

### Mejorado
- **Arranque más rápido en pantallas lentas:** LiteDash trae su Baseline Profile (lo que
  se usa al abrir y en el inicio ya viene compilado al instalar, en vez de interpretarse
  las primeras veces). Pesa 32 bytes más.

## [1.4.32] - 2026-11-02

### Mejorado
- **Escáner OBD más aguantador:** una consulta que el adaptador no contesta ya no tira la
  conexión (hacen falta tres seguidas), y si se cae estando conectado (el auto se apagó
  un momento, el adaptador se reinició) se vuelve a conectar solo unas veces, sin abrir
  el Taller.
- **Giro del auto con menos tirones:** el auto se tiñe de tu color renglón por renglón,
  sin armar dos copias enteras de cada cuadro en memoria.
- **Sin cerrarse por falta de memoria** al cargar los cuadros del auto en pantallas
  chicas: si uno no cabe, se queda el anterior y se libera lo guardado.
- Buscar apps o autos ya no repite el mismo trabajo en cada tecla, y la música deja de
  escribir en el disco con cada aviso de Spotify.
- Sensores en vivo del Taller con las mismas unidades que los medidores del inicio.

## [1.4.31] - 2026-11-02

### Mejorado
- **Música en grande sin nada sonando:** ya no es un aviso vacío; se ve como el
  reproductor, con sus controles, y el play reanuda lo último que escuchaste.

## [1.4.30] - 2026-11-02

### Nuevo
- **Pieza Medidor:** un dato del auto por OBD en tu inicio: tacómetro, temperatura del
  motor, gasolina, batería, **consumo en km/l** (calculado con el flujo de aire y la
  velocidad) o **turbo en psi**. En una fila es un número grande con su barra; más alta,
  un arco de tablero. Se pinta de rojo lo que preocupa (motor caliente, poca gasolina).
- **Alertas del motor:** aviso en el inicio si el motor se calienta de más, si la batería
  no carga con el motor encendido (alternador o banda), si el voltaje sube de más o si la
  batería está baja. El calor avisa a los 8 segundos; la batería, si dura un minuto (los
  alternadores inteligentes bajan el voltaje a propósito). "Entendido" lo calla hasta que
  se resuelva. Se apagan en el Taller.
- **Adaptadores Bluetooth LE** (Vgate, Veepeak, OBDLink y similares): en el Taller,
  "Buscar Bluetooth LE"; no hace falta emparejarlos.
- **¿Cuándo es de noche?** (Apariencia → Pantalla): por la hora del sol o por el **sensor
  de luz** de la pantalla, si tiene; cambia el tema Día y noche y el brillo en túneles y
  estacionamientos, sin parpadear bajo un puente.

### Mejorado
- **Tarjetas del inicio** estilo DuduAuto: Navegar con un mapa dibujado (como la carátula
  de la música), música con carátula dentro de la tarjeta y, en tarjetas angostas, solo
  play para que se lea la canción; en tarjetas altas, carátula grande arriba.
- **Velocímetro de una fila** para la fila de abajo, y el acomodo "Tarjetas abajo" ahora
  trae mapa, música y velocidad.
- **Sensores en vivo** del Taller con consumo y turbo; el voltaje lo mide el adaptador si
  el auto no lo reporta.
- **Copiar info de mi pantalla** incluye el sensor de luz y los ajustes de luces de la
  pantalla (para conectar los faros en cada marca).

### Corregido
- "Agregar al inicio" no dejaba ver todas las piezas en pantallas chicas o con la interfaz
  agrandada.
- En español los decimales salen con punto, como en México ("12.6 V", no "12,6 V").
- Con reloj de 12 horas, "a.m./p.m." va en chico junto a la hora grande (antes se cortaba).

## [1.4.29] - 2026-11-02

### Mejorado
- **Auto por defecto:** el contorno de las salpicaderas abraza la llanta parejo y la línea
  del hombro sigue la curva del auto y se desvanece en las puntas.

## [1.4.28] - 2026-11-02

### Cambiado
- **Auto por defecto estilo maqueta:** una sola forma limpia con volumen (luces y sombras),
  nariz redonda, cabina en burbuja y rines lisos; sin manijas, líneas ni faros. Sigue siendo
  un dibujo de la app (no pesa) y se pinta del color que elijas.

## [1.4.27] - 2026-11-02

### Cambiado
- **Auto por defecto con forma de sedán eléctrico:** frente corto, cabina larga con techo de
  cristal y cola corta (el de la 1.4.26 parecía coupé de frente largo).

## [1.4.26] - 2026-11-01

### Cambiado
- **Auto por defecto nuevo:** un sedán concepto de líneas limpias (techo de cristal, barras
  de luz y rines aerodinámicos) en lugar del dibujo anterior. Sigue siendo un dibujo de la
  app: no pesa nada y se pinta del color que elijas.

## [1.4.25] - 2026-11-01

### Mejorado
- **Más ligera:** la app ya no carga textos de ~80 idiomas que traían las librerías de
  Android y que LiteDash no usa (solo español e inglés): de 1.52 a 1.36 MB.

## [1.4.24] - 2026-11-01

### Cambiado
- **Ajustes reorganizados**, cada cosa en un solo lugar: Tu auto abre primero; **Inicio**
  tiene Acomodo y **Piezas** (todas las piezas del tablero, con su explicación); **Apps**
  junta Música, Navegación (con Casa y Trabajo) y tus **Favoritas**.
- **Módulos** ahora son las capacidades extra: escáner OBD, avisos de otras apps, ubicación,
  y pronto llantas (TPMS) y cámaras, cada una con su estado.
- Lo que se elige va en **selectores** (auto o logo, tema, idioma, barra, brillo) y lo que
  se prende o apaga en **interruptores** (animación de entrada, modo estacionado).

## [1.4.23] - 2026-11-01

### Arreglado
- El **velocímetro** se conecta solo al adaptador OBD guardado (antes usaba el GPS hasta
  abrir el Taller).
- **OBD:** al cambiar de adaptador o fallar la conexión ya no queda abierta la conexión
  vieja, y una respuesta tardía ya no desfasa las siguientes.
- En pantallas muy bajas la barra automática va abajo (la lateral no cabía).
- Las pestañas de Ajustes recuerdan la elegida al cambiar de sección.

### Mejorado
- Auditoría: el velocímetro y los sensores del Taller ya no redibujan de más, el logo se
  lee una sola vez y se guarda con tamaño exacto, sensores en °F y mph donde se usan,
  menos código repetido (ajustes, dock y barra lateral, notas de versión) y sin restos.

## [1.4.22] - 2026-11-01

### Agregado
- **Barra de apps a tu gusto** (Ajustes → Inicio): automática, a un lado o abajo.
- **Acerca de renovado:** la versión en grande, todas sus novedades y tu auto de fondo.

### Cambiado
- La **animación de entrada** sin imagen propia muestra el logo de LiteDash.

## [1.4.21] - 2026-11-01

### Cambiado
- **Diseño más limpio** (primer paso del rediseño estilo Apple/DuduAuto): tarjetas lisas
  sin borde y con esquinas más moderadas, colores más suaves en claro y oscuro, y botones
  con esquinas en lugar de píldoras.
- **Barra lateral** en pantallas acostadas: la hora, el estado, el auto, tus apps,
  volumen, voz y Ajustes van a la izquierda y el tablero usa toda la altura. En pantallas
  paradas el dock sigue abajo.

## [1.4.20] - 2026-11-01

### Agregado
- **Logo en vez del auto** (Ajustes → Tu auto → Auto o logo): para quien prefiere no ver
  un auto, el inicio muestra el logo de tu marca. La imagen la eliges de tu galería
  (LiteDash no trae logos de marcas); sin imagen se muestra el nombre de la marca.
- **Animación de entrada:** al abrir LiteDash el logo aparece unos segundos y pasa al
  inicio. Un toque la salta.

## [1.4.19] - 2026-11-01

### Cambiado
- **Pestañas en Ajustes** para no hacer tanto scroll: Apariencia (General · Pantalla),
  Tu auto (Mi auto · Modelos · Recordatorios) y Taller (Conexión · Códigos de falla ·
  Verificación · Sensores).

## [1.4.18] - 2026-11-01

### Cambiado
- **El Taller es una sección propia de Ajustes** (antes se abría desde Módulos). En
  Módulos ahora solo se agrega su **acceso directo al inicio**, que dice si el adaptador
  OBD está conectado y abre el Taller con un toque.

## [1.4.17] - 2026-11-01

### Agregado
- **Taller (escáner OBD):** en Ajustes → Módulos. Con un adaptador ELM327 (Bluetooth
  emparejado o Wi-Fi) lee la luz de falla, el voltaje de la batería y el VIN; los
  códigos de falla guardados, pendientes y permanentes con su explicación en español;
  borra códigos con doble confirmación; dice si pasarías la verificación (luz de falla y
  monitores) y muestra los sensores en vivo que da tu auto. Se reconecta solo al último
  adaptador y solo lee lo que se está viendo.
- **Pieza Velocímetro:** medidor de arco con la velocidad del OBD si está conectado y,
  si no, del GPS.

## [1.4.16] - 2026-11-01

### Cambiado
- **Giro en calidad Alta sin fundido:** el fundido entre cuadros de la 1.4.15 dejaba
  ver el auto doble (fantasma) al girar. Alta vuelve a ser 48 cuadros, nítidos, sin
  tirones.

## [1.4.15] - 2026-11-01

### Mejorado
- **Giro en calidad Alta sin escalones:** entre un cuadro y el siguiente el auto
  se funde según el dedo y al soltar se asienta en el más cercano. En el emulador
  de 7" pasa de ~34 a ~63 cuadros por segundo al girar rápido (y de ~32 a ~58 en
  grande). La calidad Baja gira igual que antes.

## [1.4.14] - 2026-11-01

### Cambiado
- **Calidad del giro: Baja y Alta** (antes Ligera y Media). Bajar a Baja libera
  espacio aunque no haya internet.

### Arreglado
- Las descargas de autos siguen aunque cierres Ajustes, y el auto bajado queda
  elegido. Ya no chocan dos descargas a la vez (el aviso del inicio y Ajustes).
- Si se corta la descarga de la calidad Alta, el auto sigue completo en Baja.
- El aviso de "modelo 3D nuevo" revisa cada auto por separado.

### Mejorado
- Auditoría del auto 3D: el giro en grande es más fluido (cada cuadro se
  decodifica una sola vez), el auto no se repinta detrás de Ajustes, al mover el
  color no se precargan colores de paso y lo instalado se lee una sola vez.

## [1.4.13] - 2026-11-01

### Cambiado
- **Fluidez del giro: Ligera y Media.** La Máxima (96 vueltas) no cabía en
  memoria en pantallas grandes y el giro se iba trabando con el tiempo; si la
  tenías, LiteDash libera ese espacio solo.
- **El auto se ve nítido mientras lo giras** (ya no baja de calidad al moverlo).
- Más memoria para el giro en equipos que la tienen, para que no se trabe.

### Catálogo
- Por ahora solo el Lancer Evolution; los demás regresan uno por uno, revisados.

## [1.4.12] - 2026-11-01

### Arreglado
- **Play con la música cerrada**: el botón de play ya no se queda sin hacer nada.
  LiteDash despierta tu app de música como lo hace Android Auto o el botón del
  volante (desde Android 12, mandarle play directo lo bloquea el sistema), avisa
  "Abriendo Spotify…" mientras tanto y, si no logra que suene, abre la app.
- **Se quedaba en "Abriendo instalador"**: la confirmación de Android ahora la
  abre la pantalla de Ajustes; si no aparece o falla, el botón "Abrir con el
  instalador de Android" instala el archivo descargado de la forma de siempre.

### Mejorado
- **Música → Otra app**: puedes elegir cualquier app como tu música (por
  ejemplo, el reproductor de USB o de Bluetooth de tu pantalla).
- **Copiar info de mi pantalla** incluye qué apps de música y qué sesiones ve
  LiteDash, para ayudarnos a revisar problemas de música.

## [1.4.11] - 2026-11-01

### Mejorado
- **La música en grande ocupa media pantalla**: al tocar la pieza de música, la
  música queda a la derecha y tus piezas se acomodan solas en la mitad izquierda
  (sin cambiar tu acomodo); con la ✕ todo regresa como estaba.

## [1.4.10] - 2026-11-01

### Mejorado
- El giro del auto usa menos memoria y el código quedó más simple (sin la pausa
  al cambiar de color: el auto se pinta al ritmo en que arrastras el selector).

## [1.4.9] - 2026-11-01

### Mejorado
- **Ajustes → Tu auto, en tarjetas**: tu auto (nombre y color), y justo debajo
  las opciones del modelo elegido (modelo 3D nuevo, fluidez, eliminar), sin bajar
  toda la lista.
- **Tus modelos a la vista**: los autos que bajaste aparecen como tarjetas con su
  imagen (en tu color); un toque lo elige.
- **Catálogo con buscador**: los autos aparecen al escribir una marca, modelo o
  año, o con "Ver todos".
- **Actualizar todos** (los que tienen modelo 3D nuevo) y **Eliminar todos los
  descargados**, los dos con confirmación.

## [1.4.8] - 2026-11-01

### Mejorado
- Ajustes más limpio: las tarjetas ya no llevan el ícono grande de fondo.

## [1.4.7] - 2026-11-01

### Arreglado
- **El giro ya no se traba con fluidez Máxima**: si giras más rápido de lo que
  carga cada vuelta, el auto sigue al dedo saltándose las de en medio en vez de
  quedarse quieto.
- El aviso de "modelo 3D nuevo" de tu auto ya llega en menos de una hora (antes
  podía tardar hasta 12 horas).

## [1.4.6] - 2026-11-01

### Arreglado
- **El giro del auto vuelve a ser fluido** con los modelos en alta calidad:
  mientras lo giras se usan cuadros a media resolución (salen al instante) y al
  soltarlo se carga el cuadro en alta calidad. Las imágenes grandes ya no se
  decodifican completas para dibujarlas más chicas.

## [1.4.5] - 2026-11-01

### Mejorado
- **Autos en alta calidad**: Lancer Evolution, Lancer Cedia, Altima, March y City
  tienen un modelo 3D nuevo, más nítido (se nota al verlo en grande, sobre todo
  en pantallas grandes), con su sombra y su reflejo en cada vuelta. LiteDash
  avisa para bajarlo con un toque.
- La sombra y el reflejo se dibujan a más resolución.

### Arreglado
- En modo claro, la sombra del auto ya no se ve con círculos.

## [1.4.4] - 2026-11-01

### Nuevo
- **Modelo 3D nuevo de tu auto**: si el catálogo trae una versión mejor del auto
  que ya bajaste (como el piso con sombra y reflejo), LiteDash avisa en el inicio
  y en Ajustes → Tu auto; un toque la baja con la fluidez que tenías.

### Arreglado
- Al tocar el auto, la animación empieza y termina del tamaño exacto del auto en
  su pieza (ya no se ve chiquito al inicio ni al regresar).

## [1.4.3] - 2026-11-01

### Nuevo
- **Mandos al volante** (Ajustes → Mandos al volante): presiona "Aprender
  botón" y luego el botón del volante; LiteDash lo reconoce y eliges qué hace
  (play/pausa, siguiente, anterior, volumen, silencio, abrir el mapa, música en
  grande, voz, volver al inicio o nada). Usa las teclas estándar de Android, así
  que funciona en la mayoría de las pantallas sin configurar nada; los botones
  sin cambiar hacen lo de siempre. Cualquier botón quita el modo estacionado.
- **Brillo de día y de noche** (Ajustes → Apariencia → Brillo): según la hora
  del sol, sin sensores. Solo en LiteDash, o en todas las apps con el permiso de
  "Modificar ajustes del sistema".
- **Gira tu auto en el inicio**: arrástralo a los lados y gira como en Ajustes,
  con su reflejo en el piso, y se queda hacia donde lo dejes. **Un toque** lo
  agranda con una animación hasta verlo solo, a pantalla completa; otro toque
  lo regresa. Su nombre, color y modelo ahora están en Ajustes → Tu auto
  (también con el botón del auto en el dock).
- **El piso real en cada ángulo**: al girar el auto (en el inicio y en Ajustes →
  Tu auto) cada vuelta trae su sombra y su reflejo hechos en el estudio, igual
  que la vista de siempre. Por ahora el Lancer Evolution (1.32 MB); los demás
  autos del catálogo lo reciben en la siguiente versión.

### Mejorado
- Código limpio de punta a punta (auditoría): menos trabajo al tocar el inicio,
  nada escuchando con otra app al frente, el giro precarga cada vuelta con su
  piso y no repite imágenes en memoria, y el color se guarda al soltar el dedo.
- El clima ya no se enciende solo al activar Viaje: solo si lo activas.

### Arreglado
- Arrastrar sobre una pieza ya no abre "Acomoda tu inicio" por error.
- El brillo "en todas las apps" regresa tu brillo original (también el
  automático) al apagarlo.
- Un botón del volante ya no repite su acción al cambiar de idioma.
- Fijar una app en el dock ya no borra las sugeridas.
- Viaje: el tiempo avanza aunque no haya señal y cuenta desde que arrancas.
- Un acomodo dañado ya no impide abrir el inicio.

## [1.4.2] - 2026-10-31

### Arreglado
- **Música por Bluetooth**: en muchas pantallas el celular no manda el nombre
  de la canción y la pieza Música decía "Nada sonando" aunque sí sonaba. Ahora
  muestra la app (por ejemplo "Bluetooth") con play, pausa, siguiente y
  anterior, y se queda al pausarla para poder reanudarla.

## [1.4.1] - 2026-10-31

### Nuevo
- **Aviso de versión nueva** (descarga de GitHub): al abrir LiteDash revisa
  sola si hay una versión nueva (a lo más dos veces al día, sin quedarse
  revisando) y la avisa arriba del inicio. "Ver" la abre lista para instalar;
  "Después" no vuelve a avisar de esa versión.

### Arreglado
- La pieza Viaje angosta ya no corta la distancia: distancia y tiempo van en
  renglones separados.

## [1.4.0] - 2026-10-31

### Nuevo
- **Saludo con tu nombre** ("Buenas noches, Irving") en la barra y en el reloj.
- **Clima**: temperatura en la barra y pieza Clima con hoy y 3 días
  (Open-Meteo, gratis y sin anuncios). Pide tu ubicación aproximada solo al
  activarlo y se actualiza al volver al inicio, no en segundo plano.
- **Tema por el sol**: claro de día y oscuro de noche, solo.
- **Casa y Trabajo** en Navegar: un toque y Waze o Maps te lleva.
- **Viaje**: velocidad, rumbo, distancia y tiempo por GPS (solo mientras se ve).
- **Modo estacionado**: reloj grande tras 5 minutos sin tocar el inicio.
- **Recordatorios del auto**: servicio, verificación, tenencia, seguro y
  placas, con aviso 7 días antes; al marcarlos como hechos se reprograman.
- **Android Auto / CarPlay**: tu app de proyección (Zlink, AutoKit…) primero en
  el dock y como pieza, a un toque.
- **Botón de voz** en el dock e **íconos de Wi-Fi, Bluetooth y ubicación** en la barra.

## [1.3.5] - 2026-10-31

### Arreglado
- El inicio podía quedarse parpadeando entre dos acomodos (con un reloj en el
  inicio, o al girar la pantalla). Ahora se queda quieto.

### Cambiado
- Ajustes → Módulos muestra solo lo que ya puedes usar.

## [1.3.4] - 2026-10-31

### Arreglado
- El giro del auto no se movía hasta soltar el dedo; ahora sigue al dedo y
  prepara los cuadros siguientes para que se vea fluido.

### Cambiado
- Tocar un auto del catálogo ya no lo baja: abre su vista previa en grande con
  el botón para bajarlo.

## [1.3.3] - 2026-10-31

### Nuevo
- **Ajustes → Tu auto**: elige y personaliza tu auto también desde Ajustes.
- **Fluidez del giro**: Ligera (24 cuadros), Media (48, +0.9 MB) o Máxima
  (96, +2.8 MB). Subir de nivel baja solo los cuadros que faltan; bajar de
  nivel los borra y libera espacio.
- **Vista previa** de cada auto en la lista antes de bajarlo (se baja del
  catálogo, unos KB; la app no crece).
- **Eliminar lo descargado**: quita un auto con un toque y confirmación.

## [1.3.2] - 2026-10-31

### Nuevo
- **Catálogo de autos**: en "Tu auto" puedes bajar un auto en imágenes (el
  primero: Lancer Evolution 2010, 1.2 MB) y girarlo 360°.
- **El auto es el fondo del inicio**: parado sobre el piso, con su sombra y un
  reflejo tenue que pasan por debajo de las tarjetas.
- **Color libre**: además de los 6 colores rápidos, elige cualquier tono,
  intensidad y brillo; el auto cambia en vivo.

### Arreglado
- Teléfono acostado: las piezas salían vacías porque la barra, el aviso y el
  dock ocupaban casi toda la altura. Ahora se compactan.
- Teléfono parado: el aviso "hazlo tu pantalla de inicio" salía cortado.
- La sombra ovalada bajo el auto ya no se dibuja (se veía mal de ¾).

## [1.3.1] - 2026-10-31

### Nuevo
- **Acomodos por tamaño de pantalla**: chicas (7"), medianas (9–10"), grandes
  (11" o más) y verticales, con el recomendado para tu pantalla. Nuevos:
  Tarjetas abajo, Tres columnas y Panel completo.
- **Ajustes rediseñados**: menú de secciones a la izquierda (Apariencia, Inicio,
  Música, Mapa, Módulos, Acerca de) y tarjetas a la derecha.
- Ajustes → Música: elige tu app de música de las que tienes instaladas, o
  "Sistema" para seguir lo que suene (radio, USB, Bluetooth o cualquier app).
- En pantallas chicas, los íconos del dock y de las tarjetas son más grandes.

### Cambiado
- La música ya no muestra botones para cambiar de app: controla y abre la que
  elegiste en Ajustes.
- Si la hora ya está en una pieza, la barra de arriba no deja espacio vacío.
- Si una pieza no cabe al agregarla desde Módulos, avisa y ofrece acomodar.

### Quitado
- La pieza Mapa de la 1.3.0 (pesaba 10 MB): Navegar abre Waze o Google Maps.
  La app vuelve a pesar 1.3 MB.

## [1.3.0] - 2026-10-31

### Nuevo
- **Pieza Mapa**: un mapa propio dentro de LiteDash, oscuro o claro según tu
  tema, centrado en tu auto y girando hacia donde vas. Tócalo para abrir Waze o
  Google Maps; mientras navegas, encima muestra la siguiente vuelta. Se agrega
  en Ajustes → Módulos o con "Agregar" al acomodar; pide tu ubicación solo al
  tocar "Activar". Mapas de OpenStreetMap, gratis y sin cuentas.
- Tu auto por defecto es un sedán nuevo, de líneas más redondas, con cajuela.

### Cambiado
- Ajustes → Mapa ahora solo elige qué app de navegación abren las piezas Mapa
  y Navegar.

### Quitado
- La vista dividida de la 1.2.0 (LiteDash encima del mapa) y su permiso
  "Mostrar sobre otras apps": el mapa ahora vive dentro de LiteDash.

## [1.2.0] - 2026-10-31

### Nuevo
- **Mapa + LiteDash (vista dividida)**: toca Navegar y Waze o Google Maps se
  abre junto a una columna de LiteDash con tu auto y tu música. Toca el mapa
  para verlo completo; una pestañita en la orilla lo vuelve a dividir o te
  lleva al inicio. El botón de inicio siempre regresa a tu tablero. La primera
  vez te pregunta si lo quieres así o solo el mapa (pide "Mostrar sobre otras
  apps" solo si eliges la vista dividida).
- La columna junto al mapa tiene su propio acomodo: deja presionado para mover,
  cambiar de tamaño, quitar o agregar piezas.
- **Siguiente vuelta en el tablero**: mientras navegas, la pieza Navegar muestra
  la flecha, la distancia y la calle que indica Waze o Maps.
- Ajustes → Mapa: qué app de navegación usar, vista dividida o solo el mapa,
  mapa a la izquierda o a la derecha, y mapa grande (2/3) o mitad y mitad.
- **Música en grande**: toca la pieza de música para ampliarla sin salir de
  LiteDash: carátula, barra para adelantar, los botones propios de la app (en
  Spotify: me gusta, aleatorio, repetir), elegir qué app controlar si suenan
  varias, y "Reanudar" lo último que escuchaste sin abrir la app.

## [1.1.0] - 2026-10-31

### Nuevo
- **Buscar actualizaciones** (versión de GitHub): en Ajustes → Acerca de,
  LiteDash revisa si hay una versión nueva, la baja, verifica que llegó completa
  y la instala con tu confirmación. La versión de Play se actualiza desde Play.
- **Idioma** en Ajustes: como el sistema, español o inglés.
- Si LiteDash no es tu pantalla de inicio, un aviso arriba te ofrece elegirla
  (o no volver a preguntar). También está en Ajustes → Inicio.
- **Inicio que tú acomodas**: deja presionado cualquier lugar para mover,
  agrandar, achicar, quitar o agregar piezas. Entre más grande la pantalla, más
  lugares hay. Cada tamaño de pantalla guarda su propio acomodo.
- Tres plantillas para empezar: auto al centro, auto a un lado y vertical.
- Tu auto en pantalla, con nombre y seis colores.
- Música: ve y controla lo que suena en cualquier app (pide acceso a
  notificaciones solo si pones la pieza y tocas "Activar").
- Piezas de Navegar, Teléfono, Reloj grande y accesos a cualquier app.
- Dock con tus apps favoritas (deja presionada una app en el cajón para fijarla)
  y control de volumen.
- Búsqueda en el cajón de apps (sin importar acentos).
- Tema claro, oscuro o como el sistema, y tamaño de la interfaz de 80 % a 150 %.
- Bienvenida de 3 pasos: tu auto, cómo lo quieres y hacerlo tu pantalla de inicio.
- En el cajón, deja presionada una app para fijarla en el dock, ponerla en el
  inicio, ocultarla o ver su información.
- Ajustes → Módulos: qué hay y qué viene (todo apagado hasta que lo uses).
- "Copiar info de mi pantalla" para reportar problemas.

## [1.1.0-beta.1] - 2026-10-31

Versión de prueba con lo mismo que 1.1.0, menos el botón de actualizaciones.

## [1.0.0] - 2026-10-31

### Nuevo
- LiteDash funciona como pantalla de inicio: reloj, fecha y el cajón con todas
  tus apps, en modo oscuro.
- Se acomoda solo en horizontal, vertical y en pantallas de 6" a 11" o más.
- El reloj respeta el formato de 24 horas de tu equipo.
- Ícono Morse (—··, la D de Dash), incluido el ícono temático de Android 13+.
- En español y en inglés.
- No pide ningún permiso, ni siquiera internet.
