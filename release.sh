#!/bin/bash
# Использование: ./release.sh "что нового"  (после правки версии в build.sh)
set -e
cd "$(dirname "$0")"
./build.sh
VC=$(grep -o 'version-code [0-9]*' build.sh | grep -o '[0-9]*')
REPO=$(grep -o 'REPO = "[^"]*"' src/app/reelpilot/Updater.java | cut -d'"' -f2)
gh release create "v$VC" build/ReelPilot.apk --repo "$REPO" --title "Версия $VC" --notes "${1:-Обновление}"
