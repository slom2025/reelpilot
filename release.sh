#!/bin/bash
# Выпуск новой версии: 1) подними version-code/version-name в build.sh  2) ./release.sh "что нового"
set -e
cd "$(dirname "$0")"
./build.sh
VC=$(grep -o 'version-code [0-9]*' build.sh | grep -o '[0-9]*')
VN=$(grep -o 'version-name [0-9.]*' build.sh | awk '{print $2}')
mkdir -p release
cp build/ReelPilot.apk release/ReelPilot.apk
python3 -c "import json,sys; json.dump({'versionCode':int(sys.argv[1]),'versionName':sys.argv[2],'apk':'ReelPilot.apk','notes':sys.argv[3]}, open('release/version.json','w'), ensure_ascii=False, indent=1)" "$VC" "$VN" "${1:-Обновление}"
git add -A
git commit -m "Версия $VN"
git push
