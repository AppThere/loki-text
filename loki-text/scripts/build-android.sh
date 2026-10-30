#!/usr/bin/env sh
# Builds libloki_ffi for Android into app/src/androidMain/jniLibs, then the debug APK.
#
#   scripts/build-android.sh          # arm64-v8a (phones, ARM Chromebooks) and the APK
#   ABIS="arm64-v8a x86_64" scripts/build-android.sh
#
# Needs the Android SDK + NDK (ANDROID_HOME, default ~/Android/Sdk) and rustup. On x86_64 hosts it
# uses cargo-ndk. The NDK's compiler is x86_64-only, so on an aarch64 Linux host (an ARM
# Chromebook) it uses the host's clang and lld against the NDK's sysroot instead.
set -eu
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ABIS="${ABIS:-arm64-v8a}"
API=26
OUT=app/src/androidMain/jniLibs

NDK="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "No Android NDK under $ANDROID_HOME/ndk (set ANDROID_NDK_HOME)" >&2; exit 1; }

triple_for() { case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    x86_64) echo x86_64-linux-android ;;
    *) echo "unsupported ABI $1" >&2; exit 1 ;;
esac; }

if [ "$(uname -m)" = "aarch64" ] && [ "$(uname -s)" = "Linux" ]; then
    command -v clang >/dev/null && command -v ld.lld >/dev/null || { echo "Install clang and lld (the NDK's own compiler is x86_64-only)" >&2; exit 1; }
    PRE="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
    RES=$(ls -d "$PRE"/lib/clang/* | head -1)
    AR=$(command -v llvm-ar || command -v llvm-ar-19 || command -v ar)
    for abi in $ABIS; do
        t=$(triple_for "$abi")
        rustup target add "$t"
        wrapper=$(mktemp)
        printf '#!/bin/sh\nexec clang --target=%s%s --sysroot=%s/sysroot -resource-dir=%s -fuse-ld=lld "$@"\n' "$t" "$API" "$PRE" "$RES" > "$wrapper"
        chmod +x "$wrapper"
        up=$(echo "$t" | tr 'a-z-' 'A-Z_')
        env "CARGO_TARGET_${up}_LINKER=$wrapper" "CC_$(echo "$t" | tr - _)=$wrapper" "AR_$(echo "$t" | tr - _)=$AR" \
            cargo build --release -p loki-ffi --target "$t"
        rm -f "$wrapper"
        mkdir -p "$OUT/$abi"
        cp "target/$t/release/libloki_ffi.so" "$OUT/$abi/"
    done
else
    export ANDROID_NDK_HOME="$NDK"
    targets=""
    for abi in $ABIS; do targets="$targets -t $abi"; rustup target add "$(triple_for "$abi")"; done
    # shellcheck disable=SC2086
    cargo ndk $targets -o "$OUT" build --release -p loki-ffi
fi

if [ -x ./gradlew ]; then GRADLE=./gradlew; else GRADLE=gradle; fi
$GRADLE :app:assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
echo "Install: adb install -r app/build/outputs/apk/debug/app-debug.apk"
