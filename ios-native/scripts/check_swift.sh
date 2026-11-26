#!/bin/bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
check_out="${1:-/tmp/inkflow-native-swift-check}"
mkdir -p "$check_out"
simulator_sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
platform_developer="$(xcode-select -p)/Platforms/iPhoneSimulator.platform/Developer"
xcrun swiftc -sdk "$simulator_sdk" -target arm64-apple-ios17.0-simulator \
  -parse-as-library -enable-testing -module-name InkflowNative \
  -emit-module -emit-module-path "$check_out/InkflowNative.swiftmodule" \
  -emit-executable -o "$check_out/InkflowNative" "$project_root"/InkflowNative/*.swift
xcrun swiftc -sdk "$simulator_sdk" -target arm64-apple-ios17.0-simulator \
  -I "$check_out" -I "$platform_developer/usr/lib" \
  -F "$platform_developer/Library/Frameworks" \
  -typecheck "$project_root"/InkflowNativeTests/*.swift
xcrun vtool -show-build "$check_out/InkflowNative"
echo "Source-only simulator executable linked; test sources type-checked. No runtime tests executed."
