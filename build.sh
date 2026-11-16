#!/bin/bash
set -e
cd "$(dirname "$0")"
FW=/usr/share/android-framework-res/framework-res.apk
BOOT=/root/sdk/android-all.jar
rm -rf build && mkdir -p build/gen build/classes
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I $FW \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 8 --version-name 1.7 \
  -F build/app.unsigned.apk
javac -nowarn -Xlint:-options --release 8 -classpath $BOOT -encoding UTF-8 -d build/classes \
  $(find build/gen src -name '*.java')
dalvik-exchange --dex --min-sdk-version=24 --output=build/classes.dex build/classes
(cd build && aapt add -f app.unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/app.unsigned.apk build/app.aligned.apk
[ -f key.jks ] || cp /mnt/project-files/reelpilot/signing-key.jks key.jks 2>/dev/null; [ -f key.jks ] || keytool -genkeypair -keystore key.jks -storepass reelpilot -keypass reelpilot -alias rp \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ReelPilot" >/dev/null 2>&1
apksigner sign --ks key.jks --ks-pass pass:reelpilot --key-pass pass:reelpilot --out build/ReelPilot.apk build/app.aligned.apk
apksigner verify --print-certs build/ReelPilot.apk | head -2
ls -la build/ReelPilot.apk
