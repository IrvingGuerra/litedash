<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/png/litedash-horizontal-sobre-oscuro.png">
    <img src="brand/png/litedash-horizontal-sobre-claro.png" alt="LiteDash" width="420">
  </picture>
</p>

<p align="center">
  <b>El launcher para pantallas Android de auto y moto: 1.8 MB con escáner OBD2, presión de llantas, radio FM, música USB y mapa.</b><br>
  <i>The car launcher for Android head units: 1.8 MB with an OBD2 scanner, TPMS, FM radio, USB music and a map.</i><br><br>
  <b>Android 6.0+ · Gratis · Sin anuncios · GPL-3.0</b><br><br>
  <a href="https://litedash.dev/">Sitio</a> ·
  <a href="https://play.google.com/store/apps/details?id=com.irvingguerra.litedash">Google Play</a> ·
  <a href="https://github.com/IrvingGuerra/litedash/releases/latest">Descargar APK</a> ·
  <a href="https://litedash.dev/compare/">Comparativa</a> ·
  <a href="https://litedash.dev/en/">English</a>
</p>

<p align="center">
  <a href="https://github.com/IrvingGuerra/litedash/stargazers"><img src="https://img.shields.io/github/stars/IrvingGuerra/litedash?style=social" alt="Estrellas en GitHub"></a>
  <a href="https://github.com/IrvingGuerra/litedash/releases"><img src="https://img.shields.io/github/downloads/IrvingGuerra/litedash/total?label=descargas" alt="Descargas"></a>
  <a href="https://github.com/IrvingGuerra/litedash/releases/latest"><img src="https://img.shields.io/github/v/release/IrvingGuerra/litedash?label=versión" alt="Versión"></a>
</p>

<p align="center">
  <b>⭐ ¿Te sirve? Dale una estrella arriba a la derecha:</b> ayuda a que más gente lo encuentre.<br>
  <i>Like it? Star the repo (top right): it helps more people find it.</i>
</p>

<p align="center">
  <a href="https://ko-fi.com/irvingguerradev"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Apóyame en Ko-fi"></a>
</p>

---

## Qué es

LiteDash es la pantalla de inicio (launcher) para las pantallas Android de autos y motos
(*head units*, las "chinas" de 7" a 11"), tablets y teléfonos. **En 1.8 MB trae escáner OBD2,
presión de llantas, la radio FM de tu pantalla, reproductor USB, mapa, velocímetros y tu auto en
3D.** Todo es opcional y viene apagado, para que vuele hasta en una pantalla de 1 GB de RAM: lo
apagado no gasta memoria, batería ni permisos. Tú acomodas tu inicio: deja presionado para mover,
agrandar o quitar widgets.

## Qué trae

| | |
| --- | --- |
| **Escáner OBD2** (Taller) | Adaptador ELM327 por Bluetooth, BLE, Wi‑Fi o USB. Salud del auto, códigos de falla explicados y borrarlos, monitores de verificación, gráficas en vivo, 0 a 100 y registro de viajes. Sin otra app. |
| **Presión de llantas (TPMS)** | Kits con receptor USB, sensores Bluetooth comunes o la presión de fábrica por OBD en algunos autos. Avisos de presión baja, fuga, calor y pila. |
| **Radio FM de tu pantalla** | Controla la radio de fábrica (TopWay, NWD, Hengchen) desde su widget, y radio por internet con las estaciones cerca de ti. |
| **Música** | Reproductor USB propio (carpetas, artistas, álbumes), Bluetooth con carátula y tu app (Spotify, YouTube Music…). |
| **Mapa** | Tu posición en vivo, plano o inclinado, y Waze o Google Maps a un toque. |
| **Velocímetros y medidores** | Por OBD o GPS: esfera, aguja, digital, barra o degradado; tacómetro, temperatura, gasolina, batería, turbo. |
| **Tu auto en 3D** | Catálogo de 15 modelos, del color que quieras, con giro de 360°. |
| **Tu inicio** | Widgets que se mueven y crecen, plantillas para 7", 10" y 11", mandos al volante, día y noche por hora, sensor de luz o faros, pantalla dividida. |
| **Android Auto y CarPlay** | Zlink, AutoKit, CarLink u Open Headunit a un toque. |
| **App del celular** | [LiteDash Connect](https://litedash.dev/connect/) (opcional): lo que suena en tu celular, con carátula y controles. |

¿Cómo se compara con StradaOS, AGAMA o CarWebGuru? → [litedash.dev/compare](https://litedash.dev/compare/)

## What it is (English)

LiteDash is a home screen (launcher) for Android car and motorcycle head units, tablets and phones.
**In 1.8 MB it packs an OBD2 scanner, TPMS tire pressure, your unit's factory FM radio, its own USB
music player, a live map, speedometers and your car in 3D.** Everything is optional and off by
default, so it stays fast on 1 GB units running Android 6.0 or newer. No ads, no tracking, no
account. See the [comparison with other car launchers](https://litedash.dev/en/compare/).

## Instalar / Install

1. Bájalo de [Google Play](https://play.google.com/store/apps/details?id=com.irvingguerra.litedash)
   o el APK de [Releases](https://github.com/IrvingGuerra/litedash/releases/latest). El APK se
   actualiza solo desde Ajustes → Sistema → Actualizaciones.
2. Ábrelo y toca **Hazlo tu inicio**. En muchas pantallas de auto esto también está en Ajustes →
   Aplicaciones → Pantalla de inicio, o en un menú del fabricante para elegir el launcher.

Requiere Android 6.0 o más nuevo. *Requires Android 6.0+.*

**¿Te sirve? Dale una ⭐ al repo:** ayuda a que más gente lo encuentre. *Like it? Star the repo.*

## Compilar / Build

JDK 17 y el SDK de Android con la plataforma 37.

```bash
./gradlew assemblePlayDebug        # versión para Google Play, depuración
./gradlew assembleGithubRelease    # APK para GitHub
tools/avd.sh                       # emuladores de pantallas de auto (1024×600 a 2000×1200)
tools/spin.sh                      # qué tan fluido gira el auto (cuadros por segundo)
tools/measure.sh                   # medir arranque, memoria y fluidez en un equipo conectado
```

Hay dos variantes del mismo código: `play` (Google Play, sin permiso de
internet) y `github` (APK; usa internet solo cuando tocas "Buscar
actualizaciones" en Ajustes, para bajar e instalar la versión nueva).

## Contribuir / Contributing

Lee [`CONTRIBUTING.md`](CONTRIBUTING.md). Los reportes de pantallas, sensores y
cajas CAN son de lo más útil.

## Licencia / License

Código bajo [GPL-3.0](LICENSE). El nombre y el logo de LiteDash no están
incluidos en esa licencia. *The LiteDash name and logo are not covered by the
code license.*

Las recetas para leer la presión de llantas de fábrica por OBD
(`feature/obd/.../FactoryTires.kt`) son derivadas de [OBDb](https://github.com/OBDb),
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), adaptadas. *The factory
tire-pressure recipes are derived from OBDb (CC BY-SA 4.0), adapted.*

El control de la radio FM de las pantallas NWD y Hengchen (`feature/music/.../NwdRadio.kt`, `HcnRadio.kt`) usa
la información de [OpenRadioFM](https://github.com/kapi21/OpenRadioFM) (Apache-2.0) y de
[Radio+](https://github.com/edman01/radio-plus-open) (MIT). *NWD and Hengchen FM radio control is based on
information from OpenRadioFM (Apache-2.0) and Radio+ (MIT).*
