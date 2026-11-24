#!/bin/bash
# Installs the APK on the emulator, taps "Download voice models" and reports what happens (logcat).
set -u
PKG=org.annay.topshe.v2
APK=$(ls apk/*.apk | head -1)
echo "APK: $APK"
adb shell getprop ro.product.cpu.abilist
adb install -r -g "$APK" || { echo "RESULT: INSTALL FAILED"; exit 1; }
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
echo "screen ${W}x${H}"
X=$((W / 2)); Y1=$((H * 3 / 4)); Y2=$((H / 4))
adb logcat -c
adb shell am start -n "$PKG/org.annay.topshe.MainActivity"
sleep 10
adb exec-out screencap -p > screen_1_start.png
adb shell input swipe $X $Y1 $X $Y2 400; sleep 2
adb exec-out screencap -p > screen_2_scrolled.png
adb shell input swipe $X $Y2 $X $Y1 400; sleep 2

# The slow emulator sometimes shows "Pixel Launcher isn't responding": press Wait
dismiss_anr() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  if grep -q "isn't responding" ui.xml; then
    W8=$(python3 .github/scripts/find_node.py ui.xml "Wait")
    if [ -n "$W8" ]; then adb shell input tap $W8; else adb shell input keyevent KEYCODE_BACK; fi
    echo "(dismissed a system 'not responding' dialog)"; sleep 2
    adb shell am start -n "$PKG/org.annay.topshe.MainActivity" >/dev/null; sleep 3
  fi
}

find_and_tap() {
  for i in $(seq 1 10); do
    dismiss_anr
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
    B=$(python3 .github/scripts/find_node.py ui.xml "$1")
    if [ -n "$B" ]; then adb shell input tap $B; echo "tapped '$1' at $B"; return 0; fi
    adb shell input swipe $X $Y1 $X $((H / 2)) 300
    sleep 1
  done
  echo "couldn't find '$1' on screen"; return 1
}

find_and_tap "2. Download voice models (about 330 MB, use Wi-Fi)" || { cat ui.xml | head -c 3000; echo "RESULT: BUTTON NOT FOUND"; exit 1; }
sleep 4
echo "Leaving the app and turning the screen off during the download"
adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_SLEEP
for i in $(seq 1 150); do
  sleep 8
  adb logcat -d -s Topshe:I | tail -1
  if adb logcat -d -s Topshe:I | grep -q "Voice models ready"; then echo "RESULT: SUCCESS"; break; fi
  if adb logcat -d | grep -q "Download failed\|Voice model download failed\|FATAL EXCEPTION"; then echo "RESULT: FAILED"; break; fi
done
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard 2>/dev/null; adb shell input swipe $X $Y1 $X $Y2 300
adb shell am start -n "$PKG/org.annay.topshe.MainActivity"; sleep 4
adb exec-out screencap -p > screen_3_after.png
echo "----- full log -----"
adb logcat -d -s Topshe:I AndroidRuntime:E System.err:W | tail -120
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
echo "----- on-screen text -----"
python3 .github/scripts/find_node.py ui.xml --all | head -40

echo "----- chat screen -----"
find_and_tap "Chat" && sleep 2
adb exec-out screencap -p > screen_4_chat.png
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
E=$(python3 .github/scripts/find_node.py ui.xml --class android.widget.EditText)
if [ -n "$E" ]; then
  adb shell input tap $E; sleep 1
  adb shell input text "hi"
  find_and_tap "↑"
  sleep 3
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  E=$(python3 .github/scripts/find_node.py ui.xml --class android.widget.EditText)   # moved up by the keyboard
  adb shell input tap $E; sleep 1
  adb shell input text "what%scan%syou%sdo"
  find_and_tap "↑"
  sleep 3
  adb exec-out screencap -p > screen_5_chat_reply.png
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  TXT=$(python3 .github/scripts/find_node.py ui.xml --all)
  echo "$TXT" | grep -q "Hey Annay! What should I do" && echo "CHAT RESULT: HI ANSWERED INSTANTLY" || echo "CHAT RESULT: HI NOT ANSWERED"
  echo "$TXT" | grep -q "I can play music" && echo "CHAT RESULT: HELP ANSWERED INSTANTLY" || echo "CHAT RESULT: HELP NOT ANSWERED"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  E=$(python3 .github/scripts/find_node.py ui.xml --class android.widget.EditText)
  adb shell input tap $E; sleep 1
  adb shell input text "what%sis%sthe%stime"
  find_and_tap "↑"
  sleep 3
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  python3 .github/scripts/find_node.py ui.xml --all | grep -q "^It's [0-9]" && echo "CHAT RESULT: TIME FROM CLOCK" || echo "CHAT RESULT: TIME NOT ANSWERED"
  find_and_tap "Play music"
  sleep 3
  adb exec-out screencap -p > screen_6_play_chip.png
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  python3 .github/scripts/find_node.py ui.xml --all | tail -8
  python3 .github/scripts/find_node.py ui.xml --all | grep -q "thinking" && echo "CHAT RESULT: PLAY WAITED FOR BRAIN" || echo "CHAT RESULT: PLAY HANDLED INSTANTLY"
else
  echo "CHAT RESULT: NO INPUT BOX"
fi
adb logcat -d -s AndroidRuntime:E | tail -30
