#!/usr/bin/env bash
# Builds the Turso sync engine (turso_sync_sdk_kit) as a Rust staticlib for iOS
# and installs it into app/sharedLogic/src/iosMain/nativeLibs/<kmp-target>/.
#
# The .a files are ~170 MB each and are NOT committed (see .gitignore); run this
# once after a fresh clone, or when bumping TURSO_COMMIT.
#
# Usage: scripts/build-turso-ios.sh [path-to-turso-checkout]
set -euo pipefail

# Same commit as the Android jniLibs .so — the vendored headers in
# app/sharedLogic/src/androidMain/turso-headers must match the ABI exactly.
TURSO_COMMIT="7e2fc39de"   # app API 0.8.0-pre.8
TURSO_DIR="${1:-/tmp/turso-repo}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/sharedLogic/src/iosMain/nativeLibs"

# Real Xcode toolchain, not the nix dev shell's bare SDK (see ios-device-run.sh).
unset SDKROOT CC CXX CPP LD AR AS NM RANLIB STRIP LIBTOOL \
      NIX_CFLAGS_COMPILE NIX_LDFLAGS 2>/dev/null || true
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
export PATH="$HOME/.cargo/bin:$PATH"

if [ ! -d "$TURSO_DIR" ]; then
  echo "Cloning turso into $TURSO_DIR…"
  git clone --filter=blob:none https://github.com/tursodatabase/turso.git "$TURSO_DIR"
fi
cd "$TURSO_DIR"
git checkout -q "$TURSO_COMMIT"

# Header sanity check: the vendored headers drive cinterop, a mismatch is silent
# memory corruption.
for h in turso.h turso_sync.h; do
  src="$(find . -name "$h" -not -path './node_modules/*' | head -1)"
  diff -q "$src" "$REPO_ROOT/app/sharedLogic/src/androidMain/turso-headers/$h" >/dev/null \
    || { echo "error: $h differs from the vendored copy — re-vendor both headers" >&2; exit 1; }
done

rustup toolchain install 1.88 --profile minimal
rustup target add aarch64-apple-ios aarch64-apple-ios-sim --toolchain 1.88

declare -a PAIRS=(
  "aarch64-apple-ios:iosArm64"
  "aarch64-apple-ios-sim:iosSimulatorArm64"
)
for pair in "${PAIRS[@]}"; do
  triple="${pair%%:*}"
  kmp="${pair##*:}"
  echo "=== building $triple -> $kmp"
  cargo +1.88 build --release --package turso_sync_sdk_kit --target "$triple"
  mkdir -p "$DEST/$kmp"
  # The release profile ships debuginfo (~285 MB); -S drops it (~170 MB).
  /usr/bin/strip -S -o "$DEST/$kmp/libturso_sync_sdk_kit.a" \
    "target/$triple/release/libturso_sync_sdk_kit.a"
done

ls -lh "$DEST"/*/libturso_sync_sdk_kit.a
