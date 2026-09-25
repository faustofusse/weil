#!/usr/bin/env bash
# Builds a signed release App Bundle and publishes it to the Google Play
# "internal" testing track through the Android Publisher API.
#
# Usage: scripts/android-play-internal.sh [options]
#   --build N         versionCode. Default: git commit count (same as iOS).
#   --version X.Y.Z   versionName. Default: $(cat VERSION).<build>, same as iOS.
#   --track NAME      internal (default) | alpha | beta | production.
#   --draft           create the release as a draft (required while the app
#                     has never been published: Play rejects "completed").
#   --notes TEXT      release notes ($PLAY_NOTES_LANG, default es-419 — must be
#                     a language of the store listing). Default: last commit.
#   --no-upload       build + sign only, print the AAB path.
#   --skip-build      reuse the existing AAB (upload only).
#   --init-keystore   generate the upload keystore and exit.
#
# Secrets live in secrets/android/ (gitignored — never commit them):
#   weil-upload.jks, weil-upload.password  upload key, alias "upload"
#   play-service-account.json              Google Cloud service account invited
#                                          in Play Console with release rights
# Override with $ANDROID_UPLOAD_DIR / $PLAY_SERVICE_ACCOUNT_JSON.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PACKAGE="finance.fausto.ar"
AAB="$REPO_ROOT/app/androidApp/build/outputs/bundle/release/androidApp-release.aab"
OUT_DIR="$REPO_ROOT/app/androidApp/build/distribution"

SECRETS_DIR="$REPO_ROOT/secrets/android"
UPLOAD_DIR="${ANDROID_UPLOAD_DIR:-$SECRETS_DIR}"
KEYSTORE="$UPLOAD_DIR/weil-upload.jks"
PASSWORD_FILE="$UPLOAD_DIR/weil-upload.password"
KEY_ALIAS="upload"
SA_JSON="${PLAY_SERVICE_ACCOUNT_JSON:-$SECRETS_DIR/play-service-account.json}"

BUILD_NUMBER=""
VERSION_NAME=""
TRACK="internal"
STATUS="completed"
NOTES=""
DO_UPLOAD=1
DO_BUILD=1
INIT_KEYSTORE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) BUILD_NUMBER="$2"; shift 2 ;;
    --version) VERSION_NAME="$2"; shift 2 ;;
    --track) TRACK="$2"; shift 2 ;;
    --draft) STATUS="draft"; shift ;;
    --notes) NOTES="$2"; shift 2 ;;
    --no-upload) DO_UPLOAD=0; shift ;;
    --skip-build) DO_BUILD=0; shift ;;
    --init-keystore) INIT_KEYSTORE=1; shift ;;
    -h|--help) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# ---- upload keystore ---------------------------------------------------------
if [[ $INIT_KEYSTORE -eq 1 ]]; then
  [[ -f "$KEYSTORE" ]] && { echo "error: $KEYSTORE already exists" >&2; exit 1; }
  mkdir -p "$UPLOAD_DIR"; chmod 700 "$UPLOAD_DIR"
  openssl rand -base64 24 | tr -d '/+=' > "$PASSWORD_FILE"
  chmod 600 "$PASSWORD_FILE"
  keytool -genkeypair -v -storetype PKCS12 \
    -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$(cat "$PASSWORD_FILE")" \
    -dname "CN=Weil upload, O=fausto.ar, C=AR"
  chmod 600 "$KEYSTORE"
  echo "==> created $KEYSTORE (password in $PASSWORD_FILE) — back both up."
  echo "    upload certificate SHA-256:"
  keytool -list -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -storepass "$(cat "$PASSWORD_FILE")" \
    | grep -i 'SHA-256' || true
  exit 0
fi

if [[ -z "$BUILD_NUMBER" ]]; then
  BUILD_NUMBER="$(git rev-list --count HEAD 2>/dev/null || date +%y%m%d%H)"
fi
[[ -z "$VERSION_NAME" ]] && VERSION_NAME="$(tr -d '[:space:]' < VERSION).$BUILD_NUMBER"
[[ -z "$NOTES" ]] && NOTES="$(git log -1 --format=%s 2>/dev/null || echo "Build $BUILD_NUMBER")"

