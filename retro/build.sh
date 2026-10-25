#!/bin/sh
# Builds Retro Games without Gradle: ndk-build for the frontend and each emulator core,
# then the SDK tools for the APK. Run tools/catalog.py first to refresh the game list and art.
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
NDK="$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -1)"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
LIBS="$(pwd)/build/lib/armeabi-v7a"
mkdir -p "$LIBS"

# The NDK's make can't handle the space in this folder's path ("games apk"), so native code
# is compiled in a copy under the temp folder. rsync keeps it incremental.
NB="${TMPDIR:-/tmp}/retrogames-native"
ndk() {  # name, source folder to copy, sub-folder (inside the copy) that holds jni/
  mkdir -p "$NB/$1"
  rsync -a --delete --exclude .git --exclude obj --exclude libs "$2/" "$NB/$1/"
  (cd "$NB/$1/$3" && "$NDK/ndk-build" -s -j8 NDK_PROJECT_PATH=. APP_BUILD_SCRIPT="$(pwd)/jni/Android.mk" \
    NDK_APPLICATION_MK="$(pwd)/jni/Application.mk" APP_ABI=armeabi-v7a APP_PLATFORM=android-22 APP_CFLAGS=-w 2>&1 \
    | grep -E "error|Error" || true)
  [ -f "$NB/$1/$3/libs/armeabi-v7a/lib$4.so" ] || { echo "native build of $1 failed"; exit 1; }
  cp "$NB/$1/$3/libs/armeabi-v7a/lib$4.so" "$LIBS/lib$5.so"
}
mkdir -p build/frontend-src && rsync -a --delete app/jni/ build/frontend-src/jni/
ndk frontend build/frontend-src . frontend frontend
ndk fceumm third_party/fceumm . retro fceumm
ndk snes9x2005 third_party/snes9x2005 . retro snes9x2005
ndk genesis_plus_gx third_party/genesis_plus_gx libretro retro genesis_plus_gx
ndk gambatte third_party/gambatte libgambatte/libretro retro gambatte
ndk mame2000 third_party/mame2000 . retro mame2000

rm -rf build/apk && mkdir -p build/apk/gen build/apk/classes build/apk/dex build/apk/pkg/lib
"$BT/aapt2" compile --dir app/res -o build/apk/res.zip
"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml -A app/assets \
  --java build/apk/gen -o build/apk/unsigned.apk build/apk/res.zip
javac -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -nowarn -d build/apk/classes \
  $(find app/src build/apk/gen -name '*.java')
jar cf build/apk/classes.jar -C build/apk/classes .
"$BT/d8" --release --lib "$JAR" --min-api 22 --output build/apk/dex build/apk/classes.jar
cp -R build/lib/armeabi-v7a build/apk/pkg/lib/
(cd build/apk/dex && zip -q ../unsigned.apk classes.dex)
(cd build/apk/pkg && zip -q -r ../unsigned.apk lib)
"$BT/zipalign" -f -p 4 build/apk/unsigned.apk build/apk/aligned.apk

[ -f retro.keystore ] || keytool -genkeypair -keystore retro.keystore -storepass android \
  -keypass android -alias retro -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Retro Games" >/dev/null
"$BT/apksigner" sign --ks retro.keystore --ks-pass pass:android --out RetroGames.apk build/apk/aligned.apk
echo "Built $(pwd)/RetroGames.apk ($(du -k RetroGames.apk | cut -f1) KB)"
