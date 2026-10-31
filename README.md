<p align="center">
  <img src="brand/png/litedash-horizontal-sobre-claro.png" alt="LiteDash" width="420">
</p>

<p align="center">
  <b>Launcher para pantallas Android de auto: ligero, rápido y elegante.</b><br>
  <i>A light, fast and clean launcher for Android car head units.</i><br><br>
  <a href="https://irvingguerra.github.io/litedash/">Sitio</a> ·
  <a href="https://github.com/IrvingGuerra/litedash/releases/latest">Descargar APK</a> ·
  <a href="https://irvingguerra.github.io/litedash/en/">English</a>
</p>

---

## Qué es

LiteDash es una pantalla de inicio limpia para las pantallas Android que se
ponen en el auto, y también para tablets y teléfonos (de 6" a 11" o más).
**Todo es opcional y viene apagado**, para que vuele hasta en una pantalla de
1 GB de RAM: lo que está apagado no gasta memoria, batería ni permisos.

La versión 1.0 trae lo esencial: reloj, fecha y el cajón con todas tus apps, en
modo oscuro. No pide ningún permiso, ni siquiera internet. Los módulos (escáner
OBD, presión de llantas, tu auto en 3D, música, cámaras…) llegarán dentro de la
misma app.

## What it is (English)

LiteDash is a clean home screen for Android car head units, tablets and phones
(6"–11"+). **Everything is optional and off by default**, so it stays fast on
low-end units. Version 1.0 ships the essentials (clock, date and app drawer in
dark mode) and requests no permissions at all, not even internet.

## Instalar / Install

1. Descarga el APK de [Releases](https://github.com/IrvingGuerra/litedash/releases/latest)
   (pronto también en Google Play).
2. Instálalo y elige **LiteDash** como pantalla de inicio. En muchas pantallas de
   auto esto está en Ajustes → Aplicaciones → Pantalla de inicio, o en un menú
   del fabricante para elegir el launcher.

Requiere Android 8.0 o más nuevo.

## Compilar / Build

JDK 17 y el SDK de Android con la plataforma 37.

```bash
./gradlew assemblePlayDebug        # versión para Google Play, depuración
./gradlew assembleGithubRelease    # APK para GitHub
tools/avd.sh                       # emuladores de pantallas de auto (1024×600 a 2000×1200)
tools/medir.sh                     # medir arranque, memoria y fluidez en un equipo conectado
```

Hay dos variantes del mismo código: `play` (Google Play) y `github` (APK).

## Contribuir / Contributing

Lee [`CONTRIBUTING.md`](CONTRIBUTING.md). Los reportes de pantallas, sensores y
cajas CAN son de lo más útil.

## Licencia / License

Código bajo [GPL-3.0](LICENSE). El nombre y el logo de LiteDash no están
incluidos en esa licencia. *The LiteDash name and logo are not covered by the
code license.*
