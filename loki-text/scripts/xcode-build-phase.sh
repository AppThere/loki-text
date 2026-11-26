#!/usr/bin/env sh
# Xcode's pre-build phase for iosApp: builds libloki_ffi.a for the SDK being built, then the Kotlin framework (Gradle's
# embedAndSignAppleFrameworkForXcode reads Xcode's CONFIGURATION, SDK_NAME and ARCHS). Run by Xcode, not by hand.
#   GRADLE=/path/to/gradle   use this Gradle when the folder has no ./gradlew and none is on Xcode's PATH
set -eu
cd "$(dirname "$0")/.."
# Xcode starts phases with a short PATH; rustup's default place for cargo is added.
export PATH="$HOME/.cargo/bin:$PATH"
case "${SDK_NAME:-}" in
    iphonesimulator*) triple=aarch64-apple-ios-sim ;;
    iphoneos*) triple=aarch64-apple-ios ;;
    *) echo "error: unsupported SDK '${SDK_NAME:-}'" >&2; exit 1 ;;
esac
cargo build --release -p loki-ffi --target "$triple"
if [ -n "${GRADLE:-}" ]; then gradle="$GRADLE"; elif [ -x ./gradlew ]; then gradle=./gradlew; else gradle=gradle; fi
"$gradle" --console=plain :app:embedAndSignAppleFrameworkForXcode
