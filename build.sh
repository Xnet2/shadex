#!/usr/bin/env bash
# Builds ShadeX.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Uses the VODOS-build kit (%LOCALAPPDATA%\VODOS-build) through its 8.3 short path, because the
# Windows user name is Hebrew and aapt2 / the JDK fail on non-ASCII paths.
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$(cygpath -m "$(cygpath -d "$LOCALAPPDATA/VODOS-build")")}"
JAVA_HOME="$(ls -d "$TOOLS"/jdk-17*)"
BT="$TOOLS/sdk/bt/android-14"
JAR="$TOOLS/sdk/pf/android-34/android.jar"
export JAVA_HOME PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"

# Work in an ASCII folder inside the kit.
W="${WORK:-$TOOLS/shadex-out}"
rm -rf "$W" && mkdir -p "$W"/{gen,classes,dex}
cp -r "$SRC/res" "$SRC/src" "$SRC/AndroidManifest.xml" "$JAR" "$W/"
cd "$W"

"$BT/aapt2.exe" compile --dir res -o res.zip
"$BT/aapt2.exe" link -I android.jar --manifest AndroidManifest.xml -R res.zip -o base.apk \
  --java gen --min-sdk-version 29 --target-sdk-version 28 \
  --version-code 21 --version-name 7.0 --auto-add-overlay

find src gen -name '*.java' > sources.txt
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar" -Xlint:-options \
  -d classes @sources.txt

find classes -name '*.class' > classes.txt
"$BT/d8.bat" --release --min-api 29 --lib "$JAR" --output dex @classes.txt

cp base.apk unsigned.apk
(cd dex && jar uf ../unsigned.apk classes.dex)
"$BT/zipalign.exe" -f -p 4 unsigned.apk aligned.apk

KS="$SRC/shadex.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass shadex -keypass shadex -alias shadex \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ShadeX"
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:shadex --ks-key-alias shadex \
  --out "$SRC/ShadeX.apk" aligned.apk
"$BT/apksigner.bat" verify --print-certs "$SRC/ShadeX.apk" | head -3
ls -la "$SRC/ShadeX.apk"
