# Cambios

Todos los cambios que nota quien usa LiteDash. Formato
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/), versiones
[SemVer](https://semver.org/lang/es/).

## [Sin publicar]

### Nuevo
- **Pieza Medidor:** un dato del auto por OBD en tu inicio: tacómetro, temperatura del
  motor, gasolina, batería, **consumo en km/l** (calculado con el flujo de aire y la
  velocidad) o **turbo en psi**. En una fila es un número grande con su barra; más alta,
  un arco de tablero. Se pinta de rojo lo que preocupa (motor caliente, poca gasolina).
- **Alertas del motor:** aviso en el inicio si el motor se calienta de más, si la batería
  no carga con el motor encendido (alternador o banda), si el voltaje sube de más o si la
  batería está baja. Solo avisa si dura unos segundos. Se apagan en el Taller.
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
