#!/bin/bash
# STADIUM LIVE 실동작 시연 녹화.
# Pleos 에뮬레이터 화면과 소리를 에뮬레이터 자체 녹화기로 담고, 기어는 Virtual Console HTTP API로 바꾼다.
# 준비: 에뮬레이터 부팅, 앱 설치, `adb forward tcp:8088 tcp:8088`, 브라우저에서 http://localhost:8088 열어 둠.
# 실행: tools/record_demo.sh demo/raw.webm
set -euo pipefail
OUT=${1:-demo/raw.webm}
A=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
PKG=kr.stadiumlive.vehicle/kr.stadiumlive.MainActivity
VC=http://localhost:8088

# Virtual Console은 브라우저 탭이 열려 있어야(heartbeat) 요청을 받는다. 실패하면 바로 멈춘다.
gear() { local r; r=$(curl -s -X PUT "$VC/vehicle/gear?gear=$1"); case "$r" in *error*) echo "gear $1 failed: $r" >&2; $A emu screenrecord stop >/dev/null 2>&1 || true; exit 1;; esac; }
speed() { curl -s -X PUT "$VC/vehicle/controls?speed=$1&accelerator=0&brake=0&steering=0" >/dev/null; }
log() { echo "[$(date +%T)] $*"; }

# 화면에 보이는 글자로 좌표를 찾아 누른다(n번째 일치).
tap_text() {
  local text=$1 nth=${2:-1} dx=${3:-} dy=${4:-}
  $A shell uiautomator dump /sdcard/ui.xml >/dev/null
  local xy
  xy=$($A shell cat /sdcard/ui.xml | python3 -I "$(dirname "$0")/find_text.py" "$text" "$nth" $dx $dy)
  $A shell input tap $xy
  log "tap '$text' #$nth at $xy"
}

# 0. 준비: 정차, 앱을 설정 화면으로.
gear Park; speed 0
$A shell am start -S -W -n $PKG >/dev/null
sleep 3

log "record start"
$A emu screenrecord start --time-limit 170 "$PWD/$OUT" >/dev/null
sleep 1.5

# 01 출발 전 설정(정차): 응원 팀 확인, 연출 강도 '강'.
sleep 3
tap_text "강"
sleep 2.5
tap_text "이 경기 듣기"
log "playback start"
sleep 3

# 02 주행 중: 해설 중심 화면으로 전환, 내비 안내(감쇠)와 전화(일시정지).
gear Drive; speed 62
log "drive"
sleep 5
$A shell am start -n $PKG --es sim nav >/dev/null
log "nav prompt"
sleep 5
$A shell am start -n $PKG --es sim call >/dev/null
log "incoming call"
sleep 9

# 03 정차 후 몰입: 긴장 → 타격 → 끝내기 → 응원가.
speed 0; gear Park
log "park"
# 남은 중계가 끝나면 평가 화면으로 넘어간다(재생 위치 약 17초 → 67초).
sleep 52

# 04 종료 후 평가.
sleep 2
tap_text "★" 5; sleep 0.6
tap_text "★" 9; sleep 0.6
tap_text "★" 15; sleep 0.8
tap_text "평가 저장하기"
sleep 3.5

$A emu screenrecord stop >/dev/null || true
log "record stop"
sleep 2
ls -la "$OUT"
