#!/usr/bin/env python3
"""Finge ser el celular (LiteDash Connect) para probar el módulo Celular sin la app.

Habla Link v1: escucha en el puerto 47600, hace el saludo con la llave del QR, manda su
`info`, contesta `ping` e imprime todo lo que recibe de la pantalla. El emulador de Android
llega a esta Mac por 10.0.2.2, que además es su puerta de enlace: LiteDash lo encuentra solo.

Necesita el paquete `cryptography` (AES-GCM). Una sola vez:

    python3 -m venv ~/.venvs/litedash-link
    ~/.venvs/litedash-link/bin/pip install cryptography

Uso (con ese Python):

    link-phone-sim.py "litedash://link?v=1&id=...&k=..."     # el texto del QR
    link-phone-sim.py --id 0011223344556677 --key AAEC...     # o el id y la llave (base64url)
    link-phone-sim.py                                         # pregunta por el texto del QR

Opciones: --port 47600 · --name "Pixel de prueba" · --app 0.1.0 · --bye N (se despide
`bye` N segundos después de vincularse: la pantalla debe olvidar el vínculo) · --once (termina
al cerrarse la primera conexión) · --beacon HOST[:PUERTO] (manda el faro UDP a esa dirección
cada 2 s mientras no hay conexión; para el emulador: `adb emu redir add udp:47600:47600` y
--beacon 127.0.0.1). Escribe `bye` + Enter para despedirse a mano.

Tu auto (§5d–§5h): --caps music,car,state declara esas capacidades en el `info` del celular. Con la
conexión abierta, órdenes por la entrada (una por línea; también sirve `tail -f órdenes.txt | sim`):

    color FF0000            setCar con el color (RRGGBB)
    finish metallic         setCar con el acabado (manda la entrada del catálogo del auto actual)
    gloss 0.8               setCar con el brillo
    name Mi auto            setCar con el nombre ("name" solo = el de fábrica)
    car ID                  setCar con el auto ID y su entrada del catálogo real
    car-url ID URL          igual, con la url del paquete cambiada a URL (fuera de la release del catálogo, la
                            pantalla no usa esa entrada: contesta `failed`)
    bomb ID                 setCar con una entrada inventada para ID cuyo paquete es un zip que se expande a 64 MB
                            (con su SHA-256 de verdad); si la pantalla lo pide con `need`, se lo manda: debe rechazarlo
    generic                 setCar con id "" (el sedán genérico)
    report                  pide el diagnóstico de la pantalla (`report`)
    raw {"t":...}           manda ese JSON tal cual
    send-pkg ID KIND URL    manda en partes `pkg` el archivo de URL como si fuera ese paquete (sin revisar nada:
                            para probar que la pantalla rechaza uno que no coincide)

A un `carJob` con `need`, el simulador baja el archivo real (la url original), revisa su SHA-256 y lo manda en
partes `pkg` de 64 KiB. Imprime `car` (sin la imagen), `carJob`, `state` y `report`.

La pantalla solo pide el paquete (`need`) si no tiene internet. Para probarlo en el emulador sin cortar el enlace,
un proxy que no existe: `adb shell settings put global http_proxy 10.0.2.2:9` (y para quitarlo, `... http_proxy :0`).
"""
import argparse
import base64
import copy
import hashlib
import hmac
import io
import json
import os
import socket
import sys
import threading
import time
import urllib.request
import zipfile
from urllib.parse import parse_qs, urlsplit

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.exit("Falta el paquete cryptography: mira cómo instalarlo al inicio de este archivo.")

HELLO_SECONDS = 5
HELLO_LINE_MAX = 1024
LINE_MAX = 256 * 1024
PING_SECONDS = 20
SILENCE_SECONDS = 45
CATALOG = "https://github.com/IrvingGuerra/litedash/releases/download/catalogo/catalogo.json"
PART = 64 * 1024
RELEASES = "https://github.com/IrvingGuerra/litedash/releases/download/"


def tag(key, label, nc, ns):
    return hmac.new(key, label + nc + ns, hashlib.sha256).digest()


def beacon(key):
    nonce = os.urandom(8)
    seal = hmac.new(key, b"LD-LINK-1 beacon" + nonce, hashlib.sha256).digest()[:8]
    return ("LD-LINK-1 beacon %s %s" % (nonce.hex(), seal.hex())).encode()


