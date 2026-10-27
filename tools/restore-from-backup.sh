#!/usr/bin/env bash
# Put the TV back the way it was when the backup was taken: re-enable every package that was enabled then
# and is disabled now (this includes the stock launcher and Google TV's setup app), make the original home
# app the default again, and remove the tvhome launcher.
#
#   ADB="tools/platform-tools/adb.exe -s <tv-ip>:<connect port>" tools/restore-from-backup.sh
#   ... tools/restore-from-backup.sh backup/<timestamp>          a specific backup (default: backup/LATEST)
#   ... tools/restore-from-backup.sh --keep-launcher             re-enable everything but keep tvhome installed
#
# Wireless debugging changes port when it's toggled; `adb mdns services` shows the current one.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-adb}"
KEEP=0 DIR=""
for a in "$@"; do
  case "$a" in
    --keep-launcher) KEEP=1 ;;
    *) DIR="$a" ;;
  esac
done
DIR="${DIR:-$(cat backup/LATEST)}"
[ -f "$DIR/packages-enabled.txt" ] || { echo "no backup in $DIR"; exit 1; }
echo "Restoring from $DIR"

now_disabled=$($ADB shell pm list packages -d | tr -d '\r' | sed 's/^package://')
# Android 14 can list a system-disabled package under both -e and -d; the backup's -d list wins.
was_disabled=$(tr -d '\r' < "$DIR/packages-disabled.txt" | sed 's/^package://')
for p in $(tr -d '\r' < "$DIR/packages-enabled.txt" | sed 's/^package://'); do
  if grep -qx "$p" <<< "$now_disabled" && ! grep -qx "$p" <<< "$was_disabled"; then
    printf 're-enable %-52s ' "$p"
    $ADB shell pm enable "$p" | tr -d '\r'  # one pm call per shell; chained ones can stop early
  fi
done

home=$(tr -d '\r' < "$DIR/home-activity.txt")
echo "home app -> $home"
$ADB shell cmd package set-home-activity --user 0 "$home"
if [ $KEEP = 0 ] && $ADB shell pm list packages com.mihir.tvhome | grep -q tvhome; then
  echo "removing tvhome launcher"
  $ADB shell pm uninstall com.mihir.tvhome
fi
$ADB shell input keyevent KEYCODE_HOME
echo "Restored. Still disabled (were disabled before the backup too):"
$ADB shell pm list packages -d | tr -d '\r' | sed 's/^package:/  /'
