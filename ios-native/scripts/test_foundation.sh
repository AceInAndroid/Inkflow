#!/bin/bash
# Runs the same preferences/library XCTest sources on macOS, without an iOS runtime.
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
check_out="$(mktemp -d /tmp/inkflow-foundation-tests.XXXXXX)"
trap 'rm -rf "$check_out"' EXIT
mkdir -p "$check_out/Sources/InkflowNative" "$check_out/Tests/InkflowNativeTests"
cat > "$check_out/Package.swift" <<'SWIFT'
// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "InkflowFoundationChecks", platforms: [.macOS(.v13)], targets: [
    .target(name: "InkflowNative"),
    .testTarget(name: "InkflowNativeTests", dependencies: ["InkflowNative"])
])
SWIFT
cp "$project_root/InkflowNative/AppPreferences.swift" "$project_root/InkflowNative/InterfaceStrings.swift" "$project_root/InkflowNative/LibraryStore.swift" "$project_root/InkflowNative/ReadingLocation.swift" "$check_out/Sources/InkflowNative/"
cp "$project_root/InkflowNativeTests/AppPreferencesTests.swift" "$project_root/InkflowNativeTests/HomeLibraryTests.swift" "$project_root/InkflowNativeTests/LibraryRecoveryTests.swift" "$check_out/Tests/InkflowNativeTests/"
xcrun swift test --package-path "$check_out"
