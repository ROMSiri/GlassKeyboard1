#!/bin/bash
# Keyboard theme vs. app theme: night on/off, launcher search + Contacts editor + our app.
mkdir -p /tmp/shots; S=/tmp/shots
PKG=dev.glasskeyboard.ime; IME=$PKG/.GlassKeyboardService
adb install -r /tmp/app.apk > $S/install.txt 2>&1
for i in 1 2 3 4 5 6; do
  adb shell ime enable $IME >> $S/start.txt 2>&1 && adb shell ime set $IME >> $S/start.txt 2>&1
  [ "$(adb shell settings get secure default_input_method | tr -d '\r')" = "$IME" ] && break
  sleep 5
done
adb logcat -c
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$S/$1.png"; }
tap_edit() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml $S/ui.xml > /dev/null 2>&1
  XY=$(python3 - "$S/ui.xml" <<'PY'
import re, sys
x = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2) if m else print("540 300")
PY
)
  adb shell input tap $XY
}
run() {
  mode=$1
  adb shell cmd uimode night $mode; sleep 3
  # launcher: all-apps search
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb shell input swipe 540 1800 540 600 300; sleep 2
  tap_edit; shot "$mode-1-launcher" 3
  adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_HOME
  # contacts editor
  adb shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >> $S/start.txt 2>&1; sleep 3
  tap_edit; shot "$mode-2-contacts" 3
  adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK
  # settings search
  adb shell am start -W -a android.settings.APP_SEARCH_SETTINGS >> $S/start.txt 2>&1; sleep 3
  tap_edit; shot "$mode-3-settings" 3
  adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK
  # our app
  adb shell am start -W -n $PKG/.SettingsActivity --es screen punctuation >> $S/start.txt 2>&1; sleep 3
  tap_edit; shot "$mode-4-ours" 3
  adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK
}
run no
run yes
adb logcat -d -b crash | grep -A40 "dev.glasskeyboard" > $S/crash.txt 2>&1
adb logcat -d | grep -iE "GlassKeyboard|AndroidRuntime" | tail -200 > $S/log.txt
exit 0
