#!/usr/bin/env bash
# Crea los emuladores de la matriz de pruebas.
# Uso: tools/avd.sh            (crea los que falten)
# Imagen: android-35 google_apis_playstore arm64-v8a. Las de API 27/29 (pantallas
# reales más viejas) se agregan cuando se descarguen con sdkmanager.
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager"
IMG="system-images;android-35;google_apis_playstore;arm64-v8a"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

# nombre        ancho alto dpi ram_mb núcleos
MATRIZ=(
  "LD_HU7_1024x600   1024 600  160 2048 2"
  "LD_HU10_1280x720  1280 720  160 2048 4"
  "LD_HU11_2000x1200 2000 1200 240 4096 4"
  "LD_Ancha_1920x720 1920 720  160 4096 4"
  "LD_Vertical_768x1024 768 1024 160 2048 4"
)

for fila in "${MATRIZ[@]}"; do
  read -r nombre ancho alto dpi ram nucleos <<<"$fila"
  if [ -d "$AVD_HOME/$nombre.avd" ] && [ "${1:-}" != "--rehacer" ]; then echo "ya existe $nombre"; continue; fi
  echo no | "$AVDM" create avd -n "$nombre" -k "$IMG" -d "tv_1080p" --force >/dev/null
  cfg="$AVD_HOME/$nombre.avd/config.ini"
  for k in hw.lcd.width hw.lcd.height hw.lcd.density hw.ramSize hw.cpu.ncore hw.keyboard hw.mainKeys skin.dynamic showDeviceFrame skin.name skin.path hw.device.name; do
    sed -i '' "/^$k=/d" "$cfg"
  done
  # Pantalla de auto: sin teclado físico ni botones de hardware, orientación horizontal.
  {
    echo "hw.lcd.width=$ancho"; echo "hw.lcd.height=$alto"; echo "hw.lcd.density=$dpi"
    echo "hw.ramSize=$ram"; echo "hw.cpu.ncore=$nucleos"; echo "hw.keyboard=yes"
    echo "hw.mainKeys=no"; echo "skin.dynamic=yes"; echo "showDeviceFrame=no"
  } >> "$cfg"
  echo "creado $nombre (${ancho}x${alto} @${dpi}dpi, ${ram} MB, ${nucleos} núcleos)"
done
