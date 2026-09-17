#!/usr/bin/env bash
# Archives the iOS app for the App Store, exports a signed IPA and uploads it
# to TestFlight with an App Store Connect API key.
#
# Usage: scripts/ios-testflight.sh [options]
#   --build N        build number (CFBundleVersion). Default: git commit count.
#   --version X.Y    marketing version (CFBundleShortVersionString).
#                    Default: whatever the project already declares.
#   --validate-only  run altool validation, don't upload.
#   --no-upload      archive + export only, print the IPA path.
#   --skip-archive   reuse the existing archive (export/upload only).
#
# API key: expects the .p8 in one of the places altool looks
#   (~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8). If it is still in
#   ~/Downloads, this script copies it there once, chmod 600.
# Override the key/issuer with ASC_KEY_ID / ASC_ISSUER_ID env vars.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

XCODEPROJ="app/iosApp/iosApp.xcodeproj"
SCHEME="iosApp"
CONFIGURATION="Release"
OUT_DIR="${IOS_DIST_DIR:-$REPO_ROOT/app/iosApp/build/distribution}"
ARCHIVE="$OUT_DIR/Weil.xcarchive"
EXPORT_DIR="$OUT_DIR/export"

ASC_KEY_ID="${ASC_KEY_ID:-45FQMCJ7R7}"
ASC_ISSUER_ID="${ASC_ISSUER_ID:-3861da2b-1bbb-422c-90d9-1afddaa4bc7e}"
KEY_DIR="$HOME/.appstoreconnect/private_keys"
KEY_FILE="$KEY_DIR/AuthKey_${ASC_KEY_ID}.p8"

BUILD_NUMBER=""
MARKETING_VERSION=""
DO_UPLOAD=1
VALIDATE_ONLY=0
DO_ARCHIVE=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) BUILD_NUMBER="$2"; shift 2 ;;
    --version) MARKETING_VERSION="$2"; shift 2 ;;
    --validate-only) VALIDATE_ONLY=1; shift ;;
    --no-upload) DO_UPLOAD=0; shift ;;
    --skip-archive) DO_ARCHIVE=0; shift ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# ---- API key -----------------------------------------------------------------
if [[ ! -f "$KEY_FILE" ]]; then
  for candidate in "$HOME/Downloads/AuthKey_${ASC_KEY_ID}.p8" "$REPO_ROOT/AuthKey_${ASC_KEY_ID}.p8"; do
    if [[ -f "$candidate" ]]; then
      mkdir -p "$KEY_DIR"
      cp "$candidate" "$KEY_FILE"
      chmod 600 "$KEY_FILE"
      echo "==> installed API key: $KEY_FILE (from $candidate)"
      break
    fi
  done
fi
if [[ $DO_UPLOAD -eq 1 || $VALIDATE_ONLY -eq 1 ]] && [[ ! -f "$KEY_FILE" ]]; then
  echo "error: missing $KEY_FILE — download AuthKey_${ASC_KEY_ID}.p8 and put it there." >&2
  exit 1
fi

if [[ -z "$BUILD_NUMBER" ]]; then
  BUILD_NUMBER="$(git rev-list --count HEAD 2>/dev/null || date +%Y%m%d%H%M)"
fi

SETTINGS_OVERRIDES=("CURRENT_PROJECT_VERSION=$BUILD_NUMBER")
[[ -n "$MARKETING_VERSION" ]] && SETTINGS_OVERRIDES+=("MARKETING_VERSION=$MARKETING_VERSION")

mkdir -p "$OUT_DIR"

cat > "$OUT_DIR/ExportOptions.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>teamID</key>
	<string>RUD5LX7W3Y</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>uploadSymbols</key>
	<true/>
	<key>destination</key>
	<string>export</string>
</dict>
</plist>
PLIST

# ---- archive -----------------------------------------------------------------
if [[ $DO_ARCHIVE -eq 1 ]]; then
  echo "==> archiving (build $BUILD_NUMBER) — the Kotlin framework compiles in a build phase, this takes a while"
  rm -rf "$ARCHIVE"
  xcodebuild \
    -project "$XCODEPROJ" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination 'generic/platform=iOS' \
    -archivePath "$ARCHIVE" \
    -allowProvisioningUpdates \
    "${SETTINGS_OVERRIDES[@]}" \
    archive
else
  [[ -d "$ARCHIVE" ]] || { echo "error: no archive at $ARCHIVE" >&2; exit 1; }
fi

APP_PLIST="$ARCHIVE/Products/Applications/Weil.app/Info.plist"
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP_PLIST")"
SHORT_VERSION="$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$APP_PLIST")"
ARCHIVED_BUILD="$(/usr/libexec/PlistBuddy -c 'Print CFBundleVersion' "$APP_PLIST")"
echo "==> archived $BUNDLE_ID $SHORT_VERSION ($ARCHIVED_BUILD)"

# ---- export ------------------------------------------------------------------
echo "==> exporting App Store IPA"
rm -rf "$EXPORT_DIR"
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportOptionsPlist "$OUT_DIR/ExportOptions.plist" \
  -exportPath "$EXPORT_DIR" \
  -allowProvisioningUpdates

IPA="$(ls "$EXPORT_DIR"/*.ipa | head -1)"
echo "==> IPA: $IPA"

if [[ $DO_UPLOAD -eq 0 && $VALIDATE_ONLY -eq 0 ]]; then
  exit 0
fi

# ---- validate / upload -------------------------------------------------------
AUTH=(--apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID")

# altool exits 0 even when the package is rejected, so grep its own verdict.
run_altool() {
  local action="$1" log
  log="$OUT_DIR/altool-${action#--}.log"
  set +e
  xcrun altool "$action" -f "$IPA" -t ios "${AUTH[@]}" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  set -e
  if [[ $status -ne 0 ]] || grep -qE 'Failed to (validate|upload) package|UPLOAD FAILED|VERIFY FAILED' "$log"; then
    echo "error: altool $action failed (log: $log)" >&2
    exit 1
  fi
}

echo "==> validating with App Store Connect"
run_altool --validate-app

if [[ $VALIDATE_ONLY -eq 1 ]]; then
  echo "==> validation only, stopping here"
  exit 0
fi

echo "==> uploading to TestFlight"
run_altool --upload-app

echo
echo "==> uploaded $BUNDLE_ID $SHORT_VERSION ($ARCHIVED_BUILD)"
echo "    processing takes a few minutes; watch https://appstoreconnect.apple.com → TestFlight"
