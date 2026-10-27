#!/usr/bin/env sh
# Needs cargo-ndk (cargo install cargo-ndk) and the Android NDK (ANDROID_NDK_HOME).
set -eu
cd "$(dirname "$0")/.."
rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk -t arm64-v8a -t x86_64 -o app/src/androidMain/jniLibs build --release -p loki-ffi
