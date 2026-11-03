#!/usr/bin/env bash
# Crea los emuladores de la matriz de pruebas.
# Uso: tools/avd.sh            (crea los que falten)
# Imagen: android-35 google_apis_playstore arm64-v8a, salvo la que diga otra en la 7.ª
# columna (LD_A6: Android 6, el mínimo que soporta LiteDash, con 1 GB como las pantallas
# viejas). Bajar la imagen antes: sdkmanager "system-images;android-23;default;arm64-v8a".
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager"
IMG="system-images;android-35;google_apis_playstore;arm64-v8a"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

# name          width height dpi ram_mb cores [imagen]
MATRIX=(
  "LD_HU7_1024x600   1024 600  160 2048 2"
  "LD_HU10_1280x720  1280 720  160 2048 4"
  "LD_HU11_2000x1200 2000 1200 240 4096 4"
  "LD_Ancha_1920x720 1920 720  160 4096 4"
  "LD_Vertical_768x1024 768 1024 160 2048 4"
  "LD_A6_1024x600    1024 600  160 1024 2 system-images;android-23;default;arm64-v8a"
)

for row in "${MATRIX[@]}"; do
  read -r name width height dpi ram cores image <<<"$row"
  if [ -d "$AVD_HOME/$name.avd" ] && [ "${1:-}" != "--redo" ]; then echo "ya existe $name"; continue; fi
  echo no | "$AVDM" create avd -n "$name" -k "${image:-$IMG}" -d "tv_1080p" --force >/dev/null
  cfg="$AVD_HOME/$name.avd/config.ini"
  for k in hw.lcd.width hw.lcd.height hw.lcd.density hw.ramSize hw.cpu.ncore hw.keyboard hw.mainKeys skin.dynamic showDeviceFrame skin.name skin.path hw.device.name; do
    sed -i '' "/^$k=/d" "$cfg"
  done
  # Pantalla de auto: sin teclado físico ni botones de hardware, orientación horizontal.
  {
    echo "hw.lcd.width=$width"; echo "hw.lcd.height=$height"; echo "hw.lcd.density=$dpi"
    echo "hw.ramSize=$ram"; echo "hw.cpu.ncore=$cores"; echo "hw.keyboard=yes"
    echo "hw.mainKeys=no"; echo "skin.dynamic=yes"; echo "showDeviceFrame=no"
  } >> "$cfg"
  echo "creado $name (${width}x${height} @${dpi}dpi, ${ram} MB, ${cores} núcleos)"
done
