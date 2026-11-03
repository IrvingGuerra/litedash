#!/usr/bin/env bash
# Mide qué tan fluido gira el auto del inicio: cuadros que dibuja mientras se arrastra,
# en su pieza y en grande, rápido y lento. Correrlo después de tocar el giro o las
# imágenes del auto (feature/car) y comparar con la medición anterior.
#
# Uso: tools/spin.sh [-s SERIAL] [X Y]
#   X Y = centro del auto en el inicio, en píxeles (por defecto, el centro de la pantalla).
# Antes: LiteDash instalado como inicio, con un auto del catálogo en calidad Alta.
# En el emulador no debe haber nada usando el GPU de la Mac (Blender renderizando lo
# traba y la medición no sirve): pausarlo con `kill -STOP $(pgrep -if blender)` y luego `kill -CONT`.
set -euo pipefail

SERIAL_ARGS=()
if [ "${1:-}" = "-s" ]; then SERIAL_ARGS=(-s "$2"); shift 2; fi
adb() { command adb ${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"} "$@"; }

PKG=com.irvingguerra.litedash
SIZE=$(adb shell wm size | tail -1 | awk '{print $NF}' | tr -d '\r')
W=${SIZE%x*}
H=${SIZE#*x}
X=${1:-$((W / 2))}
Y=${2:-$((H * 2 / 5))}
REACH=$((W / 4))

adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity >/dev/null
# Pantallas lentas (Android 6) tardan más en pasar la animación de entrada.
sleep 10

# Arrastra de lado a lado [veces] y reporta cuadros por segundo de arrastre.
measure() {
  local label=$1 x=$2 y=$3 ms=$4 times=$5
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for _ in $(seq "$times"); do
    adb shell input swipe $((x + REACH)) "$y" $((x - REACH)) "$y" "$ms"
    adb shell input swipe $((x - REACH)) "$y" $((x + REACH)) "$y" "$ms"
  done
  local stats frames p90
  stats=$(adb shell dumpsys gfxinfo $PKG)
  frames=$(echo "$stats" | awk '/Total frames rendered/{print $4; exit}')
  p90=$(echo "$stats" | awk '/^90th percentile/{print $3; exit}')
  printf '%-22s %5s cuadros  %6.1f por segundo  p90 %s\n' "$label" "$frames" \
    "$(echo "$frames * 1000 / ($ms * 2 * $times)" | bc -l)" "$p90"
}

measure "En la pieza, rápido" "$X" "$Y" 400 5
measure "En la pieza, lento" "$X" "$Y" 900 5
adb shell input tap "$X" "$Y"   # el auto en grande
sleep 2
measure "En grande, rápido" $((W / 2)) $((H / 2)) 400 5
adb shell input keyevent KEYCODE_BACK
echo "Memoria: $(adb shell dumpsys meminfo $PKG | awk '/TOTAL PSS/{print int($3 / 1024) " MB"}')"
