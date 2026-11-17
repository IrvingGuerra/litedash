#!/usr/bin/env python3
"""Idiomas descargables de LiteDash (español e inglés vienen en la APK; los demás se bajan de litedash.dev).

    python3 tools/lang.py              rehace translations/en/ (la plantilla) y site/lang/ (paquetes + index.json)
    python3 tools/lang.py --check [tr] solo revisa translations/<tag>/ (para revisar un aporte); no escribe nada
    python3 tools/lang.py --pseudo xx --out DIR
                                       paquete de prueba "xx" (todo el inglés entre corchetes) + su index.json en DIR,
                                       para probar el motor sin publicar nada (DIR fuera de site/)

Lee los values-en/strings*.xml de todos los módulos (app, core/*, feature/*): esa es la única fuente de las claves y
del inglés. Cada idioma vive en translations/<tag>/ con los mismos XML que translations/en/ (se pueden partir o juntar
como se quiera) más name.txt con el nombre del idioma en ese idioma ("Türkçe"). Ver translations/README.md.
Python 3 del sistema, sin dependencias.
"""
import argparse
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
TRANSLATIONS = ROOT / "translations"
TEMPLATE = TRANSLATIONS / "en"
SITE_LANG = ROOT / "site" / "lang"

# Mismos topes que la app (LanguagePack.kt): un paquete más grande o un texto más largo no se usa.
PACK_MAX_BYTES = 512 * 1024
PACK_MAX_TEXT = 2000
CATEGORIES = {"zero", "one", "two", "few", "many", "other"}
RIGHT_TO_LEFT = {"ar", "arc", "ckb", "dv", "fa", "he", "iw", "ks", "ps", "sd", "ug", "ur", "yi"}
TAG = re.compile(r"[a-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$")
NAME = re.compile(r"[a-z0-9_]{1,100}$")
MARKER = re.compile(r"%(\d+\$)?([-#+ 0,(<]*\d*(?:\.\d+)?[a-zA-Z%])")

# Formas de plural que pide cada idioma (CLDR, lo que Android usa con números enteros). Lo que no está aquí: solo
# "other" es obligatoria. Las formas "many" del francés, italiano, portugués y catalán (para millones) no se piden.
PLURAL_FORMS = {
    **{lang: {"other"} for lang in ("ja", "zh", "ko", "vi", "th", "id", "ms", "km", "lo", "my", "yo")},
    **{lang: {"one", "other"} for lang in (
        "tr", "de", "nl", "sv", "da", "nb", "no", "fi", "et", "el", "hu", "bg", "it", "pt", "fr", "ca", "gl", "eu",
        "hi", "bn", "az", "kk", "ka", "hy", "sw", "af", "sq", "uz", "mn", "ta", "te", "ml", "mr", "gu", "kn", "pa",
        "fil", "tl", "is")},
    **{lang: {"one", "few", "many", "other"} for lang in ("ru", "uk", "be", "pl", "cs", "sk", "lt")},
    **{lang: {"one", "few", "other"} for lang in ("ro", "hr", "sr", "bs")},
    "lv": {"zero", "one", "other"},
    "sl": {"one", "two", "few", "other"},
    "ga": {"one", "two", "few", "many", "other"},
    "cy": {"zero", "one", "two", "few", "many", "other"},
}


def markers(text):
    """Como LanguagePack.kt: (sin orden: con posición, %% y %n), (los que toman el siguiente dato, en su orden) y si
    queda un "%" suelto. Cada marcador va completo: "%#s", "%D" o "%.2d" no son "%s" ni "%d" (String.format truena)."""
    loose, ordered = [], []
    for m in MARKER.finditer(text):
        spec = m.group(2)
        if m.group(1) is None and spec not in ("%", "n"):
            ordered.append(spec)
        else:
            loose.append((m.group(1) or "") + spec)
    return sorted(loose), ordered, "%" in MARKER.sub("", text)


def plural_fits(form, english_other):
    loose, ordered, stray = markers(form)
    en_loose, en_ordered, en_stray = markers(english_other)
    return stray == en_stray and all(x in en_loose for x in loose) and en_ordered[:len(ordered)] == ordered


