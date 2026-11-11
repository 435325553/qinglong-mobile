#!/usr/bin/env bash
# 无 Gradle 打包：aapt2 + javac + d8 + zipalign + apksigner
# 依赖：JDK 11、Android build-tools 34.0.0、platform android-32 的 android.jar
set -euo pipefail

SDK_DIR="${ANDROID_SDK_HOME:-$HOME/android-sdk}"
BT_DIR="${BUILD_TOOLS_DIR:-$SDK_DIR/build-tools/34.0.0}"
PLATFORM_JAR="${ANDROID_JAR:-$SDK_DIR/platforms/android-32/android.jar}"

AAPT2="$BT_DIR/aapt2"
D8="$BT_DIR/d8"
ZIPALIGN="$BT_DIR/zipalign"
APKSIGNER="$BT_DIR/apksigner"

for t in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$PLATFORM_JAR"; do
  [ -e "$t" ] || { echo "缺少构建工具：$t" >&2; exit 1; }
done

VERSION_CODE="${VERSION_CODE:-3}"
VERSION_NAME="${VERSION_NAME:-1.2}"
KEYSTORE="${KEYSTORE:-$PWD/debug.jks}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-debug}"
OUT_DIR="${OUT_DIR:-build}"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/gen" "$OUT_DIR/classes" "$OUT_DIR/dex"

echo "== aapt2 compile =="
"$AAPT2" compile --dir res -o "$OUT_DIR/res.zip"

echo "== aapt2 link =="
"$AAPT2" link -o "$OUT_DIR/base.apk" -I "$PLATFORM_JAR" \
  --manifest AndroidManifest.xml --java "$OUT_DIR/gen" \
  --min-sdk-version 21 --target-sdk-version 32 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT_DIR/res.zip"

echo "== javac =="
javac -source 8 -target 8 -bootclasspath "$PLATFORM_JAR" -encoding UTF-8 \
  -d "$OUT_DIR/classes" $(find src "$OUT_DIR/gen" -name "*.java")

echo "== d8 =="
"$D8" --lib "$PLATFORM_JAR" --min-api 21 --release \
  --output "$OUT_DIR/dex" $(find "$OUT_DIR/classes" -name "*.class")

echo "== package =="
cp "$OUT_DIR/base.apk" "$OUT_DIR/unaligned.apk"
( cd "$OUT_DIR/dex" && zip -q -X ../unaligned.apk classes.dex )

echo "== zipalign =="
"$ZIPALIGN" -f 4 "$OUT_DIR/unaligned.apk" "$OUT_DIR/aligned.apk"

echo "== sign =="
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
    -dname "CN=Qinglong Assistant, O=qinglong-mobile, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" \
  --key-pass "pass:$KEYSTORE_PASS" --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT_DIR/qinglong-assistant.apk" "$OUT_DIR/aligned.apk"

echo "== done =="
ls -lh "$OUT_DIR/qinglong-assistant.apk"
