#!/usr/bin/env sh
# macOS only. Produces target/<triple>/release/libloki_ffi.a, linked by the Gradle iOS framework.
set -eu
cd "$(dirname "$0")/.."
for t in aarch64-apple-ios aarch64-apple-ios-sim; do
  rustup target add "$t"
  cargo build --release -p loki-ffi --target "$t"
done
