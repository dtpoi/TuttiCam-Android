#!/bin/bash
# 用 iOS 工程里的原始模型代码编译 golden 工具（需要 macOS + Xcode）。
# 用 IOS 指定 iOS 工程目录，默认是同级的 "iOS Project/TuttiCam"。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
IOS="${IOS:-$HERE/../../../../iOS Project/TuttiCam}"
OUT="${OUT:-$HERE/.build}"
mkdir -p "$OUT"
xcrun swiftc -swift-version 6 -O -o "$OUT/golden" \
  "$IOS/TuttiCam/Models/Messages.swift" "$IOS/TuttiCam/Models/CaptureSettings.swift" \
  "$HERE/Stubs.swift" "$HERE/main.swift"
echo "$OUT/golden"