def unescape(raw):
    """El texto como lo deja aapt2: comillas que guardan espacios, espacios juntos en uno, y los escapes con \\."""
    out, quoted, i = [], False, 0
    while i < len(raw):
        ch = raw[i]
        if ch == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            if nxt == "u" and re.match(r"[0-9a-fA-F]{4}", raw[i + 2:i + 6]):
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"n": "\n", "t": "\t"}.get(nxt, nxt))
            i += 2
            continue
        if ch == '"':
            quoted = not quoted
        elif ch.isspace() and not quoted:
            if not out or out[-1] != " ":
                out.append(" ")
        else:
            out.append(ch)
        i += 1
    return "".join(out).strip(" ")


def inner_xml(element):
    """El contenido de [element] tal cual (sin <xliff:g> ni etiquetas: no hay textos con formato)."""
    return "".join(element.itertext())


def read_resources(path):
    """{clave: (tipo, valor)} de un XML de Android; valor = texto, {categoría: texto} o [renglones]. Sin los no traducibles."""
    out = {}
    for element in ET.parse(path).getroot():
        name = element.get("name")
        if name is None or element.get("translatable") == "false":
            continue
        if element.tag == "string":
            out[name] = ("string", unescape(inner_xml(element)))
        elif element.tag == "plurals":
            out[name] = ("plurals", {item.get("quantity"): unescape(inner_xml(item)) for item in element.findall("item")})
        elif element.tag == "string-array":
            out[name] = ("array", [unescape(inner_xml(item)) for item in element.findall("item")])
    return out


def english_sources():
    """Los values-en/strings*.xml de todos los módulos, con un nombre de plantilla cada uno."""
    found = []
    # app/ primero: sus textos tapan los de los módulos con la misma clave.
    for path in sorted(ROOT.glob("*/src/*/res/values-en/strings*.xml")) + sorted(ROOT.glob("*/*/src/*/res/values-en/strings*.xml")):
        rel = path.relative_to(ROOT).parts
        if "build" in rel:
            continue
        module = rel[:rel.index("src")]
        source_set = rel[rel.index("src") + 1]
        label = "-".join(module + (() if source_set == "main" else (source_set,)))
        found.append((f"{label}-{path.name}", path))
    return found


def raw_entries(path):
    """Los bloques <string>/<plurals>/<string-array> traducibles de [path], tal como están escritos (con sus escapes)."""
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(r"[ \t]*<(string|plurals|string-array)\b[^>]*?(/>|>.*?</\1>)", re.S)
    return [m.group(0) for m in pattern.finditer(text) if 'translatable="false"' not in m.group(0).split(">", 1)[0]]


def load_english():
    """Todas las claves con su inglés. Si un módulo repite una clave, gana la de app/ (como al armar la APK)."""
    english = {}
    for _, path in english_sources():
        for key, value in read_resources(path).items():
            if key in english and english[key] != value:
                print(f"aviso: {key} está en dos módulos con distinto inglés; se usa el de {english_owner(key)}")
            english.setdefault(key, value)
    return english


def english_owner(key):
    return next(str(path.relative_to(ROOT)) for _, path in english_sources() if key in read_resources(path))


def write_template():
    """translations/en/ = copia exacta de los textos traducibles en inglés, un archivo por archivo de origen."""
    TEMPLATE.mkdir(parents=True, exist_ok=True)
    wanted, seen = set(), set()
    for name, path in english_sources():
        wanted.add(name)
        entries = [e for e in raw_entries(path) if re.search(r'name="([^"]+)"', e).group(1) not in seen]
        seen.update(re.search(r'name="([^"]+)"', e).group(1) for e in entries)
        body = "\n".join(entries)
        header = (f"<!-- Generado por tools/lang.py desde {path.relative_to(ROOT)}: no lo edites aquí.\n"
                  "     Generated by tools/lang.py: do not edit here. See translations/README.md -->\n")
        (TEMPLATE / name).write_text(f'<?xml version="1.0" encoding="utf-8"?>\n{header}<resources>\n{body}\n</resources>\n', encoding="utf-8")
    for old in TEMPLATE.glob("*.xml"):
        if old.name not in wanted:
            old.unlink()


