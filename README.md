# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with rotating nature wallpapers. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>`.

## Windows, and Google TV with Wireless debugging (Chromecast)

Newer Google TV builds (Chromecast with Google TV on Android 14) have no ADB on port 5555, only
**Wireless debugging** with a pairing code, which `adb-shell` can't do. Use Google's `adb` instead
(unzip platform-tools into `tools/platform-tools/`), and run the scripts from Git Bash:

```bash
tools/platform-tools/adb.exe pair <tv-ip>:<pairing port> <code>   # from the TV's pairing screen
tools/platform-tools/adb.exe mdns services                               # shows the connect port
export ADB="$PWD/tools/platform-tools/adb.exe -s <tv-ip>:<connect port>"
JAVA_HOME=/c/path/to/jdk ANDROID_HOME="$PWD/tools/android-sdk" launcher/build-and-install.sh
tools/restore-from-backup.sh            # undo: back to the state saved in backup/LATEST
```

The SDK needs only `build-tools;34.0.0` and `platforms;android-34`. Turn off **Verify apps over USB**
in Developer options or the install fails with `INSTALL_FAILED_VERIFICATION_FAILURE`.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen and screensaver. ~6 MB including the wallpapers; plain Android, no background services; network only for the weather |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, grid columns |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Clock, greeting and date top-left, with today's weather under them (now, high / low, an icon for the
  forecast; [Open-Meteo](https://open-meteo.com), no key, location set in `config.xml`). Frosted pills top-right for **Inputs** (HDMI/AV/Live TV
  dropdown; the remote's Menu key opens it too), **Wi-Fi** (shows the network name) and **Settings**.
- A dock of your favourite apps. The focused app's name appears above it over a glow coloured from
  its banner. Press Down for every other app in a grid.
- 15 nature photos crossfade every 10 minutes, with a scrim so text stays readable. Swap them in
  `launcher/assets/wallpapers/` and list them in `launcher/wallpapers.txt` (1920×1080 JPEGs, credits
  in `launcher/WALLPAPER-CREDITS.md`).
- **Home Assistant room temperatures** (optional) under the weather, one room per line, e.g. `🛋️ Living 21.5°`.
  Set `ha_url` and `ha_sensors` in `config.xml`, and put a long-lived access token (HA profile → Security)
  in `launcher/ha-token.txt`. That file is git-ignored and baked into the APK at build time. Plain http is
  only allowed to the host in `res/xml/network_security_config.xml` (`homeassistant.local` by default).
- A matching **screensaver**: the same photo as the home screen (both pick it from the clock, so they
  change together), with a large clock, the date and the weather.
- **Remote app buttons** (optional): Google TV sends the remote's YouTube/Netflix buttons to its own
  launcher, so they go dead once it's disabled. The launcher's small accessibility service opens the
  apps set in `remote_buttons` instead. Turn it on in Settings → Accessibility → TV Home remote buttons.
- Deterministic D-pad focus, remembers the last tile, picks up newly installed apps on return.

Change the dock: edit `config.xml`, then run `launcher/build-and-install.sh`.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Wi-Fi pill said "Offline"** with location services off: Android hides the Wi-Fi network from
  apps then. Online status now comes from ConnectivityManager; the network name shows only with location on.
- **d8 (build-tools 34) crashes on JDK 21 anonymous classes** (nameless `MethodParameters` entry). The
  launcher uses small named classes instead, so JDK 17 and 21 both work.
- **aapt2 on Windows** stored assets as `assets/wallpapers\01.jpg`, which Android can't find. Assets
  are added with `jar` instead; only files listed in `launcher/wallpapers.txt` are packaged.
- **The remote's YouTube button went dead**: Google TV's global-key table sends it (`KEYCODE_BUTTON_3`)
  only to the stock launcher. Netflix (`BUTTON_4`) goes to the Chromecast service. See *Remote app buttons*.
- **Button Mapper ignores keys injected over ADB**, and its free version allows one custom button.
- **Shell can't start a screensaver** (`cmd dreams` needs root). To test, set `screen_off_timeout` to
  15000 for a moment, then put it back.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11), Chromecast with Google TV HD (Android 14). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
