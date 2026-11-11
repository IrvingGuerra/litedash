#!/usr/bin/env python3
"""Página de comparación de launchers para auto (site/compare/ y site/en/compare/).

Los datos de la competencia van aquí, con su fuente y la fecha en que se consultaron. Al cambiar algo:
    python3 tools/compare.py && python3 tools/seo.py
Regla: solo lo que se puede comprobar. Lo que no se pudo confirmar se marca "sin dato", y donde otro
launcher gana, se dice.
"""
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
CHECKED = {"es": "11 de noviembre de 2026", "en": "November 11, 2026"}

APPS = [  # clave, nombre, nota bajo el nombre
    ("ld", "LiteDash", {"es": "este", "en": "this one"}),
    ("strada", "StradaOS", {"es": "2.16.0", "en": "2.16.0"}),
    ("agama", "AGAMA", {"es": "6.1.2", "en": "6.1.2"}),
    ("cwg", "CarWebGuru", {"es": "3.7.0", "en": "3.7.0"}),
    ("cl", "Car Launcher PRO", {"es": "5.7.1", "en": "5.7.1"}),
]

# Cada valor: (estado, nota ES, nota EN). Estados: si, no, parcial, nd (sin dato), txt (solo texto).
S, N, P, D, T = "si", "no", "parcial", "nd", "txt"
ROWS = [
    ("group", {"es": "Lo básico", "en": "The basics"}),
    ("row", {"es": "Peso de descarga", "en": "Download size"}, {
        "ld": (T, '<data class="apk-mb" value="1.8">1.8</data> MB', '<data class="apk-mb" value="1.8">1.8</data> MB'),
        "strada": (T, "0.6 MB", "0.6 MB"),
        "agama": (T, "11.6 MB", "11.6 MB"),
        "cwg": (T, "29.3 MB", "29.3 MB"),
        "cl": (D, "", ""),
    }),
    ("row", {"es": "Android mínimo", "en": "Minimum Android"}, {
        "ld": (T, "6.0", "6.0"), "strada": (T, "4.1", "4.1"), "agama": (T, "6.0", "6.0"),
        "cwg": (T, "5.0", "5.0"), "cl": (T, "5.0", "5.0"),
    }),
    ("row", {"es": "Precio", "en": "Price"}, {
        "ld": (T, "Gratis", "Free"),
        "strada": (T, "Gratis", "Free"),
        "agama": (T, "30 días de prueba, luego US$8.99", "30-day trial, then US$8.99"),
        "cwg": (T, "Gratis con anuncios; Pro de pago", "Free with ads; paid Pro"),
        "cl": (T, "US$15.99 (hay versión gratis limitada)", "US$15.99 (limited free version)"),
    }),
    ("row", {"es": "Sin anuncios", "en": "No ads"}, {
        "ld": (S, "", ""), "strada": (S, "", ""), "agama": (S, "", ""), "cwg": (N, "", ""), "cl": (S, "", ""),
    }),
    ("row", {"es": "Código abierto", "en": "Open source"}, {
        "ld": (S, "GPL-3.0", "GPL-3.0"), "strada": (S, "GPL-3.0", "GPL-3.0"),
        "agama": (N, "", ""), "cwg": (N, "", ""), "cl": (N, "", ""),
    }),
    ("row", {"es": "En Google Play", "en": "On Google Play"}, {
        "ld": (S, "", ""), "strada": (N, "Solo GitHub", "GitHub only"),
        "agama": (S, "", ""), "cwg": (S, "", ""), "cl": (S, "", ""),
    }),
    ("group", {"es": "Para tu auto, sin instalar otra app", "en": "For your car, no extra app needed"}),
    ("feat", {"es": "Escáner OBD2 (ELM327)", "en": "OBD2 scanner (ELM327)"}, {
        "ld": (S, "Bluetooth, BLE, Wi‑Fi o USB", "Bluetooth, BLE, Wi‑Fi or USB"),
        "strada": (N, "", ""),
        "agama": (P, "Con la app CRAB y licencia aparte", "Needs the CRAB app and a separate license"),
        "cwg": (S, "Widgets OBD2", "OBD2 widgets"),
        "cl": (N, "", ""),
    }),
    ("feat", {"es": "Códigos de falla y salud del auto", "en": "Trouble codes and car health"}, {
        "ld": (S, "Explicados y se borran", "Explained, and you can clear them"),
        "strada": (N, "", ""), "agama": (D, "", ""), "cwg": (D, "", ""), "cl": (N, "", ""),
    }),
    ("feat", {"es": "Presión de llantas (TPMS)", "en": "Tire pressure (TPMS)"}, {
        "ld": (S, "Receptor USB, Bluetooth u OBD", "USB receiver, Bluetooth or OBD"),
        "strada": (N, "", ""),
        "agama": (P, "Con la app MANTA y licencia aparte", "Needs the MANTA app and a separate license"),
        "cwg": (D, "", ""), "cl": (N, "", ""),
    }),
    ("feat", {"es": "Controla la radio FM de tu pantalla", "en": "Controls your unit's FM radio"}, {
        "ld": (S, "Estación y botones (NWD, TopWay, Hengchen)", "Station and buttons (NWD, TopWay, Hengchen)"),
        "strada": (P, "Solo la abre", "Only opens it"),
        "agama": (P, "Si la radio publica su aviso", "If the radio app posts a notification"),
        "cwg": (D, "", ""), "cl": (N, "", ""),
    }),
    ("feat", {"es": "Reproductor USB propio", "en": "Own USB music player"}, {
        "ld": (S, "Carpetas, artistas y álbumes", "Folders, artists and albums"),
        "strada": (N, "", ""), "agama": (N, "", ""),
        "cwg": (S, "Reproductor de archivos", "File player"), "cl": (N, "", ""),
    }),
    ("feat", {"es": "Radio por internet", "en": "Internet radio"}, {
        "ld": (S, "Estaciones cerca de ti", "Stations near you"),
        "strada": (N, "", ""), "agama": (N, "", ""), "cwg": (D, "", ""), "cl": (N, "", ""),
    }),
    ("feat", {"es": "Mapa en el inicio", "en": "Map on the home screen"}, {
        "ld": (S, "En vivo, plano o inclinado", "Live, flat or tilted"),
        "strada": (S, "OpenStreetMap", "OpenStreetMap"),
        "agama": (P, "Solo la siguiente vuelta de otra app", "Only the next turn from another app"),
        "cwg": (P, "Tus recorridos, no navegación", "Your trips, not navigation"),
        "cl": (N, "", ""),
    }),
    ("feat", {"es": "Mapas descargables sin internet", "en": "Downloadable offline maps"}, {
        "ld": (N, "En camino", "Coming"),
        "strada": (S, "Por país", "By country"),
        "agama": (N, "", ""), "cwg": (N, "", ""), "cl": (N, "", ""),
    }),
    ("feat", {"es": "Widgets que mueves y agrandas", "en": "Widgets you move and resize"}, {
        "ld": (S, "", ""), "strada": (N, "Diseño con opciones", "Layout with options"),
        "agama": (S, "", ""), "cwg": (S, "", ""), "cl": (S, "En PRO", "In PRO"),
    }),
    ("feat", {"es": "Tu auto en 3D", "en": "Your car in 3D"}, {
        "ld": (S, "15 modelos, de tu color", "15 models, in your color"),
        "strada": (N, "Foto de tu auto", "Photo of your car"),
        "agama": (D, "", ""), "cwg": (D, "", ""), "cl": (N, "", ""),
    }),
    ("feat", {"es": "Día y noche automático", "en": "Automatic day/night"}, {
        "ld": (S, "Hora, sensor de luz o faros", "Time, light sensor or headlights"),
        "strada": (S, "Por hora", "By time"), "agama": (S, "Por hora", "By time"),
        "cwg": (D, "", ""), "cl": (P, "Solo el brillo", "Brightness only"),
    }),
    ("feat", {"es": "Pantalla dividida con el mapa", "en": "Split screen with the map"}, {
        "ld": (S, "", ""), "strada": (D, "", ""), "agama": (D, "", ""), "cwg": (S, "", ""), "cl": (D, "", ""),
    }),
    ("feat", {"es": "App para tu celular", "en": "Phone companion app"}, {
        "ld": (S, "LiteDash Connect", "LiteDash Connect"),
        "strada": (N, "", ""), "agama": (N, "", ""), "cwg": (D, "", ""), "cl": (N, "", ""),
    }),
]