def required_forms(tag):
    return PLURAL_FORMS.get(tag.split("-")[0], {"other"})


def check_language(folder, english):
    """Revisa translations/<tag>/: (paquete con solo lo válido, errores, avisos)."""
    tag = folder.name
    errors, warnings = [], []
    if not TAG.match(tag) or tag.split("-")[0] in ("es", "en"):
        return None, [f"{tag}: carpeta con un idioma que no se puede descargar (es/en vienen en la APK)"], []
    if tag.split("-")[0] in RIGHT_TO_LEFT:
        return None, [f"{tag}: los idiomas de derecha a izquierda todavía no se pueden usar"], []
    name_file = folder / "name.txt"
    name = name_file.read_text(encoding="utf-8").strip() if name_file.exists() else ""
    if not name or len(name) > 40:
        errors.append(f"{tag}: falta name.txt con el nombre del idioma en ese idioma (máx. 40 letras)")
    strings, plurals, arrays = {}, {}, {}
    for path in sorted(folder.glob("*.xml")):
        try:
            entries = read_resources(path)
        except ET.ParseError as e:
            errors.append(f"{path.relative_to(ROOT)}: XML ilegible ({e})")
            continue
        for key, (kind, value) in entries.items():
            where = f"{path.relative_to(ROOT)}: {key}"
            en = english.get(key)
            if en is None:
                errors.append(f"{where}: esa clave no existe en inglés")
                continue
            if en[0] != kind:
                errors.append(f"{where}: en inglés es {en[0]}, aquí {kind}")
                continue
            if key in strings or key in plurals or key in arrays:
                errors.append(f"{where}: repetida")
                continue
            if kind == "string":
                if not value.strip():
                    errors.append(f"{where}: texto vacío")
                elif len(value) > PACK_MAX_TEXT:
                    errors.append(f"{where}: más de {PACK_MAX_TEXT} letras")
                elif markers(value) != markers(en[1]):
                    errors.append(f"{where}: marcadores distintos al inglés ({en[1]!r})")
                else:
                    strings[key] = value
            elif kind == "plurals":
                other = en[1].get("other", "")
                bad = [c for c in value if c not in CATEGORIES]
                missing = required_forms(tag) - set(value)
                if bad:
                    errors.append(f"{where}: forma de plural desconocida {bad}")
                elif missing:
                    errors.append(f"{where}: plural incompleto, faltan {sorted(missing)}")
                elif any(not form.strip() or len(form) > PACK_MAX_TEXT for form in value.values()):
                    errors.append(f"{where}: forma vacía o muy larga")
                elif not all(plural_fits(form, other) for form in value.values()):
                    errors.append(f"{where}: una forma pide un dato que el inglés no da ({other!r})")
                else:
                    plurals[key] = value
            else:
                if len(value) != len(en[1]):
                    errors.append(f"{where}: {len(value)} renglones, el inglés tiene {len(en[1])}")
                elif any(not v.strip() or markers(v) != markers(e) for v, e in zip(value, en[1])):
                    errors.append(f"{where}: un renglón vacío o con marcadores distintos")
                else:
                    arrays[key] = value
    done = len(strings) + len(plurals) + len(arrays)
    if done == 0:
        warnings.append(f"{tag}: sin textos traducidos")
    # Quién lo revisó como hablante nativo (usuarios de GitHub, uno por renglón): sin nadie = traducción automática.
    reviewers_file = folder / "reviewers.txt"
    reviewers = [line.strip().lstrip("@") for line in reviewers_file.read_text(encoding="utf-8").splitlines()
                 if line.strip() and not line.strip().startswith("#")] if reviewers_file.exists() else []
    pack = {"lang": tag, "name": name, "strings": strings, "plurals": plurals, "arrays": arrays, "reviewers": reviewers}
    return pack, errors, warnings


def version_code():
    props = (ROOT / "version.properties").read_text(encoding="utf-8")
    major, minor, patch = map(int, re.search(r"VERSION_NAME=(\d+)\.(\d+)\.(\d+)", props).groups())
    return major * 10_000_000 + minor * 100_000 + patch * 100 + 99  # igual que app/build.gradle.kts


