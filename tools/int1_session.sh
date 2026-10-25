#!/usr/bin/env bash
# D: 첫 통합 실측 한 시행의 측정 구간을 잡는다 (docs/FIRST_INTEGRATION_CHECKLIST.md 2절).
# 재현 앱 기록(PopupTest 태그: "시나리오:리소스ID")만 저장한다. 화면 원문은 저장하지 않는다.
#
#   bash tools/int1_session.sh P01 1          # int1_logs/int1_P01_1.log
#   cat int1_logs/int1_*.log > popuptest.log  # 모든 시행이 끝난 뒤 집계에 넣음
set -euo pipefail

usage() { echo "사용법: bash $0 <시나리오 ID(P01, N08 등)> <시행 번호> [출력 폴더]" >&2; exit 2; }
[ $# -ge 2 ] || usage
scenario=$1
trial=$2
out=${3:-int1_logs}
[[ $scenario =~ ^[PN][0-9][0-9]$ ]] || usage
[[ $trial =~ ^[0-9]+$ ]] || usage
command -v adb >/dev/null || { echo "adb를 찾을 수 없습니다" >&2; exit 2; }

mkdir -p "$out"
log="$out/int1_${scenario}_${trial}.log"
if [ -e "$log" ]; then
    echo "이미 있음: $log (버린 시행이면 지우고, 아니면 다른 시행 번호를 쓰세요)" >&2
    exit 2
fi

adb logcat -c
adb logcat -s PopupTest > "$log" &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT
sleep 1
adb shell am start -n kr.closeforyou.popuptest/.PopupScenarioActivity --es scenario "$scenario" > /dev/null

echo "측정 중: $scenario 시행 $trial"
echo "재현 앱 화면은 만지지 마세요. 닫아줄게 선택 창 확인이 끝나면 Enter를 누르세요."
read -r _
kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null || true
trap - EXIT

lines=$(grep -cE 'PopupTest[^:]*: *[A-Z][0-9][0-9]:' "$log" || true)
echo "저장: $log (재현 앱 버튼 기록 ${lines}줄)"
if [ "$lines" -ne 0 ]; then
    echo "주의: 측정 구간에 재현 앱 버튼 기록이 있습니다. 직접 누른 실수면 이 파일을 지우고 다시, 아니면 실패로 기록하세요." >&2
fi
echo "이제 팝업을 직접 닫아도 됩니다(측정 구간 밖)."
