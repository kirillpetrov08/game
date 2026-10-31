#!/bin/bash
# Прогон игры в эмуляторе: скриншоты экранов и сцен + лог. Запускается из CI.
# Скриншоты делаются в 1080x1920 с русским интерфейсом — как нужно для карточки RuStore.
PKG=com.kirillpetrov.orbita
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; }

# --- Русская локаль (без перезагрузки ресурсы не подхватятся) ---
adb root > /dev/null 2>&1 || true
sleep 3
adb wait-for-device
adb shell "setprop persist.sys.locale ru-RU" || true
adb shell "setprop persist.sys.language ru" || true
adb shell "setprop persist.sys.country RU" || true
adb reboot || true
adb wait-for-device
for i in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
sleep 15
echo "locale: $(adb shell getprop persist.sys.locale | tr -d '\r')"

# --- Экран типичного телефона, без системных панелей ---
adb shell wm size 1080x1920
adb shell wm density 420
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put global policy_control immersive.full=*
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
scene() {  # scene NAME STATE WAIT_SECONDS SHOT
  adb logcat -c
  adb shell am start -n $PKG/.MainActivity --es scene "$1"
  wait_state "$2" 20
  sleep "$3"; shot "$4"
}

sleep 3
wait_state MENU 40
sleep 8
shot 01_menu
PLAY=$(coords PLAY); GIFT=$(coords GIFT); GEAR=$(coords GEAR); PAUSE=$(coords PAUSE)
RESUME=$(coords RESUME); GOTIT=$(coords GOTIT); SBACK=$(coords SBACK); MENU=$(coords MENU)
echo "coords: PLAY=$PLAY GIFT=$GIFT GEAR=$GEAR PAUSE=$PAUSE RESUME=$RESUME GOTIT=$GOTIT SBACK=$SBACK" | tee shots/coords.txt

tapxy $GIFT; sleep 3; shot 02_gift_claimed

adb logcat -c
tapxy $GEAR; wait_state SETTINGS 15; sleep 2; shot 03_settings
adb logcat -c
tapxy $SBACK; wait_state MENU 15; sleep 1

# первый запуск игры показывает «как играть»
adb logcat -c
tapxy $PLAY; wait_state HOWTO 15; sleep 2; shot 04_howto
adb logcat -c
tapxy $GOTIT; wait_state PLAY 15; sleep 4; shot 05_play_hints

adb logcat -c
tapxy $PAUSE; wait_state PAUSE 15; sleep 2; shot 06_pause

scene skins SKINS 4 07_skins
scene gate PLAY 14 08_gate
scene ring PLAY 14 09_ring
scene meteors PLAY 14 10_meteors
scene powers PLAY 14 11_powers
scene over OVER 5 12_over

adb logcat -d > shots/logcat_full.txt
grep -iE "FATAL|AndroidRuntime" shots/logcat_full.txt | head -40 > shots/logcat_key.txt
adb shell dumpsys gfxinfo $PKG | head -12 > shots/gfxinfo.txt
