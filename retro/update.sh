#!/bin/sh
# Adds new ROMs from the NAS to Retro Games: refreshes the game list and covers, rebuilds the APK
# and installs it on the TV. Saves and downloaded games on the TV are kept.
#
# Usage: ./update.sh      (settings come from local.conf; see local.conf.example)
set -e
cd "$(dirname "$0")"
[ -f local.conf ] || { echo "Create local.conf first: cp local.conf.example local.conf, then edit it."; exit 1; }
. ./local.conf
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"

# 1. Make sure the ROM share is mounted on this Mac
if [ ! -d "$ROMS" ]; then
  echo "Mounting the NAS share..."
  osascript -e "mount volume \"$NAS_URL\"" >/dev/null 2>&1 || true
  for i in 1 2 3 4 5; do [ -d "$ROMS" ] && break; sleep 1; done
  [ -d "$ROMS" ] || { echo "Can't find $ROMS. Open $NAS_URL in Finder and try again."; exit 1; }
fi

# 2. Game list and covers
echo "Scanning ROMs..."
python3 tools/catalog.py "$ROMS" "$NAS_HOST" "$NAS_SHARE"

# 3. Build
echo "Building..."
./build.sh 2>&1 | grep -vE "^Note:" | tail -3

# 4. Install on the TV
echo "Installing on the TV ($TV)..."
"$ADB" connect "$TV" >/dev/null
"$ADB" -s "$TV" install -r RetroGames.apk | tail -1
echo "Done. Open Retro Games on the TV."
