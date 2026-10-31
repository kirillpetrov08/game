#!/bin/bash
# Скриншоты для карточки RuStore: локаль ru-RU, 1080x1920, без системных панелей.
# Запускается из CI (.github/workflows/screens-rustore.yml) на эмуляторе.
PKG=com.kirillpetrov.orbita
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=store/rustore/screenshots
mkdir -p shots "$OUT"
shot() { adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; }

# --- Русская локаль: без перезагрузки ресурсы не подхватятся ---
adb root > /dev/null 2>&1 || true
sleep 3
adb wait-for-device
adb shell "setprop persist.sys.locale ru-RU"
adb shell "setprop persist.sys.language ru"
adb shell "setprop persist.sys.country RU"
adb reboot
adb wait-for-device
for i in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
sleep 15

# --- Экран как у типичного телефона: 1080x1920, 420 dpi, без системных панелей ---
adb shell wm size 1080x1920
adb shell wm density 420
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put global policy_control immersive.full=*
adb shell settings put global heads_up_notifications_enabled 0
adb shell svc wifi disable || true
adb shell svc data disable || true

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
GOTIT=$(coords GOTIT)
echo "coords: PLAY=$PLAY GIFT=$GIFT GEAR=$GEAR PAUSE=$PAUSE GOTIT=$GOTIT" | tee shots/coords.txt

# «Как играть» — первый запуск игры
adb logcat -c
tapxy $PLAY; wait_state HOWTO 20; sleep 2; shot 04_howto
adb logcat -c
tapxy $GOTIT; wait_state PLAY 20; sleep 5; shot 05_play

# Игровые сцены (каждая — в своём запуске игры)
scene gate PLAY 15 02_gate
scene ring PLAY 15 03_ring
scene meteors PLAY 14 06_meteors
scene powers PLAY 14 07_powers

# Магазин комет и экран проигрыша
scene skins SKINS 4 08_comets
scene over OVER 5 09_over
scene settings SETTINGS 4 10_settings

adb logcat -d > shots/logcat_full.txt
grep -iE "FATAL|AndroidRuntime" shots/logcat_full.txt | head -40 > shots/logcat_key.txt

# В репозиторий кладём только игровые экраны для карточки магазина
for f in shots/*.png; do
  base=$(basename "$f")
  cp "$f" "$OUT/$base"
done
echo "--- screenshots ready ---"
ls -l "$OUT"
