#!/usr/bin/env python3
"""Datos para buscadores e IA de la web (site/).

Pone en cada página un bloque JSON-LD (SoftwareApplication + preguntas frecuentes tomadas de los
<details> que ya se ven en la página) entre <!-- seo:begin --> y <!-- seo:end -->, y rehace
site/sitemap.xml. La versión sale de version.properties y el peso de la APK de la última release
de GitHub (con `gh`; si no contesta, se queda el que ya estaba).

Correr después de publicar una versión o de cambiar preguntas:  python3 tools/seo.py
"""
import datetime
import html
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
BASE = "https://litedash.dev"
PLAY = "https://play.google.com/store/apps/details?id=com.irvingguerra.litedash"
PLAY_CONNECT = PLAY + ".connect"
REPO = "https://github.com/IrvingGuerra/litedash"
AUTHOR = {"@type": "Person", "name": "Irving Guerra", "url": "https://irvingguerra.github.io/"}

FEATURES = {
    "es": [
        "Pantalla de inicio (launcher) para pantallas Android de auto y moto (head units) de 6\" a 11\"",
        "Escáner OBD2 integrado con adaptador ELM327 (Bluetooth, BLE, Wi-Fi o USB): salud del auto, códigos de falla, monitores de verificación, gráficas en vivo, 0 a 100 y registro de viajes",
        "Presión de llantas TPMS con receptor USB, sensores Bluetooth o de fábrica por OBD, con avisos de presión baja y fugas",
        "Control de la radio FM de fábrica de la pantalla (TopWay, NWD, Hengchen) en su propio widget",
        "Reproductor de música USB propio y radio por internet con las estaciones cercanas",
        "Música de Bluetooth y de apps como Spotify o YouTube Music con carátula y controles",
        "Mapa en vivo plano o inclinado y acceso directo a Waze y Google Maps",
        "Velocímetro por OBD o GPS con varios estilos, tacómetro, temperatura, gasolina, batería y turbo",
        "Tu auto en 3D de un catálogo de 15 modelos, de tu color y con giro de 360°",
        "Widgets que se mueven y cambian de tamaño, plantillas para 7\", 10\" y 11\"",
        "Mandos al volante, día y noche por hora, sensor de luz o faros, pantalla dividida",
        "Acceso directo a Zlink, AutoKit, CarLink y Open Headunit (Android Auto y CarPlay)",
        "App opcional LiteDash Connect: la música del celular en la pantalla",
        "Todo opcional y apagado de fábrica; funciona desde Android 6.0 con 1 GB de RAM",
    ],
    "en": [
        "Home screen (launcher) for Android car and motorcycle head units from 6\" to 11\"",
        "Built-in OBD2 scanner with an ELM327 adapter (Bluetooth, BLE, Wi-Fi or USB): car health, trouble codes, readiness monitors, live charts, 0–100 timer and trip log",
        "TPMS tire pressure with a USB receiver, Bluetooth sensors or factory sensors over OBD, with low-pressure and leak alerts",
        "Control of the head unit's factory FM radio (TopWay, NWD, Hengchen) in its own widget",
        "Own USB music player and internet radio with nearby stations",
        "Bluetooth music and apps like Spotify or YouTube Music with album art and controls",
        "Live map, flat or tilted, and one-tap Waze and Google Maps",
        "OBD or GPS speedometer in several styles, tachometer, temperature, fuel, battery and boost",
        "Your car in 3D from a catalog of 15 models, in your color, with 360° spin",
        "Movable, resizable widgets and layouts for 7\", 10\" and 11\" screens",
        "Steering wheel buttons, day/night by time, light sensor or headlights, split screen",
        "One-tap Zlink, AutoKit, CarLink and Open Headunit (Android Auto and CarPlay)",
        "Optional LiteDash Connect app: your phone's music on the screen",
        "Everything optional and off by default; runs on Android 6.0 with 1 GB of RAM",
    ],
}

CONNECT_FEATURES = {
    "es": [
        "Lo que suena en tu celular (Spotify, YouTube Music…) en el widget Música de LiteDash",
        "Carátula, avance y controles de reproducción en la pantalla del auto",
        "Se vincula escaneando un código QR; enlace cifrado por el hotspot o Wi-Fi, sin internet",
        "Gratis y sin anuncios",
    ],
    "en": [
        "What's playing on your phone (Spotify, YouTube Music…) in LiteDash's Music widget",
        "Album art, progress and playback controls on your car screen",
        "Links by scanning a QR code; encrypted link over hotspot or Wi-Fi, no internet needed",
        "Free and ad-free",
    ],
}

# Páginas: ruta en la web → (archivo, idioma, qué app describe, ruta de la otra versión).
PAGES = {
    "/": ("index.html", "es", "launcher", "/en/"),
    "/en/": ("en/index.html", "en", "launcher", "/"),
    "/connect/": ("connect/index.html", "es", "connect", "/en/connect/"),
    "/en/connect/": ("en/connect/index.html", "en", "connect", "/connect/"),
    "/compare/": ("compare/index.html", "es", "compare", "/en/compare/"),
    "/en/compare/": ("en/compare/index.html", "en", "compare", "/compare/"),
}
# Solo para el sitemap (sin bloque).
EXTRA = {
    "/privacidad/": ("privacidad/index.html", "es", None, "/en/privacy/"),
    "/en/privacy/": ("en/privacy/index.html", "en", None, "/privacidad/"),
}


