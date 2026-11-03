#!/usr/bin/env bash
# Build the launcher APK with the bare SDK tools (no Gradle) and install it on the TV as the home screen.
#   launcher/build-and-install.sh            build + install
#   launcher/build-and-install.sh --build    build only
# Works on macOS and on Windows (Git Bash). Set JAVA_HOME / ANDROID_HOME if they aren't in the default places.
# Install goes through tools/tv.py (adb-shell), or through a real adb when ADB is set, e.g. for wireless
# debugging on Android 11+:  ADB="tools/platform-tools/adb.exe -s <tv-ip>:<connect port>" launcher/build-and-install.sh
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
# Google TV stock launcher; older Android TV sets use com.google.android.tvlauncher.
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}"
B=build

# SDK tools are plain binaries on macOS and .exe/.bat on Windows.
tool() { for t in "$BT/$1" "$BT/$1.exe" "$BT/$1.bat"; do [ -f "$t" ] && { echo "$t"; return; }; done; echo "missing $BT/$1" >&2; exit 1; }

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/assets/wallpapers"
# Only the wallpapers named in wallpapers.txt go into the APK.
grep -vE '^\s*(#|$)' wallpapers.txt | while read -r w; do cp "assets/wallpapers/$w" "$B/assets/wallpapers/"; done
"$(tool aapt2)" compile --dir res -o "$B/res.zip"
# The Home Assistant token stays out of git: ha-token.txt (git-ignored) overlays config.xml's empty ha_token.
OVERLAY=()
if [ -s ha-token.txt ]; then
  mkdir -p "$B/local/values"
  printf '<resources><string name="ha_token" translatable="false">%s</string></resources>\n' \
    "$(tr -d ' \r\n' < ha-token.txt)" > "$B/local/values/ha_token.xml"
  "$(tool aapt2)" compile --dir "$B/local" -o "$B/local.zip"
  OVERLAY=(-R "$B/local.zip")
fi
"$(tool aapt2)" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  --min-sdk-version 30 --target-sdk-version 30 "$B/res.zip" ${OVERLAY[@]+"${OVERLAY[@]}"}
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$(tool d8)" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
jar -u -M -f "$B/unsigned.apk" -C "$B/dex" classes.dex
# Assets via jar, not aapt2 -A: aapt2 on Windows writes them as assets/wallpapers\01.jpg, which Android can't find.
jar -u -M -0 -f "$B/unsigned.apk" -C "$B" assets
"$(tool zipalign)" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$(tool apksigner)" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
[ "${1:-}" = "--build" ] && exit 0

STEPS=(
  "pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION"
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity"
  "pm disable-user --user 0 $STOCK_LAUNCHER"
  "pm disable-user --user 0 com.google.android.tungsten.setupwraith"
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity"
  "am force-stop com.android.tv.settings"
  "input keyevent KEYCODE_HOME"
)
if [ -n "${ADB:-}" ]; then
  $ADB install -r "$B/tvhome.apk"
  for s in "${STEPS[@]}"; do echo "\$ $s"; $ADB shell "$s"; done  # one pm call per shell; chained ones can stop early
else
  PY=../tools/venv/bin/python; [ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
  "$PY" -I ../tools/tv.py --push-b64 "$B/tvhome.apk" /data/local/tmp/tvhome.apk
  printf '%s\n' "pm install -r /data/local/tmp/tvhome.apk" "rm -f /data/local/tmp/tvhome.apk" "${STEPS[@]}" \
    | "$PY" -I ../tools/tv.py --batch
fi
tvsh() { if [ -n "${ADB:-}" ]; then $ADB shell "$1"; else "$PY" -I ../tools/tv.py "$1"; fi; }

# Turn on the remote-button accessibility service (Settings > Accessibility > TV Home remote buttons), so the
# YouTube/Netflix buttons work without a trip to Settings. Any other enabled services (e.g. Button Mapper) are kept.
SVC=com.mihir.tvhome/com.mihir.tvhome.RemoteKeys
CUR=$(tvsh "settings get secure enabled_accessibility_services" | tr -d '\r')
if ! tr ':' '\n' <<< "$CUR" | grep -qx "$SVC"; then
  [ "$CUR" = null ] || [ -z "$CUR" ] && NEW=$SVC || NEW="$CUR:$SVC"
  tvsh "settings put secure enabled_accessibility_services '$NEW'"
fi
tvsh "settings put secure accessibility_enabled 1"
sleep 3
# Bound services spans several lines, one per service.
if tvsh "dumpsys accessibility" | sed -n '/Bound services:/,/Enabled services:/p' | grep -q "label=TV Home remote buttons"; then
  echo "Remote YouTube/Netflix buttons on."
else
  echo "!! Remote YouTube/Netflix buttons didn't start. On the TV: Settings > System > Accessibility >"
  echo "!! TV Home remote buttons, switch it off and on again (or restart the TV)."
fi