def announce(key, target, quiet):
    host, _, port = target.partition(":")
    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    out.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    while True:
        if not quiet.is_set():
            out.sendto(beacon(key), (host, int(port or 47600)))
        time.sleep(2)


def code(key):
    digest = hmac.new(key, b"LD-LINK-1 code", hashlib.sha256).digest()
    return "%04d" % (int.from_bytes(digest[:4], "big") % 10000)


def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


class Lines:
    """Líneas terminadas en \\n de un socket, con tope de tamaño."""

    def __init__(self, conn):
        self.conn = conn
        self.pending = b""

    def read(self, limit):
        while b"\n" not in self.pending:
            if len(self.pending) > limit:
                raise ValueError("línea demasiado larga")
            chunk = self.conn.recv(65536)
            if not chunk:
                return None
            self.pending += chunk
        line, self.pending = self.pending.split(b"\n", 1)
        if len(line) > limit:
            raise ValueError("línea demasiado larga")
        return line


class Channel:
    """El canal cifrado: AES-256-GCM, nonce = 4 ceros + contador de 8 bytes, por dirección."""

    def __init__(self, conn, send_key, receive_key):
        self.conn = conn
        self.out = AESGCM(send_key)
        self.inp = AESGCM(receive_key)
        self.sent = 0
        self.received = 0
        self.lock = threading.Lock()
        self.last_sent = time.monotonic()

    def send(self, message, quiet=False):
        with self.lock:
            nonce = bytes(4) + self.sent.to_bytes(8, "big")
            self.sent += 1
            sealed = self.out.encrypt(nonce, json.dumps(message, ensure_ascii=False).encode(), None)
            self.conn.sendall(base64.b64encode(sealed) + b"\n")
            self.last_sent = time.monotonic()
        if not quiet:
            log("→", shown(message))

    def open(self, line):
        nonce = bytes(4) + self.received.to_bytes(8, "big")
        self.received += 1
        return json.loads(self.inp.decrypt(nonce, base64.b64decode(line, validate=True), None))


def shown(message):
    """Lo que se imprime: sin la imagen del auto ni los datos de las partes (miden mucho)."""
    out = dict(message)
    for field in ("webp", "data"):
        if field in out:
            out[field] = "<%d caracteres>" % len(out[field])
    if out.get("t") == "report" and "text" in out:
        out["text"] = "%d caracteres:\n%s" % (len(out["text"]), out["text"])
    return out


