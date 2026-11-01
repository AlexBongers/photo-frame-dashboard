# Fotolijst Panel: a native Home Assistant wall panel

A standalone Android app for the PF1007L frame (and any Android 6+ tablet) that shows Home Assistant
without a WebView. It reads live states over the WebSocket API with a long-lived access token and
draws them with plain Android views. No Gradle, no libraries: the same toolchain as `dashboard/kiosk`.

Why: the Home Assistant web page needs about 13 s to load on this frame's A33 processor, switching
tabs takes 0.5 to 2 s, and the calendar card keeps the main thread busy for seconds. The panel starts in
about 0.7 s, switches pages instantly and uses about 16 MB instead of about 120 MB.

## What it shows

Four pages, set in `assets/panel.json`:

| Page | Content |
| --- | --- |
| Vandaag | the calendar events of the next 10 days (`agenda_days` in `panel.json`, 7 to 21) in two columns side by side, days never split across columns, with buttons for the previous and next whole week and back to today (REST `/api/calendars`; it returns to today after 3 minutes), current weather and a 5-day forecast (`weather.get_forecasts`), room temperatures |
| Klimaat | per climate entity: mode buttons and a target temperature with - and + (`climate.set_hvac_mode`, `climate.set_temperature`) |
| Licht | tiles per light group; tap toggles, and dimmable lights have a brightness bar you drag (sent once when you let go) |
| Energie | cards with a big value and extra lines (power, solar yield, plugs) |
| Boodschappen | a Home Assistant to-do list, for example from the Bring! integration: big tickable rows in two columns (tap = bought), a field to add items with the on-screen keyboard, and "recent gekocht" on the right (tap to put an item back). Uses `todo.get_items`, `todo.add_item` and `todo.update_item`; set the entity as `list` on the page in `panel.json` |
| Muziek | Sonos: pick a speaker, album art, title and artist, a progress bar, previous, play/pause and next, shuffle and repeat, a volume bar with mute, and a list of presets on the right: the Spotify playlists from the `playlists` array in `panel.json` (tapping one plays it on the selected speaker through `media_player.play_media` with a Spotify share link, which needs Spotify linked in the Sonos app), or, without that array, the player's own Sonos favourites (its source list), plus a Spotify search field (see below) |

The top bar has the tabs, a permanent clock and date in Dutch, and a status dot. Tap the dot for Wi-Fi
settings, Android settings and reconnect.

## Build and install

```sh
post-root/build-kiosk.sh          # one-time: toolchain check (JDK 17, build-tools 34)
panel/build.sh                    # builds panel/build/panel.apk
adb -d install -r panel/build/panel.apk
```

## Connect it to Home Assistant

1. In Home Assistant open the profile of the user the panel should use, scroll to **Long-lived access
   tokens** and create one. Use a dedicated user that is not an administrator (the `frame` user). Copy
   the token once; Home Assistant never shows it again. Do not paste it into chats or commit it.
2. Save the address and token on the frame, once:

```sh
adb -d shell am start -n nl.fotolijst.panel/.MainActivity \
  --es ha_url http://192.168.68.135:8123 --es ha_token 'PASTE_TOKEN_HERE'
```

They are stored in the app's private preferences. Revoke the token in the profile to cut access.

3. To make the panel the Home app instead of Home HQ:

```sh
adb -d shell am start -n nl.fotolijst.panel/.MainActivity --ez home true
adb -d shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

Tap **Panel** and **Always** on the frame. Undo: pick Home HQ in the chooser the same way, or run the
first command with `--ez home false`. Home HQ stays installed as the fallback.

The Home HQ navigation service draws Back and Dashboard buttons over every app. Turn it off in Android
Settings -> Accessibility if you do not want them over the panel.

## Ambient light

While the panel is on screen it follows the frame's light sensor (LTR-C216R-14):

- **Screen brightness** from a curve of lux to brightness.
- **A dim layer**: a black layer over the whole screen, to go darker than the backlight can. With the default
  curves 15 lux (a dark room) gives 17% backlight plus a 23% dim layer; a finger over the sensor (about 1 lux)
  gives 5% plus a 49% dim layer within about 6 s.
- **Automatic theme**: dark switches to light when the light stays above 250 lux for 20 s, and back to dark
  when it stays below 80 lux for 20 s. A short sunbeam does not flip it. The chosen theme is remembered.

The lux value is smoothed over 2 s, and a level only changes when the target moves by 2% (brightness) or 3%
(dim layer). All of it is set in `panel.json` under `ambient` (`curve`, `dim_curve`, `smoothing_seconds`,
`auto_theme`); curves are `[lux, value 0 to 1]` points interpolated on a log scale of lux. Tap the status
dot to see the current lux and brightness.

```sh
adb -d shell am start -n nl.fotolijst.panel/.MainActivity --ez ambient false      # hand brightness back to Android
adb -d shell am start -n nl.fotolijst.panel/.MainActivity --ez auto_theme false   # keep the configured theme
adb -d shell am start -n nl.fotolijst.panel/.MainActivity --es theme light        # try a theme now (light or dark)
```

The reaction logic is in `AmbientModel.java`, which has no Android classes, so it can be tried on a computer
with simulated lux readings.

## Spotify search

The search field at the top of the right-hand list on the Muziek page searches the Spotify catalogue (tracks,
albums, playlists). Type with the on-screen keyboard, press its search key, and the results replace the playlists.
Tap a result to play it on the selected speaker, tap the + to add it to the queue, tap the cross to go back.
It does not search your own library: Home Assistant's Sonos search only covers the local music library.

It needs a Spotify app id and secret (client credentials, free), once:

1. Open https://developer.spotify.com/dashboard, log in, **Create app**. Any name; as redirect URI use
   `http://127.0.0.1:8888/callback`; tick Web API. Spotify may require a Premium account for developer apps.
