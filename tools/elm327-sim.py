#!/usr/bin/env python3
"""
Simulador de un ELM327 por Wi-Fi (TCP) para probar el Taller sin el auto.

    python3 tools/elm327-sim.py [puerto] [--caliente] [--sin-carga]   # 35000 por defecto

En el emulador de Android: Taller → Wi-Fi → 10.0.2.2:35000. Simula un auto con luz de
falla, dos códigos guardados (P0420, P0133), uno pendiente (P0171), el monitor EVAP
pendiente y datos en vivo que cambian (velocidad, rpm, temperatura, gasolina, turbo…).
Para probar las alertas: --caliente (motor a 112 °C) y --sin-carga (alternador sin cargar,
12.2 V con el motor andando). También contesta la presión de llantas de fábrica (Llantas →
Auto (OBD)) como el módulo de su marca: ver MODULOS; con --sin-tpms no la da.
--encabezados: como los clones que ignoran ATH0, el modo 01 llega con "7E8" y el largo.
"""
import math
import socketserver
import sys
import time

HOT = "--caliente" in sys.argv
NOT_CHARGING = "--sin-carga" in sys.argv
NO_TPMS = "--sin-tpms" in sys.argv
HEADERS = "--encabezados" in sys.argv
# A qué módulo se le habla (ATSH); "7DF" = el motor.
header = "7DF"


