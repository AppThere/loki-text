#!/usr/bin/env sh
# macOS only, with Xcode and XcodeGen (`brew install xcodegen`).
#   scripts/build-ios.sh                 build target/<triple>/release/libloki_ffi.a for devices and the simulator
#   scripts/build-ios.sh run [DEVICE]    also generate iosApp/LokiText.xcodeproj, build the app for the simulator, and
#                                        install and launch it on DEVICE (a simulator name; default "iPhone 17")
#   GRADLE=/path/to/gradle               use this Gradle when the folder has no ./gradlew and none is on the PATH
# After a first run, the project opens in Xcode (iosApp/LokiText.xcodeproj), whose build phase rebuilds both libraries.
set -eu
cd "$(dirname "$0")/.."
if [ "${1:-}" != run ]; then
    for t in aarch64-apple-ios aarch64-apple-ios-sim; do
        rustup target add "$t"
        cargo build --release -p loki-ffi --target "$t"
    done
    exit 0
fi
device="${2:-iPhone 17}"
rustup target add aarch64-apple-ios-sim
(cd iosApp && xcodegen generate --quiet)
derived=build/ios
xcodebuild -project iosApp/LokiText.xcodeproj -scheme LokiText -configuration Debug -sdk iphonesimulator \
    -destination "platform=iOS Simulator,name=$device" -derivedDataPath "$derived" build
app="$derived/Build/Products/Debug-iphonesimulator/LokiText.app"
xcrun simctl boot "$device" 2>/dev/null || true
xcrun simctl install "$device" "$app"
xcrun simctl launch "$device" com.appthere.loki.text
