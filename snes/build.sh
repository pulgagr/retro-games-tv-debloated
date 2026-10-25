#!/bin/sh
# Builds Retro SNES without Gradle: ndk-build for the emulator, then the SDK tools for the APK.
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
NDK="$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -1)"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"

# Native emulator (incremental: only changed C files recompile)
(cd app && "$NDK/ndk-build" -s -j8 NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk \
  NDK_APPLICATION_MK=jni/Application.mk NDK_OUT=../build/obj NDK_LIBS_OUT=../build/lib 2>&1 | grep -v "fcntl" || true)
[ -f build/lib/armeabi-v7a/libsnes.so ] || { echo "native build failed"; exit 1; }

mkdir -p app/assets/roms   # ROMs are not in git, so the folder may not exist yet
rm -rf build/apk && mkdir -p build/apk/gen build/apk/classes build/apk/dex build/apk/pkg/lib
"$BT/aapt2" compile --dir app/res -o build/apk/res.zip
"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml -A app/assets \
  --java build/apk/gen -o build/apk/unsigned.apk build/apk/res.zip
javac -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -d build/apk/classes \
  $(find app/src build/apk/gen -name '*.java')
jar cf build/apk/classes.jar -C build/apk/classes .
"$BT/d8" --release --lib "$JAR" --min-api 22 --output build/apk/dex build/apk/classes.jar
cp -R build/lib/armeabi-v7a build/apk/pkg/lib/
(cd build/apk/dex && zip -q ../unsigned.apk classes.dex)
(cd build/apk/pkg && zip -q -r ../unsigned.apk lib)
"$BT/zipalign" -f -p 4 build/apk/unsigned.apk build/apk/aligned.apk

[ -f retrosnes.keystore ] || keytool -genkeypair -keystore retrosnes.keystore -storepass android \
  -keypass android -alias retrosnes -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Retro SNES" >/dev/null
"$BT/apksigner" sign --ks retrosnes.keystore --ks-pass pass:android --out RetroSNES.apk build/apk/aligned.apk
echo "Built $(pwd)/RetroSNES.apk ($(du -k RetroSNES.apk | cut -f1) KB)"
