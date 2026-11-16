#!/usr/bin/env bash
# Ayudas compartidas de las herramientas de medición (se cargan con `source`).

PKG=com.irvingguerra.litedash

# `-s SERIAL` al principio de los argumentos elige el equipo; sin él, adb usa el único conectado.
# Uso: use_serial "$@"; shift "$SERIAL_SHIFT"
SERIAL_ARGS=()
SERIAL_SHIFT=0
use_serial() {
  if [ "${1:-}" = "-s" ]; then SERIAL_ARGS=(-s "$2"); SERIAL_SHIFT=2; fi
}
adb() { command adb ${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"} "$@"; }

# Espera a que LiteDash tenga la ventana al frente y deje de dibujar (pasó la animación de entrada y
# el inicio quedó quieto), con tope de [segundos] (30 por defecto). Sirve igual en Android 6 lento
# que en una pantalla rápida, sin esperas fijas. Regresa 1 si no llegó a tiempo.
wait_idle() {
  local limit=$(( ${1:-30} * 2 )) last=-1 same=0 frames
  for _ in $(seq "$limit"); do
    if adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"; then
      frames=$(adb shell dumpsys gfxinfo "$PKG" 2>/dev/null | awk '/Total frames rendered/{print $4; exit}')
      # Quieto = el número de cuadros no cambió en un segundo.
      if [ -n "$frames" ] && [ "$frames" = "$last" ]; then
        same=$((same + 1))
        [ "$same" -ge 2 ] && return 0
      else
        same=0
      fi
      last=$frames
    fi
    sleep 0.5
  done
  return 1
}
