#!/bin/bash
# Прогон игры в эмуляторе: скриншоты экранов + лог. Запускается из CI.
PKG=com.kirillpetrov.orbita
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; }
tap()  { adb shell input tap "$1" "$2"; }

adb install -r "$APK"
adb logcat -c
adb shell wm size > shots/info.txt
adb shell am start -n $PKG/.MainActivity
sleep 3;  shot 01_splash
sleep 5;  shot 02_menu

# экран комет
tap 213 1910; sleep 1.5; shot 03_skins
tap 540 2000; sleep 1.2; shot 04_menu_again

# игра
tap 540 1745; sleep 1.5; shot 05_play_start
for i in 1 2 3 4 5 6; do
  tap 200 1100; sleep 0.45
  tap 880 1100; sleep 0.45
done
shot 06_play_mid
sleep 4; shot 07_play_late

# пауза
tap 983 140; sleep 1; shot 08_pause
tap 540 1130; sleep 3; shot 09_resume_countdown
# даём погибнуть
sleep 25; shot 10_gameover_or_play
sleep 10; shot 11_gameover

adb logcat -d > shots/logcat_full.txt
grep -iE "FATAL|AndroidRuntime|Yandex Ads|YandexAds|Exception" shots/logcat_full.txt | head -80 > shots/logcat_key.txt
adb shell dumpsys gfxinfo $PKG | head -40 > shots/gfxinfo.txt
