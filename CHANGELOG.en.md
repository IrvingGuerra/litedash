# Changelog

What people using LiteDash notice, in English. The full history (in Spanish) is in [CHANGELOG.md](CHANGELOG.md).
Format [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions [SemVer](https://semver.org/).

## [1.4.171] - 2026-11-16

### New
- **Your whole state's map, without internet:** Settings → Apps → Navigation → Offline now has «Your state's map»:
  LiteDash knows which one you're in and you download it at once (4 to 45 MB, depending on the state). All 32 Mexican
  states are ready; you can also download others, update them or delete them. Your zones stay as they are.

## [1.4.170] - 2026-11-16

### New
- **«Map background» layout** (Layouts → Map): a column of widgets on the right (at the bottom on portrait screens)
  and the live map behind, with you in the free space.

### Changed
- **Your car on the map background looks big**, seen from behind with its shadow, sized to your screen (it used to be
  tiny).
- Layouts decide the page background: «Map background» sets the map and the others remove it (no more map background
  and Map widget at the same time).
- On a page with the map background, «Add» no longer offers the Map widget; if you pick the map background on a page
  that has one, you're told and can remove it.

## [1.4.169] - 2026-11-16

### New
- **Pages on your home:** while arranging, «+» adds another page (up to 5) with its own widgets; swipe on an empty spot
  to change pages, and the dots at the bottom show where you are. With a single page, everything stays as before.
- **The map as background:** each page picks its background, your image or the live map. The map follows you behind
  your widgets, with you in the space they leave free, and if Waze or Maps is guiding you, its next turn shows there.
  You can appear as an arrow or as your car (then the map turns toward where you're going).
- **Cards your way:** in Home & look → Interface → Cards you pick their color and whether they're solid, translucent or
  glass (with your wallpaper blurred behind), and how visible they are.

## [1.4.168] - 2026-11-16

### Changed
- **Offline maps within the map server's rules:** each download is 10, 20 or 30 km (OpenFreeMap allows up to 3,000
  tiles or 50 MB per download). Far from the equator, if the area comes out bigger, you're told before downloading.
  Areas you already saved stay.

## [1.4.167] - 2026-11-16

### New
- **23 languages:** Spanish, English, French, Portuguese and Turkish are joined by German, Italian, Russian, Ukrainian,
  Polish, Czech, Dutch, Swedish, Romanian, Hungarian, Greek, Chinese, Japanese, Korean, Hindi, Thai, Indonesian and
  Vietnamese. They download when you pick them in Settings → Home & look → Interface → Language → More languages.
- **Review your language:** the list shows which ones a native speaker checked and which are machine translated. If you
  spot a mistake, report it at litedash.dev/en/translate.
- Each version's release notes show in English when the app isn't in Spanish.

### Fixed
- With "Use external app" off, the big map no longer shows the button that opens your maps app.
- Changing the language takes you back to Settings, where you were, instead of the home screen.
- Offline map zones show their size in miles when you use miles.
- In the photo and folder browser, "back" goes up a folder instead of closing everything.
- On portrait screens, the "Arrange your home" hint and the widget descriptions are no longer cut off.
