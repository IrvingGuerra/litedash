#!/usr/bin/env bash
# Mide LiteDash en un dispositivo o emulador y guarda el reporte en Markdown.
# Destino: $LITEDASH_MEASUREMENTS (antes LITEDASH_MEDICIONES), o measurements/ (ignorada por git).
#
# Uso: tools/measure.sh [-s SERIAL] [LABEL]
#   LABEL = nombre del equipo en el reporte (por defecto, el modelo que reporta Android).
# Compila playRelease (firmado con la llave de depuración si no hay key.properties).
set -euo pipefail
cd "$(dirname "$0")/.."

SERIAL_ARGS=()
if [ "${1:-}" = "-s" ]; then SERIAL_ARGS=(-s "$2"); shift 2; fi
adb() { command adb ${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"} "$@"; }

PKG=com.irvingguerra.litedash
ACT=$PKG/.MainActivity
VERSION=$(grep '^VERSION_NAME=' version.properties | cut -d= -f2)

./gradlew -q :app:assemblePlayRelease
APK=app/build/outputs/apk/play/release/app-play-release.apk
adb install -r "$APK" >/dev/null

MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
ANDROID=$(adb shell getprop ro.build.version.release | tr -d '\r')
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
SCREEN=$(adb shell wm size | tail -1 | awk '{print $NF}' | tr -d '\r')
DPI=$(adb shell wm density | tail -1 | awk '{print $NF}' | tr -d '\r')
RAM_KB=$(adb shell cat /proc/meminfo | awk '/MemTotal/{print $2}')
LABEL=${1:-$MODEL}

# 1) Arranque en frío: 10 veces, mediana de TotalTime.
cold_times=()
for _ in $(seq 10); do
  adb shell am force-stop $PKG
  sleep 1
  t=$(adb shell am start -W -n $ACT | awk '/TotalTime/{print $2}' | tr -d '\r')
  cold_times+=("$t")
  sleep 1
done
COLD=$(printf '%s\n' "${cold_times[@]}" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')

# 2) Volver a inicio (tibio): abrir Ajustes y regresar con HOME.
warm_times=()
for _ in $(seq 5); do
  adb shell am start -W -a android.settings.SETTINGS >/dev/null
  sleep 1
  t=$(adb shell am start -W -n $ACT | awk '/TotalTime/{print $2}' | tr -d '\r')
  warm_times+=("$t")
done
WARM=$(printf '%s\n' "${warm_times[@]}" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')

# 3) Memoria (PSS total) y CPU después de 20 s quieto en inicio.
adb shell am start -W -n $ACT >/dev/null
sleep 20
PSS_KB=$(adb shell dumpsys meminfo $PKG | awk '/TOTAL PSS:/{print $3; exit} /^ *TOTAL /{print $2; exit}')
PID=$(adb shell pidof $PKG | tr -d '\r')
CPU=$(adb shell top -b -n 1 -p "$PID" | awk -v p="$PID" '$1==p{print $9}' | tr -d '\r')

# 4) Fluidez: desplazar el cajón de apps.
adb shell dumpsys gfxinfo $PKG reset >/dev/null
read -r W H <<<"$(echo "$SCREEN" | tr 'x' ' ')"
for _ in $(seq 6); do
  adb shell input swipe $((W * 2 / 3)) $((H * 3 / 4)) $((W * 2 / 3)) $((H / 4)) 200
  adb shell input swipe $((W * 2 / 3)) $((H / 4)) $((W * 2 / 3)) $((H * 3 / 4)) 200
done
GFX=$(adb shell dumpsys gfxinfo $PKG)
FRAMES=$(echo "$GFX" | awk -F': ' '/Total frames rendered/{print $2; exit}' | tr -d '\r')
JANKY=$(echo "$GFX" | awk -F': ' '/Janky frames:/{print $2; exit}' | tr -d '\r')

APK_SIZE=$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK")

DEST=${LITEDASH_MEASUREMENTS:-${LITEDASH_MEDICIONES:-measurements}}
mkdir -p "$DEST"
REPORT="$DEST/v$VERSION-$(echo "$LABEL" | LC_ALL=C tr -cs 'A-Za-z0-9.' '-' | sed 's/-*$//').md"
cat > "$REPORT" <<EOF2
# v$VERSION · $LABEL

- Equipo: $MODEL · Android $ANDROID (API $SDK) · $SCREEN @ ${DPI} dpi · RAM $((RAM_KB / 1024)) MB
- Fecha: $(date '+%Y-%m-%d %H:%M') · variante playRelease · perfil Ligero (todo apagado)

| Métrica | Resultado | Presupuesto |
| --- | --- | --- |
| APK playRelease | $((APK_SIZE / 1024)) KB | ≤ 8 MB |
| Arranque en frío (mediana de 10) | ${COLD} ms | ≤ 2000 ms gama baja · ≤ 800 ms la SRNUBI |
| Volver a inicio (mediana de 5) | ${WARM} ms | ≤ 150 ms gama baja · ≤ 80 ms la SRNUBI |
| RAM (PSS) quieto en inicio | $((PSS_KB / 1024)) MB | ≤ 60 MB gama baja · ≤ 70 MB la SRNUBI |
| CPU quieto en inicio | ${CPU:-?} % | ≈ 0 % |
| Desplazar el cajón | $JANKY de $FRAMES cuadros trabados | ≥ 95 % a tiempo |

Tiempos de arranque en frío: ${cold_times[*]}
EOF2
cat "$REPORT"