SOURCES = [
    ("LiteDash", [("GitHub", "https://github.com/IrvingGuerra/litedash/releases/latest"),
                  ("Google Play", "https://play.google.com/store/apps/details?id=com.irvingguerra.litedash")]),
    ("StradaOS", [("README", "https://github.com/lordnordic-debug/StradaOS"),
                  ("Release 2.16.0", "https://github.com/lordnordic-debug/StradaOS/releases/tag/v2.16.0")]),
    ("AGAMA", [("Google Play", "https://play.google.com/store/apps/details?id=altergames.carlauncher"),
               ("RuStore", "https://www.rustore.ru/catalog/app/altergames.carlauncher"),
               ("Instructions", "https://altercars.ru/agama/instructions/en.html")]),
    ("CarWebGuru", [("Google Play", "https://play.google.com/store/apps/details?id=com.softartstudio.carwebguru"),
                    ("RuStore", "https://www.rustore.ru/catalog/app/com.softartstudio.carwebguru")]),
    ("Car Launcher PRO", [("Google Play", "https://play.google.com/store/apps/details?id=com.autolauncher.motorcar")]),
]

TEXT = {
    "es": {
        "title": "LiteDash vs StradaOS, AGAMA y CarWebGuru — comparativa de launchers para auto",
        "desc": "Comparativa honesta de launchers para pantallas Android de auto: peso, Android mínimo, precio, escáner OBD2, presión de llantas (TPMS), radio FM, reproductor USB y mapa. LiteDash, StradaOS, AGAMA, CarWebGuru y Car Launcher.",
        "ceja": ["Comparativa", "Con fuentes", "Actualizada"],
        "h1": 'Cuánto ofrece <em>por lo que pesa.</em>',
        "lead": "LiteDash pesa un poco más que el más ligero, pero trae lo que los demás piden instalar aparte: escáner OBD2, llantas, radio de fábrica y reproductor USB.",
        "nota": "Datos públicos de cada app, consultados el {date}. Si algo cambió, avísanos en GitHub.",
        "tabla_sobre": "Lado a lado", "tabla_h2": "Funciones primero, peso al lado.",
        "tabla_p": "Integrado = lo hace el launcher solo, sin instalar otra app.",
        "ley": ["Sí", "A medias", "No", "Sin dato público"],
        "cuenta_h": "Funciones para tu auto, de {n}",
        "mb": "MB",
        "honesto_sobre": "Sin trampa", "honesto_h2": "Cuándo te conviene otro.",
        "honesto": [
            ("StradaOS", "Si tu pantalla tiene Android 4 o 5, o necesitas hoy mapas sin internet. Pesa 0.6 MB y también es gratis y abierto; no trae OBD, llantas ni reproductor."),
            ("AGAMA", "Si quieres muchos temas y no te importa pagar, y ya usas sus apps CRAB y MANTA para OBD y llantas."),
            ("CarWebGuru", "Si te gustan sus temas y su reproductor de archivos, y no te molestan los anuncios."),
            ("DuduAuto", "No es una app: es el sistema de las radios DUDUAUTO. Si ya tienes una, no necesitas launcher; LiteDash es para las demás pantallas."),
        ],
        "preguntas_h2": "Preguntas de la comparación.",
        "faq": [
            ("¿Cuál es el launcher para auto más ligero?", "StradaOS, con 0.6 MB. LiteDash pesa 1.8 MB y suma escáner OBD2, presión de llantas, control de la radio FM de fábrica, reproductor USB y radio por internet, que StradaOS no trae."),
            ("¿Qué launcher trae escáner OBD2 sin otra app?", "LiteDash lo trae integrado: conectas un ELM327 por Bluetooth, BLE, Wi‑Fi o USB y ves salud del auto, códigos de falla y gráficas. AGAMA necesita su app CRAB con licencia aparte y StradaOS no tiene OBD."),
            ("¿LiteDash o AGAMA?", "AGAMA es más popular y tiene muchos temas, pero es de pago tras 30 días, pesa 11.6 MB y pide apps aparte para OBD y llantas. LiteDash es gratis, de código abierto y los trae dentro en 1.8 MB."),
            ("¿LiteDash o CarWebGuru?", "CarWebGuru tiene anuncios y pesa 29.3 MB, unas 16 veces más. LiteDash no tiene anuncios y trae presión de llantas y control de la radio FM de fábrica."),
            ("¿De dónde salen los datos?", "De la página de cada app en GitHub, Google Play y RuStore (Play ya no muestra el tamaño). Abajo están los enlaces. Si un dato no se pudo comprobar, dice \"sin dato\"."),
        ],
        "fuentes_h": "Fuentes",
        "cta_h2": "Pruébalo en tu pantalla.",
        "cta_play": "Google Play", "cta_apk": "Descargar APK", "cta_web": "Ver todas las funciones",
        "nav": [("../#todo", "Funciones"), ("#tabla", "Comparativa"), ("#preguntas", "Preguntas")],
        "lang_pill": ("../en/compare/", "en", "EN"), "download": ("../#descargar", "Descargar"),
        "home": "../", "root": "../",
        "footer": "© 2026 LiteDash · Hecho en México",
        "footer_links": [("../", "LiteDash"), ("../connect/", "Connect"), ("../privacidad/", "Privacidad"),
                         ("https://github.com/IrvingGuerra/litedash", "GitHub"), ("../en/compare/", "English")],
        "apk_label": "Descargar APK",
        "estado": {"si": "Sí", "no": "No", "parcial": "A medias", "nd": "Sin dato"},
    },
    "en": {
        "title": "LiteDash vs StradaOS, AGAMA and CarWebGuru — car launcher comparison",
        "desc": "Honest comparison of launchers for Android car head units: size, minimum Android, price, OBD2 scanner, tire pressure (TPMS), FM radio, USB player and map. LiteDash, StradaOS, AGAMA, CarWebGuru and Car Launcher.",
        "ceja": ["Comparison", "With sources", "Up to date"],
        "h1": 'What you get <em>for the size.</em>',
        "lead": "LiteDash weighs a bit more than the lightest one, but it includes what the others ask you to install separately: OBD2 scanner, tires, factory radio and a USB player.",
        "nota": "Public data from each app, checked on {date}. If something changed, tell us on GitHub.",
        "tabla_sobre": "Side by side", "tabla_h2": "Features first, size alongside.",
        "tabla_p": "Built in = the launcher does it on its own, no extra app.",
        "ley": ["Yes", "Partly", "No", "No public data"],
        "cuenta_h": "Car features, out of {n}",
        "mb": "MB",
        "honesto_sobre": "No tricks", "honesto_h2": "When another one suits you.",
        "honesto": [
            ("StradaOS", "If your unit runs Android 4 or 5, or you need offline maps today. It's 0.6 MB, also free and open source; no OBD, tires or music player."),
            ("AGAMA", "If you want lots of themes, don't mind paying, and already use its CRAB and MANTA apps for OBD and tires."),
            ("CarWebGuru", "If you like its themes and file player, and ads don't bother you."),
            ("DuduAuto", "It's not an app: it's the system on DUDUAUTO units. If you have one, you don't need a launcher; LiteDash is for every other screen."),
        ],
        "preguntas_h2": "Comparison questions.",
        "faq": [
            ("What is the lightest car launcher?", "StradaOS, at 0.6 MB. LiteDash is 1.8 MB and adds an OBD2 scanner, tire pressure, factory FM radio control, a USB player and internet radio, which StradaOS doesn't have."),
            ("Which launcher has an OBD2 scanner without another app?", "LiteDash has it built in: connect an ELM327 over Bluetooth, BLE, Wi‑Fi or USB and see car health, trouble codes and charts. AGAMA needs its CRAB app with a separate license, and StradaOS has no OBD."),
            ("LiteDash or AGAMA?", "AGAMA is more popular and has many themes, but it's paid after 30 days, weighs 11.6 MB and needs separate apps for OBD and tires. LiteDash is free, open source and has them built in at 1.8 MB."),
            ("LiteDash or CarWebGuru?", "CarWebGuru has ads and weighs 29.3 MB, about 16 times more. LiteDash has no ads and adds tire pressure and factory FM radio control."),
            ("Where does the data come from?", "From each app's page on GitHub, Google Play and RuStore (Play no longer shows the size). Links are below. If something couldn't be verified, it says \"no data\"."),
        ],
        "fuentes_h": "Sources",
        "cta_h2": "Try it on your screen.",
        "cta_play": "Google Play", "cta_apk": "Download APK", "cta_web": "See every feature",
        "nav": [("../#todo", "Features"), ("#tabla", "Comparison"), ("#preguntas", "Questions")],
        "lang_pill": ("../../compare/", "es", "ES"), "download": ("../#descargar", "Download"),
        "home": "../", "root": "../../",
        "footer": "© 2026 LiteDash · Made in Mexico",
        "footer_links": [("../", "LiteDash"), ("../connect/", "Connect"), ("../privacy/", "Privacy"),
                         ("https://github.com/IrvingGuerra/litedash", "GitHub"), ("../../compare/", "Español")],
        "apk_label": "Download APK",
        "estado": {"si": "Yes", "no": "No", "parcial": "Partly", "nd": "No data"},
    },
}
MARK = {"si": "✓", "no": "–", "parcial": "½", "nd": "?"}


