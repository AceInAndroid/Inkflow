#!/bin/bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
check_out="${1:-/tmp/inkflow-native-swift-check}"
check_mode="${2:-source-only}"
# Guard empty-array expansions below for macOS Bash 3.2 with nounset.
swift_options=()
link_options=()
case "$check_mode" in
  source-only) ;;
  shared-core)
    framework_slice="$project_root/../android/core/build/XCFrameworks/debug/InkflowCore.xcframework/ios-arm64-simulator"
    if [[ ! -s "$framework_slice/InkflowCore.framework/InkflowCore" ]]; then
      echo "Missing real simulator framework. Run :core:assembleInkflowCoreDebugXCFramework -PenableIos=true first." >&2
      exit 1
    fi
    swift_options=(-D INKFLOW_SHARED_CORE -F "$framework_slice")
    link_options=(-framework InkflowCore -lc++)
    ;;
  *) echo "Usage: $0 [output-directory] [source-only|shared-core]" >&2; exit 2 ;;
esac
mkdir -p "$check_out"
plutil -lint "$project_root/InkflowNative/Info.plist"
simulator_sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
platform_developer="$(xcode-select -p)/Platforms/iPhoneSimulator.platform/Developer"
xcrun swiftc -sdk "$simulator_sdk" -target arm64-apple-ios17.0-simulator \
  ${swift_options[@]+"${swift_options[@]}"} -parse-as-library -enable-testing -module-name InkflowNative \
  -emit-module -emit-module-path "$check_out/InkflowNative.swiftmodule" \
  -emit-executable -o "$check_out/InkflowNative" "$project_root"/InkflowNative/*.swift ${link_options[@]+"${link_options[@]}"}
xcrun swiftc -sdk "$simulator_sdk" -target arm64-apple-ios17.0-simulator \
  ${swift_options[@]+"${swift_options[@]}"} -I "$check_out" -I "$platform_developer/usr/lib" \
  -F "$platform_developer/Library/Frameworks" \
  -typecheck "$project_root"/InkflowNativeTests/*.swift
xcrun vtool -show-build "$check_out/InkflowNative"
echo "$check_mode simulator executable linked; test sources type-checked. No runtime tests executed."
