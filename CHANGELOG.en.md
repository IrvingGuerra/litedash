# Changelog

What people using LiteDash notice, in English. The full history (in Spanish) is in [CHANGELOG.md](CHANGELOG.md).
Format [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions [SemVer](https://semver.org/).

## [1.5.16] - 2026-11-24

Tidier Settings that are easier to find things in, and a whole country's maps in one tap.

### Added
- **A whole country's map:** in Settings → Apps → Navigation → Offline, search your country (e.g. "Mexico"): it comes
  first with **"Download all"** and its size, with all its states below. They download one after another, with progress
  and Cancel.
- **"Mark here"** on the full-screen map: tap the pin button, move the map to the crosshair and mark the spot without
  long-pressing.
- **Add widget** right from Settings → Home & look → Home.
- **Reset the trip** from the Trip and Road widget options (with a confirmation).
- A "Use LiteDash Connect" button when your screen's Bluetooth music can only be controlled from the phone.

### Changed
- **Settings reorganized:**
  - Home & look: Home · **Language & units** (km/°C on top, in view) · Bar · Interface · **Theme & brightness**.
  - The background (image or map) is picked in Home; "Hide the Android bar" moved to Bar.
  - **Weather** is now a module (Settings → Modules), like Workshop and Tires.
  - **About & help** (was System): "Report a problem" has its own tab.
  - Modules → Permissions lists every permission and only asks when you tap it.
  - Workshop always has tabs, with "Adapter and trips"; Tires: Sensors · Pressure and alerts · Calibrate · On the home screen.
- **Languages and countries:** a few are shown, with "See all".
- **One map look** for every widget and the full-screen map (Settings → Apps → Navigation); the "Full" map is gone (it
  didn't work for routes or search).
- **Guidance:** back no longer ends it (only the red button), it closes by itself 30 s after arriving, and shows "—"
  without speed.
- **Phone:** the app you pick is used by the widget, the bar and the steering wheel.
- Tapping the car shows it big by default. Same names on the bar and the wheel, and plainer texts.
- New icon for the Android Auto / CarPlay button.
- Light theme: grey texts easier to read. Round scales for mpg and gal/h.

### Fixed
- What you had picked in the Phone or Android Auto / CarPlay widget, or in a Map widget's look, is kept.
- Routes no longer wait on the network while planning; turn restrictions are followed better.
- Old map caches nobody used anymore are cleaned up (up to ~25 MB).
- Size: dropped the x86_64 libraries no car screen uses.

## [1.5.15] - 2026-11-23

This week's full audit: many small fixes across the app. What you'll notice most:

### Added
- **Android Auto / CarPlay button on the bar** (and a steering wheel action). It opens your projection app (ZLink,
  AutoKit, CarLetter, Open Headunit…) or the one you pick in **Settings → Apps → Android Auto / CarPlay**. Like any
  button, you can remove or move it. The Bluetooth button opens only Bluetooth again.
- **"Dim the background"** next to your wallpaper: keep your image cleaner or more subdued (#34).
- **+ and − buttons** on the full-screen map, for head units where the two-finger gesture doesn't respond.
- **Shuffle** for Files music, one tap away in the full-screen player.
- **Map downloads resume:** if the network drops they continue where they were (on their own when internet returns,
  or when you tap "Download"), and you're told beforehand if a map doesn't fit.

### Changed
- **Settings → Apps → Navigation** now works like Music and Radio: pick **"Navigate with"** (LiteDash map or Maps app)
  at the top and its options appear below. "Your maps app" accepts any app with "Other app…".
- **Offline maps are easier to find:** the list says whether it's downloading or failed (with "Retry"), state maps show
  with any map style, and the search finds the state or the country in any language.
- **Languages:** all 21 downloadable languages are visible (before they only showed when typing). With "System" and a
  system that isn't in Spanish, LiteDash shows in English or in the language you already downloaded.
- **Glass cards over the map background** are now smoked glass instead of a flat panel (#32), and the Weather widget
  looks like the other cards (#33).
- Place search sorts better: first what's named exactly like what you typed and what's nearby.
- With the bar hidden, a tap on any free spot brings it up. In the app drawer, "Choose" lets you hide an app or see
  its info without long-pressing. Applying a template over a page with widgets asks first.

### Fixed
- **"Open LiteDash on startup"** failed on some startups; it also recognizes ignition on more head units (TS10 / QF,
  Autochips, Jancar).
- **Music:** the on-screen button always does what it shows (no more pressing play twice); the steering wheel now
  tells radio from music; Bluetooth on Android 6–8 shows the song; on head units whose Bluetooth can't be controlled,
  the widget says so instead of showing a play button that does nothing.
- **Guidance:** it tells you when you leave the route, a recalculated route no longer starts backwards, and when you
  end guidance the route is drawn again from where you are.
- **Weather:** on Android 6–10 it could get stuck on "Looking for the weather…"; offline it shows the forecast
  temperature.
- **OBD:** steadier with clone adapters; it no longer insists on the USB cable permission; "Disconnect" is respected.
- **Tires:** on head units without a clock battery the history is kept, so the slow leak warning can work.
- **Head units without a clock battery:** when the date is wrong, LiteDash says so instead of "check your internet".
- Your car → Models: the catalog no longer says "Checking…" and "Couldn't download" at once, and has "Retry".
- With the light theme and a wallpaper, the Settings menu and what sits outside cards are readable again.
- Over a hundred minor audit fixes (memory, background work, texts and permissions).

## [1.5.14] - 2026-11-18

### Changed
- **Arrange the bar without long-pressing:** in Settings → Home & look → Bar, tap a button on the bar (or one of its two
  lines, or your favorites) and move it with the arrows, or take it off with "Remove". On many head units long-press
  and drag didn't respond well. On the home screen you can still drag as before.

## [1.5.13] - 2026-11-18

### Changed
- **New glass, crystal style:** glass cards now have almost no fill: your wallpaper shows through, less blurred and with
  livelier colors, with an edge of light that starts at the corner. If your image is very bright (or very dark with the
  light theme), the background behind each card is dimmed just enough for the text to stay readable.
- **"How visible" now goes from crystal to solid:** in Settings → Home & look → Interface, all the way left is pure
  crystal and moving right fills the card with its color. Crystal is the default.
- On pages with the map as background, cards keep their previous fill (the map moves and can't be blurred).

## [1.5.12] - 2026-11-18

### Changed
- **The card sample is your real home screen:** in Settings → Home & look → Interface, the sample uses the piece of your
  wallpaper at home-screen scale and the card has the same glass as your widgets (it used to show the whole wallpaper
  shrunk and without blur, so it didn't match what you got).
- **Narrow, tall Music widget:** the buttons are centered when there is no room for the cover on the side; if the widget
  is a bit taller, a small cover shows next to the title.

## [1.5.11] - 2026-11-18

### Changed
- **Route and 3D guidance: clearer about why they ask for the map:** the notice now says they use the downloaded map of
  your state or country even with internet, with a button straight to the download. If there is no map for your area
  yet, the "Available maps" button takes you to the list, where you can also request yours.

### Fixed
- **The volume control with glass cards** no longer shows see-through over the home screen (it was unreadable): it is
  always a solid card.
- **Cut-off cover in the Music widget:** in a narrow, not very tall widget the cover showed as a thin strip on top; when
  it doesn't fit properly it is left out and the title and buttons are centered.

## [1.5.10] - 2026-11-18

### Fixed
- **The steering wheel play/pause button now pauses on the first press:** if music (Spotify or another app, or your
  files) was already playing and the Music widget was off screen or didn't know yet, the wheel button sent "play"
  again instead of "pause", and you couldn't pause without touching the screen. LiteDash now checks whether the app is
  playing at that moment before deciding.

## [1.5.9] - 2026-11-18

### Added
- **Home button on the bar:** takes you back to the dashboard and closes whatever is open or full screen (map, music).
  It shows up at the start of the bar; move or remove it like any other button.
- **Arrange the bar from its settings too:** Settings → Home & look → Bar now shows the bar itself; drag the buttons
  to reorder them, remove them with the x and move the two lines that split it.

### Changed
- **The route fits the screen:** when you tap "Route", the map frames the whole way using all the free space, by
  width or height depending on the route. It used to zoom out too far: the route looked small and sometimes the map
  from that far wouldn't load.
- Your favorite apps, while you haven't picked any, show a star when arranging the bar (they had the same icon as
  "All apps").

## [1.5.8] - 2026-11-18

### Changed
- **"Use an external maps app" now covers everything:** the option in Settings → Apps → Navigation picks a single
  path. Off (the default), everything uses the LiteDash map: a tap on the Map widget, Home, Work, the bar and the
  steering wheel; the maps app button is gone from the widget, and so is "Open in…" on the place card. On, the widget
  still shows the map with your arrow, but a tap opens your maps app directly (Waze, Google Maps…), and so do Home,
  Work, the bar and the steering wheel.
- **Home and Work on the LiteDash map:** with the external app off, tapping them opens the place on the LiteDash map,
  ready to plan the route.
- **The Map widget no longer has a compass:** that belongs to navigation (still on the full-screen map and the guide).

## [1.5.7] - 2026-11-18

### Changed
- **Countries with maps, in plain sight:** Settings → Apps → Navigation → Offline → By state now always shows the
  available countries (Mexico, United States, Puerto Rico, Canada and Colombia); tap one to see its states. They used
  to be hidden behind "Other states" and many people requested a country that was already there.
- **Offline opens on "By state"** even when the screen doesn't know where you are yet (it opened on "By zone" and the
  states weren't even visible), and turns the GPS on while it's open to put your state on top.
- The technical report includes which maps you have downloaded.

## [1.5.6] - 2026-11-18

### Changed
- **The route notice is no longer confusing:** it said "For the route without internet, download your state's map" and
  many read it as having no internet. LiteDash plans routes with your state's downloaded map; the notice now says which
  one is needed ("you need the map of Jalisco") and explains that zones are only for viewing the map.
- **"Download map" button on that notice:** it takes you straight to Settings → Apps → Navigation → Offline.
- If there is no map of where you are yet, the notice says so instead of sending you to download something that doesn't exist.

## [1.5.5] - 2026-11-18

### Changed
- **Natural turns in guidance:** at corners the blue route no longer makes a square corner; it follows the curve a car
  takes when turning, and your car follows it.

### Fixed
- **Tidal and other music apps missing from the list** (Settings → Apps → Music → App): apps that declare themselves
  as music apps now show up even if they don't offer their library, and Tidal, Qobuz, SoundCloud and Pandora always do
  when installed. (Any other app can still be picked with "Other app".)

## [1.5.4] - 2026-11-18

### Fixed
- **The blue route line was missing on Android 8.1 and older:** on many head units the route was not drawn in guidance
  ("Go") or on the map next to it. Now only the visible part is drawn, and it works on any screen.
- **Guidance no longer recalculates endlessly** when you leave a parking lot or the GPS is far from the street (the
  card flickered between "Recalculating" and "Waiting for location").
- The route is calculated again by itself if your real location turned out to be far from the last known one, and when
  the list of states arrives after opening without internet.
- In guidance, the time, the speed and the next turn no longer overlap Android's status bar.
- "View route" picks the right zoom on screens with low or very high density.

### Changed
- **Cleaner, Tesla-style guidance:** the route is a plain blue ribbon (no white edge or glow) and two-way streets get a
  single thin yellow line instead of two.
- **Lighter map:** when stopped it no longer redraws 30 times a second (GPS drifts even when you don't move); map
  pieces are drawn without reading the disk again, on threads with lower priority than the interface, and with a memory cap.
- Every route and recalculation reuses the open router (it used to reopen the streets file each time).

## [1.5.3] - 2026-11-18

### Fixed
- **Android 8 and older:** after picking a place in the map search, the keyboard opened again and covered the place
  card (Route, Favorite, Home/Work). Now the keyboard hides and the card shows up.

## [1.5.2] - 2026-11-18

### New
- **Offline maps for three more countries:** the **United States** (all 50 states, Washington, D.C. and Puerto Rico),
  **Canada** (all 10 provinces and 3 territories) and **Colombia** (all 33 departments). Each state complete in a
  single download; big ones weigh more (California is ~700 MB): download them on a good connection.

### Changed
- **Routing uses far less memory:** only what it explores, not the whole state (in California, a city route goes from
  ~46 MB to ~1 MB), so big states also work on 1 GB screens.
- If a trip doesn't fit in your screen's memory, LiteDash tells you ("This trip is too long…") instead of closing.

## [1.5.1] - 2026-11-17

### Changed
- **One tap on the map opens LiteDash navigation.** The Map widget always opens the LiteDash map full screen (search,
  route and guidance); your maps app (Waze, Google Maps…) is on its corner button.
- The bar's navigate button and the steering wheel button also open the LiteDash map. If you prefer your maps app,
  turn on "Open your maps app from the bar and steering wheel" in Settings → Apps → Navigation (if you had changed it
  before, it stays as you left it).

## [1.5.0] - 2026-11-17

**LiteDash 1.5: Offline navigation.** Everything from 1.4.168 to 1.4.184 in one release: no API keys, no accounts.

### New
- **Download your state or an area** and search, get your route and follow it without internet.
- **Tesla-style guide:** your car in 3D on the blue route, streets with lane lines, 3D buildings, the next turn and
  your music next to the map.
- **Compass** on the Map widget, the full-screen map and the guide.
- **Your country isn't there? Request it** from Settings → Apps → Navigation → Offline.
- Map background and pages on the home screen, a glass look across the app and the app bar in parts.

## [1.4.184] - 2026-11-17

### New
- **Request your country's map.** In Settings → Apps → Navigation → Offline, "Your country isn't here? Request it"
  opens a ready-made report: type your country and region and we add it so it works without internet.

### Fixed
- The Map widget compass did nothing on the first tap: it now points the way the map is turned and switches at once.
- On phones in landscape the map search keyboard closed as soon as it opened.

## [1.4.183] - 2026-11-17

### Changed
- In the guide, the music sits at the bottom of the map side only (like Tesla); your car's view reaches the bottom of
  the screen.

## [1.4.182] - 2026-11-17

### New
- **Turn-by-turn, Tesla style.** The route is a glowing blue band that runs under your whole car; streets are filled
  in, with sidewalks, edges and a yellow center line on two-way streets (a dashed white one on wide one-way streets),
  and buildings rise in 3D with their real height (where the map has it).
- **Music in the guide.** Across the bottom, like Tesla: see what's playing and skip songs without leaving.
- **Map compass.** A round button on the Map widget, the full-screen map and the guide: it points north and a tap
  switches between "always north" and "heading up". In Settings the orientation now has three options: Automatic (as
  before), Heading up and Always north.
- **The hidden bar peeks with a tap** (#27). With "Hide the bar" on, tap an empty spot on the home screen and it shows
  over your widgets for a few seconds, without moving them.

### Changed
- In the guide, "Route overview" and "End" are now round buttons on the right of the map, next to the compass.
- Alerts (tires, engine, "LiteDash isn't your home screen"…) float at the top over your widgets instead of pushing
  them down (#27); with the map or music full screen only the car alerts stay.
- The app search sits at the top, between the title and the ✕, so the list gains almost a row on short screens (#28).
- The route is drawn in blue on every map.

### Fixed
- On Android 6 the search keyboard no longer stays open over the guide.

## [1.4.181] - 2026-11-17

### Changed
- When arranging the app bar, the other icons move out of the way while you drag one (like the home widgets); you no
  longer have to drop it to see the result.

## [1.4.180] - 2026-11-17

### New
- **The app bar, your way.** When you arrange it (long-press it) two lines appear: whatever sits before the first goes
  left, between the two goes in the center and after the second goes right. Drag the icons to reorder them or move
  them to another part, and drag the lines too. On the side bar it is top, center and bottom.

### Changed
- **Colors are easier to pick.** In Settings → Home & look → Interface, everything about color lives under "Cards":
  the ready-made combinations first and, when you tap the palette (the last circle), the card color and the interface
  color open separately.

## [1.4.179] - 2026-11-17

### Changed
- **A more understated default background.** With no wallpaper, the glow behind the glass no longer uses amber: it
  is now neutral light (silver and steel), in dark and light themes. If you pick an interface color, the glow takes it.

## [1.4.178] - 2026-11-17

### New
- **The whole app wears the look you pick.** The card style is no longer just for the home screen: the bottom bar,
  full-screen music and map, Settings, the Workshop and every panel share the same glass and the same color.
- **Glass by default.** Cards show your wallpaper blurred behind them; with no image, a soft glow in your color. If
  you prefer the old look: Settings → Home & look → Interface → Cards → "Solid".
- **Vivid colors and ready-made combinations.** Blue, green, wine, orange, violet, sand… or any color you like. You
  pick the color and LiteDash fits it to the theme: deep at night and light by day, so everything stays readable.

### Changed
- Cards are solid or glass; "translucent" ones become glass.

### Fixed
- With glass, the speedometer and gauges no longer looked dimmed: their ring and ticks now read on any background.

## [1.4.177] - 2026-11-17

### Fixed
- **The Bluetooth OBD scanner stays connected.** It used to disconnect a while after its widgets were out of sight
  (opening Settings, full-screen music or map, or switching pages) and then reconnect from scratch; several ELM327
  adapters failed on that reconnect. It now connects once and stays connected until you disconnect it or turn the
  module off.
- If the head unit's Bluetooth is off, or slow to come up when you start the car, the Workshop says so clearly and
  connects by itself as soon as it turns on. "Turn on Bluetooth" opens Android's Bluetooth settings when the head
  unit doesn't turn it on by itself, and "Turn off Bluetooth" now asks to confirm (it sat next to the adapters).
- Each connection attempt is lighter (one round instead of two) and, with the adapter asleep, it retries at most
  once a minute instead of every five.
- Swiping the Music or Radio widget changes track or station even when the head unit shows no song info (same as
  its buttons).
- The map no longer jumps to the middle of the ocean when the GPS reports "0, 0" with no signal: it keeps your last
  position.

## [1.4.176] - 2026-11-17

### New
- **Turn-by-turn guidance, without internet:** with the route ready tap «Go» and the driving screen opens. On the left,
  your car from behind on the streets, with the route as a band as wide as the car (like Tesla); on the right, the map
  with the next turn («50 m · Turn left onto Av. Insurgentes», and «Then…»), arrival time, what's left and a progress
  bar. Upright screens show the car on top and the map below.
- If you leave the route it recalculates instantly on your screen; when you get there it says «You've arrived».
- «See route» shows the whole route and «End» takes you back where you were, without touching your home screen.

## [1.4.175] - 2026-11-17

### New
- **Your route, without internet:** on a place's card tap «Route» and LiteDash works it out on your screen with your
  state's streets (it follows one-way streets and banned turns). You see the route drawn on the map, how long it is,
  how long it takes (without traffic), when you arrive and which road you take. Turn-by-turn guidance comes in the next
  stage; meanwhile, «Open in your maps app».
- Route streets come with your state's map. If you already had it, your first route offers to download them (Colima
  is 1.7 MB and the State of Mexico, the largest, 22 MB).
- If the place is in another state or you haven't downloaded yours, it tells you and lets you open it in your maps app.

### Changed
- With a place's card open, the map puts the place in the part the card leaves free.

## [1.4.174] - 2026-11-16

### New
- **Search where to go on the map:** in the full-screen map (or with the magnifier on the Map widget) type a place,
  street, neighborhood or town and results appear instantly, **without internet**, from your state's map (nearest
  first, even without accents or with a typo). If it isn't there, «Search online» looks it up worldwide with a single
  query. Tap a result and the map goes there with a pin and its card: how far it is, open it in your maps app, save it
  as a favorite or set it as Home or Work. Your recents and favorites stay as shortcuts.
- **Long-press the map** to mark any point (a ranch, an entrance) and open it in your maps app.
- **Places from other apps:** a shared place (WhatsApp, the browser…) can be opened with LiteDash and shows on its map.
- Offline search downloads with your state's map (and for anyone who already had it, the first time they search).

### Changed
- **Offline, clearer:** choose «By state» (your whole state's map) or «By zone» (a circle around you); if your state
  isn't there yet, use the zone.

## [1.4.173] - 2026-11-16

### Changed
- **Search in «More languages»:** instead of the full list, type your language (in your own or in the app's language:
  «german» or «alemán» find «Deutsch») and download it. If it isn't there yet, it tells you where to help translate it.

## [1.4.172] - 2026-11-16

### Fixed
- **«More languages» now shows every language** after updating the app (it could keep the old list for up to a day).

### Changed
- **Search in «Other states»** of the offline map: type the name and it shows up (without typing, the ones in your
  country).

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
