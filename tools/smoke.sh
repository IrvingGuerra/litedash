#!/usr/bin/env bash
# Prueba de humo en TODOS los emuladores o pantallas conectados (Android 6 incluido):
# instala la APK, abre LiteDash, espera, y revisa que siga viva y que no haya tronado.
# Es la pasada mínima antes de publicar: lo que cambia por versión de Android (ramas
# SDK_INT, memoria, red) se prueba a mano en LD_A6; esto solo atrapa lo que se cae.
#
# Uso: tools/smoke.sh [APK]      (por defecto compila playDebug)
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.irvingguerra.litedash
ACT=$PKG/.MainActivity
APK=${1:-}
if [ -z "$APK" ]; then
  ./gradlew -q :app:assemblePlayDebug
  APK=app/build/outputs/apk/play/debug/app-play-debug.apk
fi

serials=$(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
[ -n "$serials" ] || { echo "No hay dispositivos conectados"; exit 1; }

failed=0
for serial in $serials; do
  sdk=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
  name=$(adb -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)
  [ -n "$name" ] || name=$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')
  printf '%-28s API %-3s ' "$name" "$sdk"
  adb -s "$serial" install -r -t "$APK" >/dev/null 2>&1 || { echo "FALLÓ instalar"; failed=1; continue; }
  adb -s "$serial" shell am force-stop $PKG
  adb -s "$serial" logcat -c
  adb -s "$serial" shell am start -W -n $ACT >/dev/null
  # Android 6 en 1 GB tarda en pasar la animación de entrada y armar el inicio.
  sleep 12
  alive=$(adb -s "$serial" shell pidof $PKG 2>/dev/null | tr -d '\r' || true)
  crashes=$(adb -s "$serial" logcat -d -s AndroidRuntime:E | grep -c "$PKG" || true)
  if [ -n "$alive" ] && [ "$crashes" = 0 ]; then
    echo "OK"
  else
    echo "FALLÓ (viva: ${alive:-no}, errores: $crashes)"
    adb -s "$serial" logcat -d -s AndroidRuntime:E | head -40
    failed=1
  fi
done
exit $failed
