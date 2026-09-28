#!/usr/bin/env bash
# Clean status bar for screenshots: 12:00, full battery, full Wi-Fi, no notification icons.
adb shell settings put global sysui_demo_allowed 1
d() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
d enter
d clock -e hhmm 1200
d battery -e level 100 -e plugged false
d network -e wifi show -e level 4 -e fully true
d network -e mobile hide
d notifications -e visible false
d status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide -e tty hide -e eri hide -e mute hide -e speakerphone hide