def encode(pack, base):
    data = {"lang": pack["lang"], "name": pack["name"], "base": base,
            "strings": dict(sorted(pack["strings"].items())),
            "plurals": {k: dict(sorted(v.items())) for k, v in sorted(pack["plurals"].items())},
            "arrays": dict(sorted(pack["arrays"].items()))}
    return json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def write_packs(packs, english, out, base):
    """Escribe <tag>.json e index.json en [out]; borra los paquetes que ya no están."""
    out.mkdir(parents=True, exist_ok=True)
    index = []
    for pack in packs:
        blob = encode(pack, base)
        if len(blob) > PACK_MAX_BYTES:
            sys.exit(f"{pack['lang']}: el paquete pesa {len(blob)} bytes (máx. {PACK_MAX_BYTES})")
        (out / f"{pack['lang']}.json").write_bytes(blob)
        done = len(pack["strings"]) + len(pack["plurals"]) + len(pack["arrays"])
        index.append({"lang": pack["lang"], "name": pack["name"], "progress": done * 100 // len(english),
                      "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest(), "base": base,
                      "reviewed": bool(pack.get("reviewers")), "reviewers": pack.get("reviewers", [])})
    for old in out.glob("*.json"):
        if old.name != "index.json" and old.stem not in {p["lang"] for p in packs}:
            old.unlink()
    index.sort(key=lambda e: e["name"].casefold())
    (out / "index.json").write_text(json.dumps({"languages": index}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for e in index:
        print(f"  {e['lang']}: {e['name']}, {e['progress']} %, {e['bytes']} bytes")


def pseudo(tag, english):
    """Todo el inglés entre corchetes (los marcadores no cambian): se ve de inmediato qué texto no pasa por el motor."""
    pack = {"lang": tag, "name": f"[Pseudo {tag}]", "strings": {}, "plurals": {}, "arrays": {}}
    for key, (kind, value) in english.items():
        if kind == "string":
            pack["strings"][key] = f"[{value}]"
        elif kind == "plurals":
            pack["plurals"][key] = {c: f"[{v}]" for c, v in value.items()}
        else:
            pack["arrays"][key] = [f"[{v}]" for v in value]
    return pack


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", nargs="?", const="*", metavar="TAG", help="solo revisar (todos o uno)")
    parser.add_argument("--pseudo", metavar="TAG", help="paquete de prueba con el inglés entre corchetes")
    parser.add_argument("--out", type=pathlib.Path, help="carpeta para --pseudo (no site/lang)")
    args = parser.parse_args()
    english = load_english()
    print(f"{len(english)} textos traducibles en inglés")

    if args.pseudo:
        if not args.out or args.out.resolve() == SITE_LANG.resolve():
            sys.exit("--pseudo necesita --out con una carpeta fuera de site/lang (no se publica)")
        if not TAG.match(args.pseudo) or args.pseudo.split("-")[0] in {"es", "en"} | RIGHT_TO_LEFT:
            sys.exit(f"{args.pseudo}: etiqueta que la app no aceptaría")
        write_packs([pseudo(args.pseudo, english)], english, args.out, version_code())
        return

    folders = sorted(p for p in TRANSLATIONS.iterdir() if p.is_dir() and p.name != "en") if TRANSLATIONS.exists() else []
    if args.check and args.check != "*":
        folders = [f for f in folders if f.name == args.check]
        if not folders:
            sys.exit(f"No existe translations/{args.check}/")
    packs, failed = [], False
    for folder in folders:
        pack, errors, warnings = check_language(folder, english)
        for w in warnings:
            print(f"aviso: {w}")
        for e in errors:
            print(f"ERROR: {e}")
        if errors:
            failed = True
        elif pack:
            done = len(pack["strings"]) + len(pack["plurals"]) + len(pack["arrays"])
            print(f"{folder.name}: bien, {done} de {len(english)} ({done * 100 // len(english)} %)")
            packs.append(pack)
    if args.check:
        sys.exit(1 if failed else 0)
    if failed:
        sys.exit("Hay errores: no se escribió nada en site/lang/")
    write_template()
    write_packs(packs, english, SITE_LANG, version_code())


if __name__ == "__main__":
    main()
