#!/usr/bin/env sh
# Build libloki_ffi for the host (JVM desktop), then run: ./gradlew :app:run
set -eu
cd "$(dirname "$0")/.."
cargo build --release -p loki-ffi
