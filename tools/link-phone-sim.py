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
al cerrarse la primera conexión). Escribe `bye` + Enter para despedirse a mano.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import socket
import sys
import threading
import time
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


def tag(key, label, nc, ns):
    return hmac.new(key, label + nc + ns, hashlib.sha256).digest()


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

    def send(self, message):
        with self.lock:
            nonce = bytes(4) + self.sent.to_bytes(8, "big")
            self.sent += 1
            sealed = self.out.encrypt(nonce, json.dumps(message, ensure_ascii=False).encode(), None)
            self.conn.sendall(base64.b64encode(sealed) + b"\n")
            self.last_sent = time.monotonic()
        log("→", message)

    def open(self, line):
        nonce = bytes(4) + self.received.to_bytes(8, "big")
        self.received += 1
        return json.loads(self.inp.decrypt(nonce, base64.b64decode(line, validate=True), None))


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
    channel.send({"t": "info", "role": "phone", "app": args.app, "name": args.name, "caps": []})
    linked = time.monotonic()
    heard = time.monotonic()
    while True:
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
        log("←", message)
        kind = message.get("t")
        if kind == "ping":
            channel.send({"t": "pong"})
        elif kind == "bye":
            log("la pantalla se desvinculó: olvido su llave")
            return True


def typed(current):
    """`bye` + Enter se despide de la pantalla conectada."""
    for line in sys.stdin:
        if line.strip() == "bye":
            current["bye"] = True


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
    if sys.stdin.isatty():
        threading.Thread(target=typed, args=(current,), daemon=True).start()
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", args.port))
    server.listen(1)
    log("escuchando en el puerto %d" % args.port)
    while True:
        conn, address = server.accept()
        log("conexión de %s:%d" % address)
        ended = False
        try:
            ended = serve(conn, args, screen_id, key, current)
        except Exception as error:  # una línea que no descifra, JSON roto, conexión cortada…
            log("conexión cerrada:", repr(error))
        finally:
            conn.close()
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
