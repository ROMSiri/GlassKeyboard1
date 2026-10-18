#!/bin/bash
# Installs the APK on the emulator, opens screens, exercises the keyboard; screenshots + logs to /tmp/shots.
mkdir -p /tmp/shots
S=/tmp/shots
PKG=dev.glasskeyboard.ime
IME=$PKG/.GlassKeyboardService
adb install -r /tmp/app.apk > $S/install.txt 2>&1
adb logcat -c
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$S/$1.png"; }
open() { adb shell am start -S -W -n $PKG/.SettingsActivity ${1:+--es screen $1} >> $S/start.txt 2>&1; }

open; shot 01-main 4
adb shell input swipe 540 1900 540 500 400
shot 02-main-scrolled 2
for s in correction toolbar gesture appearance personal tool_keys; do open $s; shot "10-$s" 3; done

# our keyboard: the system needs a moment to list a freshly installed IME
for i in 1 2 3 4 5 6; do
  adb shell ime enable $IME >> $S/start.txt 2>&1 && adb shell ime set $IME >> $S/start.txt 2>&1
  [ "$(adb shell settings get secure default_input_method | tr -d '\r')" = "$IME" ] && break
  sleep 5
done
adb shell settings get secure default_input_method >> $S/start.txt

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
adb shell input tap $XY
sleep 1
# clear the field
adb shell input keyevent KEYCODE_MOVE_END
for i in $(seq 1 25); do adb shell input keyevent KEYCODE_DEL; done
shot 20-keyboard 3

# keyboard geometry (dp → px) from the IME window frame
adb shell dumpsys window windows > $S/windows.txt
python3 - "$S/windows.txt" > $S/geo.sh <<'PY'
import re, sys
t = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
blk = t[t.find('InputMethod'):]
m = re.search(r'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', blk) or re.search(r'mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', blk)
top = int(m.group(2)) if m else 1400
print(f"TOP={top}")
PY
. $S/geo.sh
D=2.625   # pixel_6 density
px() { python3 -c "print(int(round($1)))"; }
UNIT=$(python3 -c "print((1080-2*3*$D)/10)")
ROWY() { python3 -c "print(int($TOP + (4+46)*$D + ($1+0.5)*56*$D))"; }
KEYX() { python3 -c "print(int(3*$D + $1*$UNIT))"; }
DOTSX=$(python3 -c "print(int(1080 - (3+4+18)*$D))"); DOTSY=$(python3 -c "print(int($TOP + (4+23)*$D))")
echo "TOP=$TOP DOTS=$DOTSX,$DOTSY" >> $S/start.txt

adb shell input tap $DOTSX $DOTSY; shot 22-menu-open 2
adb shell input tap $DOTSX $DOTSY; shot 23-menu-closed 1

# type "привет" by tapping keys (unit x positions of key centres)
tapkey() { adb shell input tap $(KEYX $1) $(ROWY $2); sleep 0.3; }
tapkey 4.0 1; tapkey 5.0 1; tapkey 5.0 2; tapkey 2.0 1; tapkey 4.5 0; tapkey 6.0 2
shot 24-typed 2
# space bar (centre of bottom row)
adb shell input tap 540 $(ROWY 3); shot 25-after-space 2

# a typo: "прмвет" → should autocorrect on space
tapkey 4.0 1; tapkey 5.0 1; tapkey 4.0 2; tapkey 2.0 1; tapkey 4.5 0; tapkey 6.0 2
shot 26-typo 2
adb shell input tap 540 $(ROWY 3); shot 27-autocorrected 2

# gesture "как": к(3.5,0) → а(3.0,1) → к(3.5,0)
K="$(KEYX 3.5) $(ROWY 0)"; A="$(KEYX 3.0) $(ROWY 1)"
adb shell input motionevent DOWN $K
for t in 0.2 0.4 0.6 0.8 1.0; do
  adb shell input motionevent MOVE $(python3 -c "k=[$K];a=[$A];t=$t;print(int(k[0]+(a[0]-k[0])*t), int(k[1]+(a[1]-k[1])*t))")
done
for t in 0.2 0.4 0.6 0.8 1.0; do
  adb shell input motionevent MOVE $(python3 -c "k=[$A];a=[$K];t=$t;print(int(k[0]+(a[0]-k[0])*t), int(k[1]+(a[1]-k[1])*t))")
done
adb shell input motionevent UP $K
shot 28-gesture 2

adb logcat -d -b crash | grep -A40 "dev.glasskeyboard" > $S/crash.txt 2>&1
adb logcat -d | grep -iE "GlassKeyboard|PanelBlur|DictionaryCache|AndroidRuntime" | tail -200 > $S/log.txt
exit 0
