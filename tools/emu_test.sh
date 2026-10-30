#!/bin/bash
# Прогон игры в эмуляторе: скриншоты экранов + лог. Запускается из CI.
PKG=com.kirillpetrov.orbita
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; }

adb shell wm size 540x1200
adb shell wm density 240
adb shell settings put global window_animation_scale 0
adb install -r "$APK"
adb logcat -c
adb shell am start -n $PKG/.MainActivity

wait_state() {  # wait_state NAME TRIES
  for i in $(seq 1 "$2"); do
    adb logcat -d -s OrbitaUI:D | grep -q "state:$1" && return 0
    sleep 2
  done
  echo "TIMEOUT waiting for $1"; return 1
}
coords() { adb logcat -d -s OrbitaUI:D | grep "btn:$1 " | tail -1 | awk '{print $(NF-1), $NF}'; }
tapxy() { adb shell input tap "$1" "$2"; }

sleep 2; shot 01_splash
wait_state MENU 40
sleep 6   # даём баннеру загрузиться и раскладке перестроиться
shot 02_menu
PLAY=$(coords PLAY); SKINS=$(coords SKINS); BACK=$(coords BACK); PAUSE=$(coords PAUSE)
RESUME=$(coords RESUME); AGAIN=$(coords AGAIN); MENU=$(coords MENU)
echo "coords: PLAY=$PLAY SKINS=$SKINS BACK=$BACK PAUSE=$PAUSE RESUME=$RESUME AGAIN=$AGAIN MENU=$MENU" | tee shots/coords.txt

adb logcat -c
tapxy $SKINS; wait_state SKINS 15; sleep 3; shot 03_skins
adb logcat -c
tapxy $BACK; wait_state MENU 15; sleep 2; shot 04_menu_again

adb logcat -c
tapxy $PLAY; wait_state PLAY 15; sleep 3; shot 05_play_start

# пауза сразу после старта, пока игрок жив
adb logcat -c
tapxy $PAUSE; wait_state PAUSE 15; sleep 2; shot 09_pause
adb logcat -c
tapxy $RESUME; wait_state PLAY 15; sleep 1; shot 10_countdown
sleep 8
for i in 1 2 3; do tapxy 90 700; sleep 1.2; tapxy 450 700; sleep 1.2; done
shot 06_play_mid
sleep 6; shot 07_play_late

adb logcat -c
wait_state OVER 90
sleep 4; shot 11_gameover
adb logcat -c
tapxy $MENU; wait_state MENU 15; sleep 2; shot 12_menu_after

adb logcat -d > shots/logcat_full.txt
grep -iE "FATAL|AndroidRuntime" shots/logcat_full.txt | head -40 > shots/logcat_key.txt
adb shell dumpsys gfxinfo $PKG | head -12 > shots/gfxinfo.txt