2. Open the app's Settings and copy the **Client ID** and **Client secret**. Treat the secret like a password.
3. Save them on the frame:

```sh
adb -d shell am start -n nl.fotolijst.panel/.MainActivity --es spotify_id 'CLIENT_ID' --es spotify_secret 'CLIENT_SECRET'
```

`--ez spotify_reset true` removes them again. `--ez spotify_probe true` logs whether the frame can reach
api.spotify.com over HTTPS (it can: HTTP 401 without a token). Playing a result uses the same Spotify share link
route as the playlists, so Spotify must be linked in the Sonos app.

## Night mode: screen off

Between the `night.from` and `night.to` hours (default 23:30 to 07:00), once the room has been darker than
`dark_lux` for `dark_minutes` and nobody touched the screen for `idle_seconds`, the panel lets Android switch
the display off (it sets the screen timeout to 15 s and puts the old value back afterwards). The screen comes
back at the end of the window, or earlier when the room light stays above `wake_lux`, or
when you wake it by hand: while the display is off the touch screen does not report touches on this frame
(tested: a tap does not wake it), so the panel watches the light sensor instead. Hold a finger over the sensor
for about 1.5 s (the light must drop to a third), or switch the room light on. The power button also wakes it,
if the frame has one. While it is off, a small wake lock keeps the CPU awake
to watch the light sensor and to keep the wake time with an in-process timer. An alarm is set as a backup, but this
firmware's vendor background manager (`BackgroundManagerService`, "package not in whitelist") turns alarms of
apps it does not know into non-waking ones, so the alarm alone did not wake the screen in testing. Set `"only_when_dark": false` to switch off purely by the clock.

It needs the "Modify system settings" permission, once. Open the status dot menu and tap the Nachtstand line,
or grant it over USB:

```sh
adb -d shell appops set nl.fotolijst.panel WRITE_SETTINGS allow
```

Try it without waiting for night (sleeps now and wakes after 1 minute):

```sh
adb -d shell am start -n nl.fotolijst.panel/.MainActivity --ei night_test 1
```

Turn it off with `--ez night false`. If a frame ever stays dark, `adb -d shell input keyevent 224` wakes it.
Whether a touch wakes this frame's screen has not been checked; the alarm and the light do.

## Change the layout

`assets/panel.json` lists the pages and entity ids. Edit it and rebuild, or push a file that overrides it
without rebuilding:

```sh
adb -d push my-panel.json /data/local/tmp/panel.json
adb -d shell 'cp /data/local/tmp/panel.json /data/data/nl.fotolijst.panel/files/panel.json'
```

(root ADB; make the file readable by the app: `chown` it to the app's uid, see `ls -ld /data/data/nl.fotolijst.panel`.)
Page types: `today`, `climate`, `lights`, `cards`. `"theme": "light"` switches to a light palette.

## Test without a token

```sh
MOCK_EXTRA=650 python3 panel/tools/mock_ha.py     # listens on :8124, token "testtoken"
adb -d shell am start -n nl.fotolijst.panel/.MainActivity --es ha_url http://<your-mac-ip>:8124 --es ha_token testtoken
```

The mock serves the entities from `panel.json`, accepts light and climate calls, and moves the P1
power every two seconds. Remove the test settings afterwards with `adb -d shell pm clear nl.fotolijst.panel`.

## Measured on the PF1007L (mock with 684 entities)

| | Home HQ + Home Assistant page | Panel |
| --- | --- | --- |
| Start to usable | 13 to 15 s | about 0.7 s, data right after |
| Tab switch | 0.5 to 2 s, then 2 to 3 s of busy main thread | instant |
| Memory (PSS) | about 118 MB | about 16 MB |
| Frame time while tapping | not measured | 90th percentile 34 ms, 99th 57 ms |

## Limits

- It talks plain `http://` or `https://` with the system trust store. This Android 6 build lacks the
  Let's Encrypt root, so use a local `http://` address or add the root as described in the Uhale guide.
- No entity picker or settings screen: the layout is the JSON file.
- The calendar shows the next 7 days; an event is listed on each day it covers.
- If Home Assistant is unreachable the panel keeps showing the last values and retries every few seconds;
  the status dot turns red.