def version():
    for line in (ROOT / "version.properties").read_text().splitlines():
        if line.startswith("VERSION_NAME="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("Sin VERSION_NAME")


def apk_mb():
    try:
        out = subprocess.run(
            ["gh", "release", "view", "--repo", "IrvingGuerra/litedash", "--json", "assets",
             "-q", '.assets[] | select(.name=="litedash.apk") | .size'],
            capture_output=True, text=True, timeout=30, check=True).stdout.strip()
        return f"{int(out) / 1e6:.1f}"
    except Exception:
        return None


def text(fragment):
    """HTML de una respuesta → texto plano."""
    return html.unescape(re.sub(r"<[^>]+>", "", fragment)).strip()


def faqs(page):
    found = re.findall(r"<details><summary>(.*?)</summary>(.*?)</details>", page, re.S)
    return [{"@type": "Question", "name": text(q),
             "acceptedAnswer": {"@type": "Answer", "text": text(a)}} for q, a in found]


def app_data(kind, lang, url, ver, mb):
    if kind == "connect":
        return {
            "@type": "SoftwareApplication",
            "name": "LiteDash Connect",
            "applicationCategory": "UtilitiesApplication",
            "operatingSystem": "Android",
            "description": ("App de celular compañera de LiteDash: pone en la pantalla del auto lo que suena en tu celular, con carátula y controles."
                            if lang == "es" else
                            "Companion phone app for LiteDash: shows what's playing on your phone on your car screen, with album art and controls."),
            "url": url,
            "installUrl": PLAY_CONNECT,
            "downloadUrl": PLAY_CONNECT,
            "image": f"{BASE}/img/connect/og.jpg",
            "featureList": CONNECT_FEATURES[lang],
            "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
            "isAccessibleForFree": True,
            "isRelatedTo": {"@type": "SoftwareApplication", "name": "LiteDash", "url": f"{BASE}/"},
            "author": AUTHOR,
            "inLanguage": lang,
        }
    data = {
        "@type": "SoftwareApplication",
        "name": "LiteDash",
        "alternateName": ["LiteDash Launcher", "LiteDash car launcher"],
        "applicationCategory": "UtilitiesApplication",
        "applicationSubCategory": "Car launcher" if lang == "en" else "Launcher para auto",
        "operatingSystem": "Android 6.0+",
        "softwareVersion": ver,
        "description": ("Launcher ligero y de código abierto para pantallas Android de auto y moto: escáner OBD2, presión de llantas TPMS, radio FM de fábrica, reproductor USB, mapa, velocímetros y tu auto en 3D."
                        if lang == "es" else
                        "Lightweight, open-source launcher for Android car and motorcycle head units: OBD2 scanner, TPMS tire pressure, factory FM radio, USB player, map, speedometers and your car in 3D."),
        "url": url,
        "downloadUrl": f"{REPO}/releases/latest/download/litedash.apk",
        "installUrl": PLAY,
        "image": f"{BASE}/img/og{'' if lang == 'es' else '-en'}.jpg",
        "screenshot": f"{BASE}/img/inicio-oscuro{'' if lang == 'es' else '-en'}.webp",
        "featureList": FEATURES[lang],
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
        "isAccessibleForFree": True,
        "license": "https://www.gnu.org/licenses/gpl-3.0.html",
        "sameAs": [REPO, PLAY],
        "author": AUTHOR,
        "inLanguage": lang,
    }
    if mb:
        data["fileSize"] = f"{mb} MB"
    return data


def block(path, lang, kind, other, page, ver, mb):
    url = BASE + path
    graph = []
    if kind in ("launcher", "connect"):
        graph.append(app_data(kind, lang, url, ver, mb))
    q = faqs(page)
    if q:
        graph.append({"@type": "FAQPage", "url": url, "inLanguage": lang, "mainEntity": q})
    lines = [f'  <link rel="alternate" hreflang="x-default" href="{BASE}{path if lang == "en" else other}">']
    if graph:
        doc = json.dumps({"@context": "https://schema.org", "@graph": graph}, ensure_ascii=False, indent=1)
        lines.append('  <script type="application/ld+json">\n' + doc + "\n  </script>")
    return "  <!-- seo:begin -->\n" + "\n".join(lines) + "\n  <!-- seo:end -->\n"


def main():
    ver, mb = version(), apk_mb()
    if mb is None:
        old = re.search(r'"fileSize": "([\d.]+) MB"', (SITE / "index.html").read_text())
        mb = old.group(1) if old else None
    today = datetime.date.today().isoformat()
    urls = []
    for path, (file, lang, kind, other) in {**PAGES, **EXTRA}.items():
        f = SITE / file
        if not f.exists():
            continue
        urls.append((path, lang, other))
        if kind is None:
            continue
        page = f.read_text()
        page = re.sub(r"  <!-- seo:begin -->.*?<!-- seo:end -->\n", "", page, flags=re.S)
        page = page.replace("</head>", block(path, lang, kind, other, page, ver, mb) + "</head>", 1)
        f.write_text(page)
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">']
    for path, lang, other in urls:
        out.append("  <url>")
        out.append(f"    <loc>{BASE}{path}</loc>")
        out.append(f"    <lastmod>{today}</lastmod>")
        es, en = (path, other) if lang == "es" else (other, path)
        out.append(f'    <xhtml:link rel="alternate" hreflang="es" href="{BASE}{es}"/>')
        out.append(f'    <xhtml:link rel="alternate" hreflang="en" href="{BASE}{en}"/>')
        out.append("  </url>")
    out.append("</urlset>")
    (SITE / "sitemap.xml").write_text("\n".join(out) + "\n")
    print(f"LiteDash {ver}, APK {mb} MB; {len(urls)} páginas en el sitemap")


if __name__ == "__main__":
    main()