class Catalog:
    """El catálogo real (para mandar `entry` en setCar) y los archivos que pide la pantalla con `need`."""

    def __init__(self, url):
        self.url = url
        self.entries = None
        self.sent = {}  # id → la entrada original (con su url de verdad)

    def entry(self, car_id):
        if self.entries is None:
            with urllib.request.urlopen(self.url, timeout=30) as r:
                self.entries = {a["id"]: a for a in json.load(r)["autos"]}
        found = self.entries.get(car_id)
        if found is None:
            raise KeyError("el catálogo no trae %s" % car_id)
        self.sent[car_id] = found
        return copy.deepcopy(found)

    def bomb(self, car_id):
        """Una entrada para [car_id] cuyo paquete es un zip pequeño que se expande a 64 MB de ceros."""
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("auto.json", json.dumps({"id": car_id, "nombre": "Bomba", "giro": 1}))
            z.writestr("giro/00.webp", bytes(64 << 20))
        data = buffer.getvalue()
        entry = {"id": car_id, "nombre": "Bomba de prueba", "marca": "Prueba", "anio": 2026,
                 "url": RELEASES + "no-existe/bomba.zip", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        self.sent[car_id] = dict(entry, data=data)
        log("bomba: zip de %d bytes que se expande a 64 MB" % len(data))
        return entry

    def file(self, car_id, kind):
        e = self.sent.get(car_id) or self.entry(car_id)
        if "data" in e:
            return e["data"]
        target = e["acabado"] if kind == "finish" else e
        with urllib.request.urlopen(target["url"], timeout=120) as r:
            data = r.read()
        if hashlib.sha256(data).hexdigest() != target["sha256"]:
            raise ValueError("el archivo bajado no coincide con su SHA-256")
        return data


def send_package(channel, catalog, car_id, kind, url=None):
    """Baja el paquete (o lo que haya en [url]) y lo manda en partes `pkg` (§5f)."""
    try:
        if url:
            with urllib.request.urlopen(url, timeout=120) as r:
                data = r.read()
        else:
            data = catalog.file(car_id, kind)
    except Exception as error:
        log("no pude bajar el paquete:", repr(error))
        return
    total = max(1, (len(data) + PART - 1) // PART)
    log("mando %s/%s: %d bytes en %d partes" % (car_id, kind, len(data), total))
    for n in range(total):
        chunk = data[n * PART:(n + 1) * PART]
        channel.send({"t": "pkg", "id": car_id, "kind": kind, "n": n, "of": total, "data": base64.b64encode(chunk).decode()}, quiet=n not in (0, total - 1))


def order(text, channel, catalog, last_car):
    """Una orden escrita (ver al inicio del archivo) → el mensaje para la pantalla."""
    word, _, rest = text.strip().partition(" ")
    rest = rest.strip()
    if word == "color":
        return {"t": "setCar", "color": rest}
    if word == "gloss":
        return {"t": "setCar", "gloss": float(rest)}
    if word == "name":
        return {"t": "setCar", "name": rest}
    if word == "generic":
        return {"t": "setCar", "id": ""}
    if word == "report":
        return {"t": "report", "id": os.urandom(16).hex()}
    if word == "raw":
        return json.loads(rest)
    if word == "send-pkg":
        car_id, kind, url = rest.split()
        threading.Thread(target=send_package, args=(channel, catalog, car_id, kind, url), daemon=True).start()
        return None
    if word == "car":
        return {"t": "setCar", "id": rest, "entry": catalog.entry(rest)}
    if word == "car-url":
        car_id, url = rest.split()
        entry = catalog.entry(car_id)
        entry["url"] = url
        return {"t": "setCar", "id": car_id, "entry": entry}
    if word == "bomb":
        return {"t": "setCar", "id": rest, "entry": catalog.bomb(rest)}
    if word == "finish":
        message = {"t": "setCar", "finish": rest}
        car_id = last_car.get("id")
        if car_id:
            message["entry"] = catalog.entry(car_id)
        return message
    log("orden desconocida:", text.strip())
    return None


def serve(conn, args, screen_id, key, current):
    """Atiende una conexión. Regresa True si el vínculo terminó (alguien mandó `bye`)."""
    conn.settimeout(HELLO_SECONDS)
    lines = Lines(conn)
    hello = json.loads(lines.read(HELLO_LINE_MAX) or b"{}")
    log("←", hello)
    if hello.get("t") != "hello":
        return False
    if hello.get("v") != 1:
        conn.sendall(b'{"t":"error","code":"version"}\n')
        log("versión desconocida:", hello.get("v"))
        return False
    if hello.get("id") != screen_id:
        log("no conozco esa pantalla (id %s): cierro sin contestar" % hello.get("id"))
        return False
    nc = base64.b64decode(hello["nc"], validate=True)
    if len(nc) != 16:
        return False
    ns = os.urandom(16)
    answer = {"t": "hello", "v": 1, "ns": base64.b64encode(ns).decode(),
              "mac": base64.b64encode(tag(key, b"LD-LINK-1 srv", nc, ns)).decode()}
    conn.sendall(json.dumps(answer).encode() + b"\n")
    auth = json.loads(lines.read(HELLO_LINE_MAX) or b"{}")
    mac = base64.b64decode(auth.get("mac", ""))
    if auth.get("t") != "auth" or not hmac.compare_digest(mac, tag(key, b"LD-LINK-1 cli", nc, ns)):
        log("la pantalla no tiene la llave: cierro")
        return False
    log("saludo correcto")
    channel = Channel(conn, tag(key, b"LD-LINK-1 s2c", nc, ns), tag(key, b"LD-LINK-1 c2s", nc, ns))
    caps = [c for c in args.caps.split(",") if c]
    info = {"t": "info", "role": "phone", "app": args.app, "name": args.name, "caps": caps}
    if "car" in caps:
        info["carImg"] = ""
    channel.send(info)
    linked = time.monotonic()
    heard = time.monotonic()
    catalog = current.setdefault("catalog", Catalog(args.catalog))
    last_car = current.setdefault("car", {})
    while True:
        while current.get("orders"):
            text = current["orders"].pop(0)
            try:
                message = order(text, channel, catalog, last_car)
            except Exception as error:
                log("no se pudo armar la orden:", repr(error))
                message = None
            if message is not None:
                channel.send(message)
        if current.pop("bye", False) or (args.bye is not None and time.monotonic() - linked >= args.bye):
            channel.send({"t": "bye", "reason": "unlink"})
            log("me despedí: la pantalla debe olvidar el vínculo")
            return True
        now = time.monotonic()
        if now - heard >= SILENCE_SECONDS:
            log("45 s de silencio: conexión caída")
            return False
        if now - max(heard, channel.last_sent) >= PING_SECONDS:
            channel.send({"t": "ping"})
        conn.settimeout(1)
        try:
            line = lines.read(LINE_MAX)
        except socket.timeout:
            continue
        if line is None:
            log("la pantalla cerró")
            return False
        message = channel.open(line)
        heard = time.monotonic()
        log("←", shown(message))
        kind = message.get("t")
        if kind == "car":
            last_car.clear()
            last_car.update(message)
        elif kind == "carJob" and message.get("step") == "need":
            threading.Thread(target=send_package, args=(channel, catalog, message.get("id"), message.get("kind")), daemon=True).start()
        if kind == "ping":
            channel.send({"t": "pong"})
        elif kind == "bye":
            log("la pantalla se desvinculó: olvido su llave")
            return True


def typed(current):
    """`bye` + Enter se despide de la pantalla conectada; lo demás son órdenes de tu auto (ver al inicio)."""
    for line in sys.stdin:
        if line.strip() == "bye":
            current["bye"] = True
        elif line.strip():
            current.setdefault("orders", []).append(line)


def main():
    parser = argparse.ArgumentParser(description="Simulador del celular para LiteDash (Link v1).")
    parser.add_argument("qr", nargs="?", help="el texto del QR (litedash://link?...)")
    parser.add_argument("--id", help="id de la pantalla (16 hex)")
    parser.add_argument("--key", help="llave en base64url (43 caracteres)")
    parser.add_argument("--port", type=int, default=47600)
    parser.add_argument("--name", default="Simulador de celular")
    parser.add_argument("--app", default="0.1.0-sim")
    parser.add_argument("--bye", type=float, metavar="N", help="despedirse N segundos después de vincularse")
    parser.add_argument("--once", action="store_true", help="terminar al cerrarse la primera conexión")
    parser.add_argument("--beacon", metavar="HOST[:PUERTO]", help="mandar el faro UDP a esa dirección")
    parser.add_argument("--caps", default="", help="capacidades del celular separadas por coma (music,car,state)")
    parser.add_argument("--catalog", default=CATALOG, help="catalogo.json de donde salen las entradas de `car`")
    args = parser.parse_args()

    screen_id, key_text = args.id, args.key
    if not (screen_id and key_text):
        text = args.qr or input("Texto del QR: ").strip()
        link = urlsplit(text)
        fields = parse_qs(link.query)
        if link.scheme != "litedash" or link.netloc != "link" or fields.get("v") != ["1"]:
            sys.exit("Ese texto no es un QR de LiteDash (litedash://link?v=1&...).")
        screen_id, key_text = fields["id"][0], fields["k"][0]
        log("QR de:", fields.get("n", ["—"])[0], "·", fields.get("s", ["—"])[0])
    key = base64.urlsafe_b64decode(key_text + "=" * (-len(key_text) % 4))
    if len(key) != 32:
        sys.exit("La llave debe ser de 32 bytes.")
    log("pantalla %s · código de confirmación %s" % (screen_id, code(key)))

    current = {}
    threading.Thread(target=typed, args=(current,), daemon=True).start()
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", args.port))
    server.listen(1)
    log("escuchando en el puerto %d" % args.port)
    connected = threading.Event()
    if args.beacon:
        threading.Thread(target=announce, args=(key, args.beacon, connected), daemon=True).start()
        log("faro hacia", args.beacon)
    while True:
        conn, address = server.accept()
        log("conexión de %s:%d" % address)
        ended = False
        connected.set()
        try:
            ended = serve(conn, args, screen_id, key, current)
        except Exception as error:  # una línea que no descifra, JSON roto, conexión cortada…
            log("conexión cerrada:", repr(error))
        finally:
            conn.close()
            connected.clear()
        if ended:
            log("vínculo terminado")
            return
        if args.once:
            return


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
