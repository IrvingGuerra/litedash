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

# --- Ayudas -------------------------------------------------------------

# Mediana de una lista de números (ignora los "?").
median() { printf '%s\n' "$@" | grep -E '^[0-9]+$' | sort -n | awk '{a[NR]=$1} END{if (NR) print a[int((NR+1)/2)]; else print "?"}'; }

# "+219ms" o "+1s219ms" (lo que anota Android en logcat) → milisegundos.
displayed_ms() {
  adb logcat -d 2>/dev/null | tr -d '\r' | grep 'Displayed' | grep "$ACT\|$PKG/" | tail -1 |
    sed -E 's/.*\+([0-9]+s)?([0-9]+)ms.*/\1 \2/' |
    awk '{ if (NF == 2) { sub("s", "", $1); print $1 * 1000 + $2 } else if (NF == 1) print $1 }'
}

# PID del proceso principal: pidof (Android 7+) o ps (Android 6 lista todo sin -A).
app_pid() {
  local pid
  pid=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r' | awk '{print $1}') || true
  if [ -z "$pid" ]; then
    pid=$(adb shell ps 2>/dev/null | tr -d '\r' | awk -v p="$PKG" '$NF==p{print $2; exit}') || true
  fi
  if [ -z "$pid" ]; then
    pid=$(adb shell ps -A 2>/dev/null | tr -d '\r' | awk -v p="$PKG" '$NF==p{print $2; exit}') || true
  fi
  echo "$pid"
}

# PSS total en KB: la fila TOTAL de dumpsys meminfo (formato de Android 6 a 15; "TOTAL PSS:" en
# las nuevas). Si no sale, la suma de Pss de /proc/PID/smaps (solo si el shell puede leerla).
pss_kb() {
  local pid=$1 kb
  kb=$(adb shell dumpsys meminfo "${pid:-$PKG}" 2>/dev/null | tr -d '\r' |
    awk '/TOTAL PSS:/{print $3; exit} /^ *TOTAL +[0-9]/{print $2; exit}') || true
  if [ -z "$kb" ] && [ -n "$pid" ]; then
    kb=$(adb shell cat /proc/"$pid"/smaps 2>/dev/null | tr -d '\r' | awk '/^Pss:/{s+=$2} END{if (s) print s}') || true
  fi
  echo "${kb:-}"
}

# %CPU del proceso en una pasada de top. Sirve con el top de toybox (Android 7+: columna
# "S[%CPU]" del encabezado = dos columnas en las filas) y con el de toolbox (Android 6: "CPU%").
cpu_percent() {
  local pid=$1 out
  [ -n "$pid" ] || { echo "?"; return; }
  out=$(adb shell top -b -n 1 2>/dev/null | tr -d '\r') || true
  echo "$out" | grep -q 'PID' || out=$(adb shell top -n 1 2>/dev/null | tr -d '\r') || true
  echo "$out" | awk -v p="$pid" '
    col == 0 && /PID/ {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^S\[%CPU\]/) { col = i + 1; break }
        if ($i ~ /CPU/) { col = i; break }
      }
      next
    }
    col > 0 && $1 == p { v = $col; gsub("%", "", v); print v; exit }
  '
}

# --- 1) Arranque en frío: 10 veces, mediana --------------------------------
# Si LiteDash es el inicio predeterminado, Android lo vuelve a abrir solo tras force-stop (y el
# acceso a notificaciones revive el proceso): am start salía WARM con 0 ms. Por eso primero se
# pone otra app al frente (Ajustes) y luego "am start -S" lo detiene justo antes de abrirlo. Si
# aun así no sale COLD, se toma lo que Android anotó en logcat ("Displayed …").
cold_times=()
cold_states=()
for _ in $(seq 10); do
  adb shell am start -W -a android.settings.SETTINGS >/dev/null
  sleep 1
  adb shell am force-stop $PKG
  sleep 1
  adb logcat -c 2>/dev/null || true
  out=$(adb shell am start -S -W -n $ACT | tr -d '\r')
  state=$(echo "$out" | awk '/LaunchState/{print $2}')   # Android 10+; en 6–9 no viene
  t=$(echo "$out" | awk '/TotalTime/{print $2}')
  if [ -z "$t" ] || [ "$t" = 0 ] || { [ -n "$state" ] && [ "$state" != COLD ]; }; then
    sleep 1
    t=$(displayed_ms)
  fi
  cold_times+=("${t:-?}")
  cold_states+=("${state:-?}")
  sleep 1
done
COLD=$(median "${cold_times[@]}")

# --- 2) Volver a inicio (tibio): abrir Ajustes y regresar ------------------
warm_times=()
for _ in $(seq 5); do
  adb shell am start -W -a android.settings.SETTINGS >/dev/null
  sleep 1
  t=$(adb shell am start -W -n $ACT | awk '/TotalTime/{print $2}' | tr -d '\r')
  warm_times+=("${t:-?}")
done
WARM=$(median "${warm_times[@]}")

# --- 3) Memoria (PSS total) y CPU después de 20 s quieto en inicio ----------
adb shell am start -W -n $ACT >/dev/null
sleep 20
PID=$(app_pid)
PSS_KB=$(pss_kb "$PID")
CPU=$(cpu_percent "$PID")
PSS_MB=$([ -n "$PSS_KB" ] && echo "$((PSS_KB / 1024))" || echo "?")

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
| RAM (PSS) quieto en inicio | ${PSS_MB} MB | ≤ 60 MB gama baja · ≤ 70 MB la SRNUBI |
| CPU quieto en inicio | ${CPU:-?} % | ≈ 0 % |
| Desplazar el cajón | $JANKY de $FRAMES cuadros trabados | ≥ 95 % a tiempo |

Tiempos de arranque en frío: ${cold_times[*]} (estado: ${cold_states[*]}; "?" = Android 6–9 no lo dice)
EOF2
cat "$REPORT"
