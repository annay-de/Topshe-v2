#!/bin/bash
# Installs the APK on the emulator, taps "Download voice models" and reports what happens (logcat).
set -u
PKG=org.annay.topshe.v2
APK=$(ls apk/*.apk | head -1)
echo "APK: $APK"
adb shell getprop ro.product.cpu.abilist
adb install -r -g "$APK" || { echo "RESULT: INSTALL FAILED"; exit 1; }
adb logcat -c
adb shell am start -n "$PKG/org.annay.topshe.MainActivity"
sleep 10
adb exec-out screencap -p > screen_1_start.png
adb shell input swipe 500 1500 500 600 400; sleep 2
adb exec-out screencap -p > screen_2_scrolled.png
adb shell input swipe 500 600 500 1500 400; sleep 2

find_and_tap() {
  for i in $(seq 1 10); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
    B=$(python3 .github/scripts/find_node.py ui.xml "$1")
    if [ -n "$B" ]; then adb shell input tap $B; echo "tapped '$1' at $B"; return 0; fi
    adb shell input swipe 500 1400 500 800 300
    sleep 1
  done
  echo "couldn't find '$1' on screen"; return 1
}

find_and_tap "Download voice models" || { cat ui.xml | head -c 3000; echo "RESULT: BUTTON NOT FOUND"; exit 1; }
for i in $(seq 1 150); do
  sleep 8
  adb logcat -d -s Topshe:I | tail -1
  if adb logcat -d -s Topshe:I | grep -q "Voice models ready"; then echo "RESULT: SUCCESS"; break; fi
  if adb logcat -d | grep -q "Voice model download failed\|FATAL EXCEPTION"; then echo "RESULT: FAILED"; break; fi
done
adb exec-out screencap -p > screen_3_after.png
echo "----- full log -----"
adb logcat -d -s Topshe:I AndroidRuntime:E System.err:W | tail -120
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
echo "----- on-screen text -----"
python3 .github/scripts/find_node.py ui.xml --all | head -40
