#!/bin/bash
# Animation smoothness on the phone over USB: scripted gestures per area, then the share of frames that reached the
# screen after their deadline (dumpsys gfxinfo framestats, last <=120 frames per area). Music is paused first: while it
# plays, the 150 ms progress redraws count as "late" lone frames and hide the animation figures.
# Per area: frames that missed their display deadline (framestats, last <=120 frames per area).
export MSYS_NO_PATHCONV=1; source ~/dev/sable-toolchain/env.sh >/dev/null 2>&1
S="$(dirname "$0")"; A="am start -n app.sable/.ui.MainActivity --es cmd"
fs() { adb shell dumpsys gfxinfo app.sable framestats > "$TEMP/fs_$1.txt"; python "$S/late_frames.py" "$TEMP/fs_$1.txt" "$1"; }
r() { adb shell dumpsys gfxinfo app.sable reset >/dev/null; }
adb shell $A playalbum --es match Charli >/dev/null 2>&1; sleep 3; adb shell $A pause >/dev/null 2>&1; sleep 1
adb shell $A open --es screen nowplaying >/dev/null 2>&1; sleep 2; r
for k in 1 2; do adb shell input swipe 850 800 200 800 180; sleep 1.2; adb shell input swipe 200 800 900 800 180; sleep 1.2; done; fs cover-swipes
r; for k in 1 2; do adb shell input swipe 540 400 540 1500 220; sleep 1.2; adb shell $A open --es screen nowplaying >/dev/null 2>&1; sleep 1.2; done; fs nowplaying-close-open
adb shell $A open --es screen settings >/dev/null 2>&1; sleep 2; r
# Taps are found by the words on screen (uiautomator), not by fixed pixels, so they survive layout changes.
tap_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$TEMP/ui.xml" >/dev/null 2>&1
  xy=$(python "$S/tap_xy.py" "$TEMP/ui.xml" "$1"); [ -n "$xy" ] && adb shell input tap $xy; }
for k in 1 2; do tap_text "Advanced"; sleep 1.2; tap_text "Easy"; sleep 1.2; done; fs advanced-toggle
adb shell $A open --es screen library >/dev/null 2>&1; sleep 2; r
for k in 1 2; do adb shell input swipe 540 1800 540 500 250; sleep 0.8; adb shell input swipe 540 500 540 1800 250; sleep 0.8; done; fs library-scroll
adb shell $A pause >/dev/null 2>&1