# ---- build -------------------------------------------------------------------
if [[ $DO_BUILD -eq 1 ]]; then
  if [[ ! -f "$KEYSTORE" || ! -f "$PASSWORD_FILE" ]]; then
    echo "error: no upload keystore at $KEYSTORE — run: $0 --init-keystore" >&2
    exit 1
  fi
  echo "==> building release bundle $VERSION_NAME ($BUILD_NUMBER)"
  rm -f "$AAB"
  ORG_GRADLE_PROJECT_weilUploadStoreFile="$KEYSTORE" \
  ORG_GRADLE_PROJECT_weilUploadStorePassword="$(cat "$PASSWORD_FILE")" \
  ORG_GRADLE_PROJECT_weilUploadKeyAlias="$KEY_ALIAS" \
  ORG_GRADLE_PROJECT_weilVersionCode="$BUILD_NUMBER" \
  ORG_GRADLE_PROJECT_weilVersionName="$VERSION_NAME" \
    ./gradlew :app:androidApp:bundleRelease
fi
[[ -f "$AAB" ]] || { echo "error: no bundle at $AAB" >&2; exit 1; }

# An unsigned or debug-signed bundle is rejected by Play after the upload; fail here instead.
if ! keytool -printcert -jarfile "$AAB" 2>/dev/null | grep -q 'CN=Weil upload'; then
  echo "error: $AAB is not signed with the upload key" >&2
  exit 1
fi
echo "==> AAB: $AAB"

if [[ $DO_UPLOAD -eq 0 ]]; then
  exit 0
fi

# ---- OAuth token (service account JWT, RS256 via openssl) --------------------
[[ -f "$SA_JSON" ]] || { echo "error: missing service account JSON at $SA_JSON" >&2; exit 1; }
mkdir -p "$OUT_DIR"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

SA_EMAIL="$(jq -r .client_email "$SA_JSON")"
NOW="$(date +%s)"
HEADER="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
CLAIMS="$(jq -cn --arg iss "$SA_EMAIL" --argjson now "$NOW" '{
  iss: $iss,
  scope: "https://www.googleapis.com/auth/androidpublisher",
  aud: "https://oauth2.googleapis.com/token",
  iat: $now, exp: ($now + 3600)
}' | b64url)"
KEY_PEM="$(mktemp)"; trap 'rm -f "$KEY_PEM"' EXIT
jq -r .private_key "$SA_JSON" > "$KEY_PEM"
SIGNATURE="$(printf '%s.%s' "$HEADER" "$CLAIMS" | openssl dgst -sha256 -sign "$KEY_PEM" | b64url)"
JWT="$HEADER.$CLAIMS.$SIGNATURE"

TOKEN="$(curl -sS https://oauth2.googleapis.com/token \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JWT" | jq -r '.access_token // empty')"
[[ -n "$TOKEN" ]] || { echo "error: could not get an access token for $SA_EMAIL" >&2; exit 1; }

API="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PACKAGE"
UPLOAD_API="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PACKAGE"

# curl wrapper: prints the body, fails loudly with Google's error message.
call() {
  local out status
  out="$(mktemp)"
  status="$(curl -sS -o "$out" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$@")"
  if [[ "$status" != 2* ]]; then
    echo "error: HTTP $status from ${*: -1}" >&2
    jq -r '.error.message // .' "$out" >&2 2>/dev/null || cat "$out" >&2
    rm -f "$out"; exit 1
  fi
  cat "$out"; rm -f "$out"
}

# ---- edit → upload → track → commit ------------------------------------------
echo "==> opening edit"
EDIT_ID="$(call -X POST -H 'Content-Type: application/json' -d '{}' "$API/edits" | jq -r .id)"

echo "==> uploading bundle (versionCode $BUILD_NUMBER)"
UPLOADED="$(call -X POST -H 'Content-Type: application/octet-stream' \
  --data-binary "@$AAB" "$UPLOAD_API/edits/$EDIT_ID/bundles?uploadType=media" | jq -r .versionCode)"

echo "==> assigning to track '$TRACK' ($STATUS)"
TRACK_BODY="$(jq -cn --arg track "$TRACK" --arg name "$VERSION_NAME ($UPLOADED)" \
  --arg code "$UPLOADED" --arg status "$STATUS" --arg notes "$NOTES" --arg lang "${PLAY_NOTES_LANG:-es-419}" '{
  track: $track,
  releases: [{
    name: $name, versionCodes: [$code], status: $status,
    releaseNotes: [{language: $lang, text: $notes[0:500]}]
  }]
}')"
call -X PUT -H 'Content-Type: application/json' -d "$TRACK_BODY" \
  "$API/edits/$EDIT_ID/tracks/$TRACK" > /dev/null

echo "==> committing edit"
call -X POST "$API/edits/$EDIT_ID:commit" > /dev/null

echo
echo "==> published $PACKAGE $VERSION_NAME ($UPLOADED) to $TRACK"
echo "    https://play.google.com/console → Testing → Internal testing"