def multi(data):
    """Una respuesta larga como la da el ELM327 sin encabezados: largo y tramas "0:", "1:"…"""
    lines = ["%03X" % (len(data) // 2), "0:" + data[:12]]
    rest, n = data[12:], 1
    while rest:
        lines.append("%X:" % (n % 16) + rest[:14])
        rest, n = rest[14:], n + 1
    return "\r".join(lines)


def tpms(module_header, request):
    """La presión de llantas de fábrica: (encabezado, petición) -> respuesta (sin el 62/61), o None."""
    if NO_TPMS:
        return None
    return MODULOS.get((module_header, request))


def mitsubishi(request, psi, temp):
    """Mitsubishi GS (ASX, Outlander Sport, Lancer): 13 bytes; psi = d[8]/5, °C = d[9] − 50."""
    def answer():
        # La presión baja poco a poco en la trasera derecha (fuga lenta) para ver que se mueve.
        p = psi - (time.time() - START) / 600 if request == "2125" else psi
        return "61" + request[2:] + "00C20DB9F3830196" + "%02X%02X" % (round(p * 5), temp + 50) + "0F"
    return answer


# (ATSH, petición) -> función que da la respuesta positiva completa: las recetas de FactoryTires.kt
# que se prueban sin auto (el VIN del simulador es de un Lancer: JA3…).
MODULOS = {
    ("600", "2123"): mitsubishi("2123", 32, 28),
    ("600", "2126"): mitsubishi("2126", 31, 29),
    ("600", "2124"): mitsubishi("2124", 33, 26),
    ("600", "2125"): mitsubishi("2125", 30, 27),
}

# PIDs del modo 01 que "tiene" este auto.
PIDS = {0x01, 0x04, 0x05, 0x06, 0x07, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1F, 0x21, 0x2F, 0x33, 0x42, 0x46, 0x5C, 0x5E}
# Cuándo "arrancó el motor" (para el PID 1F, tiempo con el motor encendido).
START = time.time()
codes = {"03": ["P0420", "P0133"], "07": ["P0171"], "0A": []}
mil = True


def bitmap(base):
    v = 0
    for i in range(32):
        pid = base + i + 1
        if pid in PIDS or (i == 31 and any(p > pid for p in PIDS)):
            v |= 1 << (31 - i)
    return "%08X" % v


def code_bytes(code):
    first = "PCBU".index(code[0]) << 6 | int(code[1]) << 4 | int(code[2], 16)
    return "%02X%s" % (first, code[3:])


def live(pid):
    t = time.time()
    speed = 60 + 40 * math.sin(t / 6)
    rpm = 900 + speed * 32
    coolant = 112 if HOT else 90
    timing = 12 + speed / 10
    liters_hour = 1.0 + speed / 12
    values = {
        0x04: "%02X" % int(30 + speed / 2),
        0x05: "%02X" % (coolant + 40),
        # Ajuste de mezcla: corto ±3 % que va y viene, largo +2 % fijo (128 = 0 %).
        0x06: "%02X" % (128 + int(3 * math.sin(t / 4))),
        0x07: "%02X" % (128 + round(2 * 128 / 100)),
        # Presión de gasolina 351 kPa (A × 3).
        0x0A: "%02X" % 117,
        0x0E: "%02X" % int((timing + 64) * 2),
        0x11: "%02X" % int((15 + speed / 2) * 255 / 100),
        0x1F: "%04X" % int(t - START),
        0x21: "%04X" % 42,
        # Admisión de 60 a 160 kPa: con el aire a 78 kPa (CDMX) el turbo llega a ~12 psi.
        0x0B: "%02X" % int(60 + speed),
        0x0C: "%04X" % int(rpm * 4),
        0x0D: "%02X" % int(speed),
        0x0F: "%02X" % (32 + 40),
        0x10: "%04X" % int((3 + speed / 10) * 100),
        0x2F: "%02X" % int(0.62 * 255),
        0x33: "%02X" % 78,
        0x42: "%04X" % (12200 if NOT_CHARGING else 14100),
        0x46: "%02X" % (26 + 40),
        0x5C: "%02X" % (coolant + 5 + 40),
        0x5E: "%04X" % int(liters_hour * 20),
    }
    return values.get(pid)


def reply(cmd):
    global mil
    cmd = cmd.replace(" ", "").upper()
    global header
    if cmd.startswith("ATSH"):
        header = cmd[4:]
        return "OK"
    if cmd == "ATDPN":
        return "A6"
    if header != "7DF" and not cmd.startswith("AT"):
        answer = tpms(header, cmd)
        if answer is None:
            return "NO DATA"
        return answer() if len(answer()) <= 14 else multi(answer())
    if cmd == "ATZ":
        return "\r\rELM327 v1.5"
    if cmd == "ATRV":
        return "12.6V"
    if cmd.startswith("AT"):
        return "OK"
    if cmd in ("03", "07", "0A"):
        cs = codes[cmd]
        if not cs:
            return "%02X00" % (int(cmd, 16) + 0x40)
        return "%02X%02X%s" % (int(cmd, 16) + 0x40, len(cs), "".join(code_bytes(c) for c in cs))
    if cmd == "04":
        codes["03"], codes["07"] = [], []
        mil = False
        return "44"
    if cmd == "0902":
        vin = "JA32W8FV0AU000123".encode().hex().upper()
        data = "490201" + vin
        return "014\r0:" + data[0:12] + "\r1:" + data[12:26] + "\r2:" + data[26:40]
    if len(cmd) == 4 and cmd.startswith("01"):
        out = mode01(cmd)
        return "7E8%02X%s" % (len(out) // 2, out) if HEADERS and out.startswith("41") else out
    return "?"


def mode01(cmd):
    if True:
        pid = int(cmd[2:], 16)
        if pid in (0x00, 0x20, 0x40, 0x60):
            return "41%02X%s" % (pid, bitmap(pid))
        if pid == 0x01:
            a = (0x80 if mil else 0) | len(codes["03"])
            # B: los tres continuos soportados y listos. C: catalizador, EVAP, O2, calentador. D: EVAP pendiente.
            return "4101%02X07650%d" % (a, 4 if mil else 0)
        value = live(pid) if pid in PIDS else None
        return "41%02X%s" % (pid, value) if value else "NO DATA"


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        print("Conectado", self.client_address)
        buf = b""
        while True:
            data = self.request.recv(256)
            if not data:
                break
            buf += data
            while b"\r" in buf:
                line, buf = buf.split(b"\r", 1)
                cmd = line.decode(errors="ignore").strip()
                if not cmd:
                    continue
                out = reply(cmd)
                print(cmd, "->", out.replace("\r", " | "))
                self.request.sendall((out + "\r\r>").encode())


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    port = int(args[0]) if args else 35000
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("0.0.0.0", port), Handler) as server:
        print("ELM327 simulado en el puerto", port)
        server.serve_forever()
