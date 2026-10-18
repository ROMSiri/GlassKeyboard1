#!/bin/bash
# Installs the APK on the emulator, opens screens, saves screenshots + crash log to /tmp/shots.
mkdir -p /tmp/shots
S=/tmp/shots
PKG=dev.glasskeyboard.ime
adb install -r /tmp/app.apk > $S/install.txt 2>&1
adb logcat -c
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$S/$1.png"; }

adb shell am start -W -n $PKG/.SettingsActivity > $S/start.txt 2>&1
shot 01-main 4
adb shell input swipe 540 1800 540 600 300
shot 02-main-scrolled 2
for s in correction toolbar gesture appearance personal tool_keys; do
  adb shell am start -W -n $PKG/.SettingsActivity --es screen $s >> $S/start.txt 2>&1
  shot "10-$s" 3
done

# keyboard
adb shell ime enable $PKG/.GlassKeyboardService >> $S/start.txt 2>&1
adb shell ime set $PKG/.GlassKeyboardService >> $S/start.txt 2>&1
adb shell am start -W -n $PKG/.SettingsActivity --es screen punctuation >> $S/start.txt 2>&1
sleep 3
adb shell input tap 540 640
shot 20-keyboard 4
adb shell input text "privet"
shot 21-typing 2

adb logcat -d -b crash > $S/crash.txt 2>&1
adb logcat -d | grep -iE "GlassKeyboard|PanelBlur|AndroidRuntime|FATAL|dev.glasskeyboard" | tail -300 > $S/log.txt
adb shell dumpsys window | grep -iE "mCurrentFocus|mFocusedApp" > $S/focus.txt
exit 0