def cell(lang, state, es, en, ours):
    note = es if lang == "es" else en
    cls = ' class="nuestra"' if ours else ""
    if state == T:
        return f"<td{cls}>{note}</td>"
    label = TEXT[lang]["estado"][state]
    small = f"<small>{note}</small>" if note else ""
    return f'<td{cls}><span class="{state}" role="img" aria-label="{label}">{MARK[state]}</span>{small}</td>'


def table(lang):
    t = TEXT[lang]
    ours = ' class="nuestra"'
    head = "".join(
        f'<th scope="col"{ours if key == "ld" else ""}>{name}<small>{note[lang]}</small></th>'
        for key, name, note in APPS)
    body = []
    for kind, label, *rest in ROWS:
        if kind == "group":
            body.append(f'<tr class="grupo"><th scope="rowgroup" colspan="{len(APPS) + 1}">{label[lang]}</th></tr>')
            continue
        values = rest[0]
        tds = "".join(cell(lang, *values[key], key == "ld") for key, _, _ in APPS)
        body.append(f'<tr><th scope="row">{label[lang]}</th>{tds}</tr>')
    return (f'<div class="tabla" tabindex="0" role="region" aria-label="{t["tabla_h2"]}">\n'
            f'<table>\n<thead><tr><th scope="col"></th>{head}</tr></thead>\n<tbody>\n'
            + "\n".join(body) + "\n</tbody>\n</table>\n</div>")


