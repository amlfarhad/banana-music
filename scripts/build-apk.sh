#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
VERSION="0.2.1"
MANIFEST="app/src/main/AndroidManifest.xml"
SUFFIX=""
SDK="$ROOT/.toolchain/android-sdk"
JDK_ROOT="$(find "$ROOT/.toolchain" -maxdepth 1 -type d -name 'jdk-21*' -print -quit)"
export JAVA_HOME="$JDK_ROOT/Contents/Home"
TOOLS="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
if [[ ! -x "$JAVA_HOME/bin/javac" || ! -x "$TOOLS/aapt2" || ! -f "$PLATFORM" ]]; then
  echo 'Missing local Android/JDK toolchain. See TOOLCHAIN.md.' >&2
  exit 1
fi
mkdir -p build/compiled build/classes build/generated build/dex downloads .keys
if [[ "${1:-}" == "--diagnostic" ]]; then
  python3 - <<'PY'
import xml.etree.ElementTree as E
tree=E.parse('app/src/main/AndroidManifest.xml')
tree.getroot().find('application').set('{http://schemas.android.com/apk/res/android}debuggable','true')
tree.write('build/diagnostic-manifest.xml',encoding='utf-8',xml_declaration=True)
PY
  MANIFEST="build/diagnostic-manifest.xml"
  SUFFIX="-diagnostic"
fi
OUTPUT="downloads/Banana-Music-${VERSION}${SUFFIX}.apk"
chmod 700 .keys
"$TOOLS/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$TOOLS/aapt2" link -I "$PLATFORM" --manifest "$MANIFEST" \
  --java build/generated -A app/src/main/assets --min-sdk-version 26 --target-sdk-version 35 \
  --auto-add-overlay -o build/unsigned.apk build/resources.zip
find app/src/main/java build/generated -name '*.java' -print > build/sources.list
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 -classpath "$PLATFORM" \
  -d build/classes @build/sources.list
find build/classes -name '*.class' -print > build/classes.list
"$TOOLS/d8" --release --lib "$PLATFORM" --min-api 26 --output build/dex @build/classes.list
(cd build/dex && /usr/bin/zip -q -u ../unsigned.apk classes*.dex)
"$TOOLS/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [[ ! -f .keys/aural.p12 ]]; then
  python3 - <<'PY'
import secrets, pathlib, os
p = pathlib.Path('.keys/password')
p.write_text(secrets.token_urlsafe(32))
os.chmod(p, 0o600)
PY
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore .keys/aural.p12 -alias aural \
    -storepass:file .keys/password -keypass:file .keys/password \
    -dname 'CN=Aural TV Personal Build' -keyalg RSA -keysize 2048 -validity 3650 \
    -storetype PKCS12 > build/signing.log 2>&1
  chmod 600 .keys/aural.p12
fi
"$TOOLS/apksigner" sign --ks .keys/aural.p12 --ks-key-alias aural \
  --ks-pass file:.keys/password \
  --out "$OUTPUT" build/aligned.apk
"$TOOLS/apksigner" verify --verbose "$OUTPUT" > build/signature-verification.txt
"$TOOLS/aapt2" dump badging "$OUTPUT" > build/apk-badging.txt
shasum -a 256 "$OUTPUT" > "${OUTPUT%.apk}.sha256"
echo "Built $OUTPUT"
ls -lh "$OUTPUT"
