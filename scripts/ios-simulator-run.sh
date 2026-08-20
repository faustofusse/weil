#!/usr/bin/env bash
# Builds the iOS app (Kotlin framework included, via the Xcode build phase)
# and launches it in an iOS simulator.
#
# Usage: scripts/ios-simulator-run.sh [simulator name or UDID]
#   With no argument: reuse the currently booted simulator, or boot the first
#   available iPhone.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

XCODEPROJ="app/iosApp/iosApp.xcodeproj"
SCHEME="iosApp"
CONFIGURATION="Debug"
DERIVED_DATA="app/iosApp/build/DerivedData"

# The nix dev shell exports a bare Apple SDK (DEVELOPER_DIR/SDKROOT), Nix
# cc-wrapper tool names (CC/LD/... — xcodebuild honours env vars as build
# setting overrides) and an xcbuild xcrun shim, all of which break the real
# Xcode toolchain. Strip them.
unset DEVELOPER_DIR SDKROOT CC CXX CPP LD AR AS NM RANLIB STRIP LIBTOOL \
      NIX_CC NIX_CFLAGS_COMPILE NIX_LDFLAGS NIX_HARDENING_ENABLE \
      NIX_ENFORCE_NO_NATIVE NIX_DONT_SET_RPATH NIX_IGNORE_LD_THROUGH_GCC \
      NIX_CC_WRAPPER_TARGET_HOST NIX_BINTOOLS_WRAPPER_TARGET_HOST
PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -v '^/nix/store/' | paste -sd: -):/usr/bin:/bin:/usr/sbin:/sbin"
export PATH

XCRUN=/usr/bin/xcrun
XCODEBUILD=/usr/bin/xcodebuild

# --- pick a simulator -------------------------------------------------------
TARGET="${1:-}"
if [ -z "$TARGET" ]; then
  # Reuse a booted simulator if there is one…
  UDID="$($XCRUN simctl list -j devices booted | python3 -c '
import json, sys
devs = [d for rt in json.load(sys.stdin)["devices"].values() for d in rt]
print(devs[0]["udid"] if devs else "")')"
  # …otherwise boot the first available iPhone
  if [ -z "$UDID" ]; then
    UDID="$($XCRUN simctl list -j devices available | python3 -c '
import json, sys
devs = [d for rt in json.load(sys.stdin)["devices"].values() for d in rt if "iPhone" in d["name"]]
print(devs[0]["udid"] if devs else "")')"
    [ -n "$UDID" ] || { echo "error: no available iPhone simulator found" >&2; exit 1; }
    echo "Booting simulator ${UDID}…"
    $XCRUN simctl boot "$UDID"
  fi
else
  UDID="$($XCRUN simctl list -j devices | python3 -c '
import json, sys
target = sys.argv[1]
devs = [d for rt in json.load(sys.stdin)["devices"].values() for d in rt]
match = next((d for d in devs if d["udid"] == target or d["name"] == target), None)
print(match["udid"] if match else "")' "$TARGET")"
  [ -n "$UDID" ] || { echo "error: simulator '$TARGET' not found" >&2; exit 1; }
  if ! $XCRUN simctl list devices booted | grep -q "$UDID"; then
    echo "Booting $TARGET (${UDID})…"
    $XCRUN simctl boot "$UDID"
  fi
fi
open -a Simulator

# --- build (this also builds the Kotlin framework via the script phase) -----
echo "Building $SCHEME for simulator ${UDID}…"
$XCODEBUILD -project "$XCODEPROJ" -scheme "$SCHEME" -configuration "$CONFIGURATION" \
  -sdk iphonesimulator -destination "platform=iOS Simulator,id=$UDID" \
  -derivedDataPath "$DERIVED_DATA" build

# --- install & launch -------------------------------------------------------
APP="$(find "$DERIVED_DATA/Build/Products/$CONFIGURATION-iphonesimulator" -maxdepth 1 -name '*.app' | head -1)"
[ -n "$APP" ] || { echo "error: built .app not found under $DERIVED_DATA" >&2; exit 1; }
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")"

echo "Installing $(basename "$APP") (${BUNDLE_ID})…"
$XCRUN simctl install "$UDID" "$APP"
echo "Launching ${BUNDLE_ID}…"
$XCRUN simctl launch "$UDID" "$BUNDLE_ID"
