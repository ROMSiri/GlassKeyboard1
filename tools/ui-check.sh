#!/bin/bash
# Installs the APK on the emulator, opens screens, saves screenshots + crash log to /tmp/shots.
mkdir -p /tmp/shots
S=/tmp/shots
PKG=dev.glasskeyboard.ime
adb install -r /tmp/app.apk > $S/install.txt 2>&1
adb logcat -c
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$S/$1.png"; }
open() { adb shell am start -S -W -n $PKG/.SettingsActivity ${1:+--es screen $1} >> $S/start.txt 2>&1; }

adb shell ime enable $PKG/.GlassKeyboardService >> $S/start.txt 2>&1
adb shell ime set $PKG/.GlassKeyboardService >> $S/start.txt 2>&1

open; shot 01-main 4
adb shell input swipe 540 1900 540 500 400
shot 02-main-scrolled 2
for s in correction toolbar gesture appearance personal tool_keys; do open $s; shot "10-$s" 3; done

# keyboard: open a screen with a text field, find the field with uiautomator and tap it
open punctuation; sleep 3
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
adb pull /sdcard/ui.xml $S/ui.xml > /dev/null 2>&1
XY=$(python3 - "$S/ui.xml" <<'PY'
import re, sys
x = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2) if m else print("540 700")
PY
)
echo "field at $XY" >> $S/start.txt
adb shell input tap $XY
shot 20-keyboard 4
adb shell input keyevent KEYCODE_MOVE_END
# tap letters on the keyboard by screen position: read IME window frame
adb shell dumpsys window windows > $S/windows.txt
shot 21-keyboard-again 1

adb logcat -d -b crash > $S/crash.txt 2>&1
adb logcat -d | grep -iE "GlassKeyboard|PanelBlur|AndroidRuntime|FATAL|dev.glasskeyboard" | tail -300 > $S/log.txt
adb shell dumpsys window | grep -iE "mCurrentFocus|mFocusedApp" > $S/focus.txt
exit 0
