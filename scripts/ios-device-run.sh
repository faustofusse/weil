#!/usr/bin/env bash
# Builds the iOS app (Kotlin framework included, via the Xcode build phase)
# and installs + launches it on a physical iPhone connected over USB.
#
# Usage: scripts/ios-device-run.sh [device name or UDID]
#   With no argument: use the single connected device, or fail listing them.
#   --no-console: don't attach to the app's stdout/stderr after launch.
#
# Requirements on the phone: unlocked, trusted ("Trust this computer"), and
# Settings > Privacy & Security > Developer Mode = On (a device with Developer
# Mode off shows up as "connected (no DDI)" and installs fail).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

XCODEPROJ="app/iosApp/iosApp.xcodeproj"
SCHEME="iosApp"
CONFIGURATION="Debug"
DERIVED_DATA="app/iosApp/build/DerivedDataDevice"

CONSOLE=1
TARGET=""
for arg in "$@"; do
  case "$arg" in
    --no-console) CONSOLE=0 ;;
    *) TARGET="$arg" ;;
  esac
done

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

# --- pick a device ----------------------------------------------------------
DEVICES_JSON="$(mktemp -t weil-devices)"
$XCRUN devicectl list devices --json-output "$DEVICES_JSON" >/dev/null

read -r DEVICE_ID DEVICE_NAME DEV_MODE DEVICE_UDID <<EOF
$(python3 - "$DEVICES_JSON" "$TARGET" <<'PY'
import json, sys
path, target = sys.argv[1], sys.argv[2]
devs = json.load(open(path))["result"]["devices"]
usable = []
for d in devs:
    props = d.get("deviceProperties", {})
    name = props.get("name", "?")
    udid = d.get("hardwareProperties", {}).get("udid", "")
    ident = d.get("identifier", "")
    state = d.get("connectionProperties", {}).get("tunnelState", "")
    if d.get("connectionProperties", {}).get("pairingState") != "paired":
        continue
    if state == "unavailable":
        continue
    usable.append((ident, name, udid, props.get("developerModeStatus", "unknown")))
if target:
    usable = [d for d in usable if target in (d[0], d[1], d[2])]
if len(usable) != 1:
    for ident, name, udid, dm in usable:
        print(f"  {name}  id={ident}  udid={udid}  developerMode={dm}", file=sys.stderr)
    print("error: specify one device (name or udid)" if usable else
          "error: no connected iPhone found", file=sys.stderr)
    sys.exit(1)
ident, name, udid, dm = usable[0]
print(ident, name.replace(" ", "\u00a0"), dm, udid)
PY
)
EOF
rm -f "$DEVICES_JSON"
DEVICE_NAME="${DEVICE_NAME//$'\u00a0'/ }"

echo "Device: $DEVICE_NAME ($DEVICE_ID)"
if [ "$DEV_MODE" != "enabled" ]; then
  echo "error: Developer Mode is '$DEV_MODE' on $DEVICE_NAME." >&2
  echo "       Enable Settings > Privacy & Security > Developer Mode, reboot, unlock." >&2
  exit 1
fi

# --- build (this also builds the Kotlin framework via the script phase) -----
# The device-specific destination (plus -allowProvisioningDeviceRegistration)
# is what makes Xcode register a new iPhone in the dev account; a
# 'generic/platform=iOS' build signs with a profile that excludes it and the
# install then fails with 0xe8008012.
echo "Building $SCHEME for device…"
$XCODEBUILD -project "$XCODEPROJ" -scheme "$SCHEME" -configuration "$CONFIGURATION" \
  -destination "id=$DEVICE_UDID" -derivedDataPath "$DERIVED_DATA" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration build

# --- install & launch -------------------------------------------------------
APP="$(find "$DERIVED_DATA/Build/Products/$CONFIGURATION-iphoneos" -maxdepth 1 -name '*.app' | head -1)"
[ -n "$APP" ] || { echo "error: built .app not found under $DERIVED_DATA" >&2; exit 1; }
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")"

echo "Installing $(basename "$APP") (${BUNDLE_ID})…"
$XCRUN devicectl device install app --device "$DEVICE_ID" "$APP"

echo "Launching ${BUNDLE_ID}…"
if [ "$CONSOLE" = 1 ]; then
  $XCRUN devicectl device process launch --console --terminate-existing \
    --device "$DEVICE_ID" "$BUNDLE_ID"
else
  $XCRUN devicectl device process launch --terminate-existing \
    --device "$DEVICE_ID" "$BUNDLE_ID"
fi