def counts(lang):
    feats = [r for r in ROWS if r[0] == "feat"]
    out = []
    for key, name, _ in APPS[:4]:  # Car Launcher PRO no entra: no hay dato de su peso
        n = sum(1 for _, _, values in feats if values[key][0] == S)
        size = next(v for kind, _, *rest in ROWS if kind == "row" for k, v in rest[0].items()
                    if k == key and v[0] == T and "MB" in v[1])
        cls = ' class="nuestra"' if key == "ld" else ""
        out.append(f'<div{cls}><b>{n}</b><small>{name} · {size[1 if lang == "es" else 2]}</small></div>')
    title = TEXT[lang]["cuenta_h"].format(n=len(feats))
    return f'<p class="fuente" style="text-align:center;margin-top:34px">{title}</p>\n<div class="cuenta">' + "".join(out) + "</div>"


def page(lang):
    t = TEXT[lang]
    other = "/en/compare/" if lang == "es" else "/compare/"
    me = "/compare/" if lang == "es" else "/en/compare/"
    root = t["root"]
    img = root + "img/"
    nav = "\n".join(f'        <a class="seccion" href="{h}">{x}</a>' for h, x in t["nav"])
    pill_href, pill_lang, pill = t["lang_pill"]
    ley = "".join(f'<span><span class="{s}" aria-hidden="true">{MARK[s]}</span>{x}</span>'
                  for s, x in zip(("si", "parcial", "no", "nd"), t["ley"]))
    honesto = "\n".join(f'          <div class="tarjeta"><h3>{n}</h3><p>{p}</p></div>' for n, p in t["honesto"])
    faq = "\n".join(f"          <details><summary>{q}</summary><p>{a}</p></details>" for q, a in t["faq"])
    fuentes = "\n".join(
        f'          <li><b>{name}:</b> ' + " · ".join(f'<a href="{u}" rel="nofollow noopener" target="_blank">{x}</a>' for x, u in links) + "</li>"
        for name, links in SOURCES)
    footer = "\n".join(f'        <a href="{h}">{x}</a>' for h, x in t["footer_links"])
    ceja = "".join(f"<li>{x}</li>" for x in t["ceja"])
    return f"""<!doctype html>
<html lang="{lang}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{t["title"]}</title>
  <meta name="description" content="{t["desc"]}">
  <meta name="theme-color" content="#101317">
  <link rel="canonical" href="https://litedash.web.app{me}">
  <link rel="icon" href="{root}favicon.ico" sizes="48x48">
  <link rel="icon" href="{root}favicon.svg" type="image/svg+xml">
  <link rel="apple-touch-icon" href="{root}apple-touch-icon.png">
  <link rel="alternate" hreflang="es" href="https://litedash.web.app/compare/">
  <link rel="alternate" hreflang="en" href="https://litedash.web.app/en/compare/">
  <meta property="og:title" content="{t["title"]}">
  <meta property="og:description" content="{t["desc"]}">
  <meta property="og:image" content="https://litedash.web.app/img/og{'' if lang == 'es' else '-en'}.jpg">
  <meta property="og:url" content="https://litedash.web.app{me}">
  <meta property="og:type" content="website">
  <meta name="twitter:card" content="summary_large_image">
  <link rel="stylesheet" href="{root}landing.css?v=20261111b">
  <script>document.documentElement.classList.add('js')</script>
</head>
<body>
  <header class="barra">
    <div class="contenedor">
      <a class="logo" href="{t["home"]}" aria-label="LiteDash"><img src="{img}litedash-horizontal-sobre-oscuro.svg" alt="LiteDash" width="132" height="30"></a>
      <nav>
{nav}
        <a class="pastilla idioma" href="{pill_href}" hreflang="{pill_lang}" lang="{pill_lang}">{pill}</a>
        <a class="pastilla bajar" href="{t["download"][0]}">{t["download"][1]}</a>
      </nav>
    </div>
  </header>

  <main>
    <div class="heroe comparativa">
      <div class="contenedor">
        <ul class="ceja">{ceja}</ul>
        <h1>{t["h1"]}</h1>
        <p class="lead">{t["lead"]}</p>
        <p class="nota">{t["nota"].format(date=CHECKED[lang])}</p>
      </div>
    </div>

    <section id="tabla">
      <div class="contenedor">
        <div class="titulo centro aparece">
          <span class="sobre">{t["tabla_sobre"]}</span>
          <h2>{t["tabla_h2"]}</h2>
          <p>{t["tabla_p"]}</p>
        </div>
{table(lang)}
        <div class="leyenda">{ley}</div>
{counts(lang)}
      </div>
    </section>

    <section class="alterna">
      <div class="contenedor">
        <div class="titulo centro aparece"><span class="sobre">{t["honesto_sobre"]}</span><h2>{t["honesto_h2"]}</h2></div>
        <div class="rejilla cuatro aparece">
{honesto}
        </div>
      </div>
    </section>

    <section id="preguntas">
      <div class="contenedor">
        <div class="titulo centro aparece"><h2>{t["preguntas_h2"]}</h2></div>
        <div class="preguntas aparece">
{faq}
        </div>
        <div class="titulo centro" style="margin:56px auto 0"><h3 style="margin:0 0 10px">{t["fuentes_h"]}</h3></div>
        <ul class="fuente" style="max-width:820px;margin:0 auto;display:grid;gap:6px;padding-left:20px">
{fuentes}
        </ul>
      </div>
    </section>

    <section class="descarga">
      <div class="contenedor aparece">
        <div class="morse" aria-hidden="true"><i></i><i></i><i></i></div>
        <h2>{t["cta_h2"]}</h2>
        <div class="botones">
          <a class="boton principal" href="https://play.google.com/store/apps/details?id=com.irvingguerra.litedash" target="_blank" rel="noopener"><svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true" style="flex:none"><path d="M8 5v14l11-7z" fill="currentColor"/></svg> {t["cta_play"]}</a>
          <a class="boton secundario" href="https://github.com/IrvingGuerra/litedash/releases/latest/download/litedash.apk" download>{t["cta_apk"]} <small class="version">1.8 MB</small></a>
          <a class="boton secundario" href="{t["home"]}">{t["cta_web"]}</a>
        </div>
      </div>
    </section>
  </main>

  <footer>
    <div class="contenedor">
      <span>{t["footer"]}</span>
      <nav>
{footer}
      </nav>
    </div>
  </footer>

  <script>
  (() => {{
    const put = (sel, text) => document.querySelectorAll(sel).forEach(e => {{ e.textContent = text; }});
    if ('IntersectionObserver' in window) {{
      const io = new IntersectionObserver(es => es.forEach(e => {{ if (e.isIntersecting) {{ e.target.classList.add('visto'); io.unobserve(e.target); }} }}), {{ threshold: .12 }});
      document.querySelectorAll('.aparece').forEach(e => io.observe(e));
    }} else document.querySelectorAll('.aparece').forEach(e => e.classList.add('visto'));
    // El peso de LiteDash, en vivo desde la última versión de GitHub (si no contesta, se queda el texto fijo).
    fetch('https://api.github.com/repos/IrvingGuerra/litedash/releases/latest').then(r => r.ok ? r.json() : null).then(r => {{
      const apk = r && r.assets && r.assets.find(a => a.name === 'litedash.apk');
      if (!apk) return;
      const mb = (apk.size / 1e6).toFixed(1);
      put('.apk-mb', mb); put('.version', mb + ' MB');
    }}).catch(() => {{}});
  }})();
  </script>
</body>
</html>
"""


def main():
    for lang, path in (("es", SITE / "compare/index.html"), ("en", SITE / "en/compare/index.html")):
        path.parent.mkdir(parents=True, exist_ok=True)
        old = path.read_text() if path.exists() else ""
        html = page(lang)
        # Conserva el bloque de buscadores que pone tools/seo.py.
        if "<!-- seo:begin -->" in old:
            start, end = old.index("  <!-- seo:begin -->"), old.index("<!-- seo:end -->\n") + len("<!-- seo:end -->\n")
            html = html.replace("</head>", old[start:end] + "</head>", 1)
        path.write_text(html)
    print("compare/ y en/compare/ listos")


if __name__ == "__main__":
    main()
