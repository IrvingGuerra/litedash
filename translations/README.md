# Traducciones · Translations

LiteDash trae español e inglés. Los demás idiomas **no van dentro de la app**: se bajan de litedash.dev cuando alguien
los elige (Ajustes → Inicio y apariencia → Idioma y unidades → Más idiomas), así la app sigue ligera.
*LiteDash ships Spanish and English. Other languages are downloaded from litedash.dev when picked (Settings → Home &
look → Language & units → More languages), so the app stays small.*

## Cómo traducir

1. Copia la carpeta `translations/en/` a `translations/<idioma>/` (código de idioma: `tr`, `de`, `pt-BR`…).
2. Crea `translations/<idioma>/name.txt` con el nombre del idioma en ese idioma (`Türkçe`).
3. Traduce el texto de cada `<string>`, `<plurals>` y `<string-array>`. Es el mismo formato que `strings.xml` de
   Android: escribe `\'` para el apóstrofo y `\n` para un salto de línea.
4. **No cambies** los nombres (`name="…"`) ni los marcadores: `%1$s`, `%2$d`, `%d`, `%s`, `%.1f`, `%%`. Puedes moverlos
   de lugar dentro de la frase si llevan número (`%1$s`). En los plurales pon las formas de tu idioma (`one`, `few`,
   `many`, `other`…); la forma `one` puede no llevar el número.
5. No hace falta traducir todo: lo que falte sale en inglés. Puedes borrar lo que no traduzcas.
6. Revisa tu trabajo: `python3 tools/lang.py --check <idioma>` (Python 3, sin instalar nada) y abre un PR.

Los archivos de `translations/en/` se generan solos desde la app con `python3 tools/lang.py`: no los edites.
Todavía no se pueden usar idiomas de derecha a izquierda (árabe, hebreo, persa…).

## How to translate

1. Copy `translations/en/` to `translations/<language>/` (language code: `tr`, `de`, `pt-BR`…).
2. Create `translations/<language>/name.txt` with the language's own name (`Türkçe`).
3. Translate the text of every `<string>`, `<plurals>` and `<string-array>`. Same format as Android's `strings.xml`:
   write `\'` for an apostrophe and `\n` for a line break.
4. **Don't change** the names (`name="…"`) or the placeholders: `%1$s`, `%2$d`, `%d`, `%s`, `%.1f`, `%%`. Numbered ones
   (`%1$s`) can move inside the sentence. In plurals use your language's forms (`one`, `few`, `many`, `other`…); the
   `one` form may leave the number out.
5. You don't have to translate everything: anything missing shows in English. You can delete what you don't translate.
6. Check your work: `python3 tools/lang.py --check <language>` (Python 3, nothing to install) and open a PR.

`translations/en/` is generated from the app by `python3 tools/lang.py`: don't edit it.
Right-to-left languages (Arabic, Hebrew, Persian…) aren't supported yet.

## Revisar un idioma

Algunos idiomas empezaron con traducción automática y necesitan a alguien que los hable de nacimiento.

- **Un error suelto:** repórtalo con el
  [formulario de traducción](https://github.com/IrvingGuerra/litedash/issues/new?template=translation.yml).
- **Todo el idioma:** corrige los archivos de `translations/<idioma>/` y abre un PR que agregue tu usuario de GitHub a
  `translations/<idioma>/reviewers.txt` (uno por línea). Con eso el idioma queda como revisado por la comunidad y tu
  nombre aparece en [litedash.dev/translate/](https://litedash.dev/translate/).

## Review a language

Some languages started as machine translations and need a native speaker.

- **One mistake:** report it with the
  [translation form](https://github.com/IrvingGuerra/litedash/issues/new?template=translation.yml).
- **The whole language:** fix the files in `translations/<language>/` and open a PR adding your GitHub login to
  `translations/<language>/reviewers.txt` (one per line). The language then shows as community reviewed and you're
  credited on [litedash.dev/translate/](https://litedash.dev/translate/).

## Para quien publica · For maintainers

`python3 tools/lang.py` revisa todo, rehace `translations/en/` y escribe `site/lang/<idioma>.json` y
`site/lang/index.json` (avance, tamaño y SHA-256); se publica con la web, sin versión nueva de la app.
Para probar sin publicar: `python3 tools/lang.py --pseudo xx --out /tmp/lang` (todo el inglés entre corchetes).
