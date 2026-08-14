#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

NEWEST_RS=$(find "$ROOT_DIR/crates" -type f -name "*.rs" -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1 || echo 0)

ARM64_SO="$ROOT_DIR/apps/android/app/src/main/jniLibs/arm64-v8a/libnxfr_ffi.so"
X86_64_SO="$ROOT_DIR/apps/android/app/src/main/jniLibs/x86_64/libnxfr_ffi.so"

if [ ! -f "$ARM64_SO" ] || [ ! -f "$X86_64_SO" ]; then
    echo "ERROR: Native libraries missing in jniLibs!"
    echo "Run: cargo ndk -t arm64-v8a -t x86_64 -o apps/android/app/src/main/jniLibs build --release -p nxfr-ffi"
    echo "Or: cd apps/android && ./gradlew rebuildNative"
    exit 1
fi

ARM64_TIME=$(stat -c %Y "$ARM64_SO" 2>/dev/null || stat -f %m "$ARM64_SO")
X86_64_TIME=$(stat -c %Y "$X86_64_SO" 2>/dev/null || stat -f %m "$X86_64_SO")

if [ "$ARM64_TIME" -lt "$NEWEST_RS" ] || [ "$X86_64_TIME" -lt "$NEWEST_RS" ]; then
    echo "ERROR: STALE NATIVE LIB — Rust source files have been modified since last native build."
    echo "Run: ./gradlew rebuildNative (or cargo ndk -t arm64-v8a -t x86_64 -o apps/android/app/src/main/jniLibs build --release -p nxfr-ffi)"
    exit 1
fi

echo "Native libraries are fresh and in sync with crates/ source."
exit 0
