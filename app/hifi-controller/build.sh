#!/bin/sh
# Offline APK build: aapt2 + javac + d8 + apksigner. No Gradle, no network.
# Stage 1 artifact: an ORDINARY app. Not privileged, not platform-signed.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.0.0
JAR=${ANDROID_API_JAR:-$SDK/platforms/android-29/android.jar}
[ -f "$JAR" ] || { echo "API 29 android.jar required (ANDROID_API_JAR may specify its path)" >&2; exit 1; }
JDK=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
mkdir -p "$HERE/build"
OUT=$(mktemp -d "$HERE/build/run.XXXXXX")
KS=$HERE/.debug.keystore   # 必须在 build/ 之外：rm -rf $OUT 会连钥匙一起删，导致每次换签名
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 29 \
  "$OUT/res.zip"

echo "[3/6] javac"
find "$HERE/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac" -source 8 -target 8 -nowarn \
  -bootclasspath "$JAR" -classpath "$JAR:$BT/core-lambda-stubs.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --min-api 29 --output "$OUT" --lib "$JAR" @"$OUT/classes.txt"

echo "[5/6] package + align"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -u unsigned.apk classes.dex)
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
# Throwaway debug key. NOT a platform key, NOT the MoKee release key. This APK is
# installed with `adb install` as an ordinary app; its signature grants it nothing.
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -v -keystore "$KS" \
    -storepass android -keypass android -alias leohifidebug \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=Leo HiFi Stage1 Debug, OU=throwaway, O=none, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias leohifidebug \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --min-sdk-version 29 --out "$OUT/leo-hifi-daily-candidate.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 29 -v "$OUT/leo-hifi-daily-candidate.apk"

echo
echo "APK: $OUT/leo-hifi-daily-candidate.apk"
ls -l "$OUT/leo-hifi-daily-candidate.apk"
