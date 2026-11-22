#!/usr/bin/env bash
# Builds a signed, sideloadable APK without Gradle.
# Needs: JDK 8+ (javac, keytool), aapt, a dex tool (d8 or dalvik-exchange), zipalign, apksigner, android.jar (API 23+).
# Override locations with env vars if you use the official SDK, e.g.
#   ANDROID_JAR=$ANDROID_HOME/platforms/android-33/android.jar BT=$ANDROID_HOME/build-tools/33.0.2 ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
BT=${BT:-}                                    # official SDK build-tools dir (optional)
tool() { if [ -n "$BT" ] && [ -x "$BT/$1" ]; then echo "$BT/$1"; else command -v "$1"; fi; }
OUT=build; rm -rf $OUT; mkdir -p $OUT/classes

echo "[1/6] aapt: manifest + resources"
"$(tool aapt)" package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F $OUT/app.unaligned.apk

echo "[2/6] javac"
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -cp "$ANDROID_JAR" \
      -d $OUT/classes src/com/cosmos90gt/ctrl/*.java

echo "[3/6] dex"
if [ -n "$BT" ] && [ -x "$BT/d8" ]; then
  "$BT/d8" --min-api 23 --lib "$ANDROID_JAR" --output $OUT $(find $OUT/classes -name '*.class')
else
  dalvik-exchange --dex --min-sdk-version=23 --output=$OUT/classes.dex $OUT/classes
fi
( cd $OUT && "$(tool aapt)" add app.unaligned.apk classes.dex >/dev/null )

echo "[4/6] zipalign"
"$(tool zipalign)" -f 4 $OUT/app.unaligned.apk $OUT/app.aligned.apk

echo "[5/6] sign (keystore tools/debug.keystore; created once, keep it so updates install over old versions)"
# Override with KEYSTORE_PASS / KEYSTORE_ALIAS env vars; defaults suit a throwaway local build.
KEYSTORE_PASS=${KEYSTORE_PASS:-android}
KEYSTORE_ALIAS=${KEYSTORE_ALIAS:-cosmos}
mkdir -p tools
[ -f tools/debug.keystore ] || keytool -genkeypair -keystore tools/debug.keystore -alias "$KEYSTORE_ALIAS" -keyalg RSA -keysize 2048 \
      -validity 10000 -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" -dname "CN=Cosmos90GT" >/dev/null 2>&1
"$(tool apksigner)" sign --ks tools/debug.keystore --ks-key-alias "$KEYSTORE_ALIAS" \
      --ks-pass "pass:$KEYSTORE_PASS" --key-pass "pass:$KEYSTORE_PASS" \
      --out $OUT/cosmos90gt.apk $OUT/app.aligned.apk

echo "[6/6] verify"
"$(tool apksigner)" verify -v $OUT/cosmos90gt.apk | head -5
ls -l $OUT/cosmos90gt.apk
