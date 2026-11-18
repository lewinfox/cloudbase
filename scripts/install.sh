#!/usr/bin/env bash
# Build Cloudbase and put it on a USB-connected Android phone.
#   ./scripts/install.sh                 release build (fast on slow phones)
#   VARIANT=debug ./scripts/install.sh
#   SERIAL=abc123 ./scripts/install.sh   pick a phone when several are plugged in
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=app.cloudbase
VARIANT=${VARIANT:-release}

amber=$'\033[38;5;214m'; red=$'\033[31m'; dim=$'\033[2m'; bold=$'\033[1m'; off=$'\033[0m'
say()  { printf '%s▸%s %s\n' "$amber" "$off" "$*"; }
fail() { printf '%s✗ %s%s\n' "$red" "$*" "$off" >&2; exit 1; }

# --- Java ------------------------------------------------------------------------------
command -v java >/dev/null || fail "Java not found. Install JDK 17 or newer (e.g. 'brew install openjdk@17' or 'sudo apt install openjdk-17-jdk')."
jv=$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)
[ "${jv:-0}" -ge 17 ] 2>/dev/null || fail "Java ${jv:-?} found; Android builds need 17 or newer."

# --- Android SDK -----------------------------------------------------------------------
SDK=$(./scripts/sdk.sh)
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB=$(command -v adb || true)
[ -n "$ADB" ] || fail "adb not found. Install Android platform-tools, or delete local.properties and run make sdk."

# --- Find the phone --------------------------------------------------------------------
"$ADB" start-server >/dev/null 2>&1 || true

list_devices() { "$ADB" devices | awk 'NR>1 && NF>=2 {print $1" "$2}'; }

if [ -z "${SERIAL:-}" ]; then
  waited=0
  while :; do
    ready=$(list_devices | awk '$2=="device" {print $1}')
    unauth=$(list_devices | awk '$2=="unauthorized" {print $1}')
    count=$(printf '%s' "$ready" | grep -c . || true)
    if [ "$count" -eq 1 ]; then SERIAL=$ready; break; fi
    if [ "$count" -gt 1 ]; then
      echo "Several phones connected:"; list_devices | sed 's/^/   /'
      fail "Pick one: SERIAL=<id> make install"
    fi
    if [ $waited -eq 0 ]; then
      if [ -n "$unauth" ]; then
        say "Phone found but not authorised. Unlock it and tap ${bold}Allow${off} on the USB debugging prompt."
      else
        say "Waiting for a phone over USB…"
        printf '%s' "$dim"
        cat <<'HELP'
    On the phone:
      1. Settings → About phone → tap "Build number" 7 times (unlocks Developer options)
      2. Settings → System → Developer options → turn on "USB debugging"
      3. Plug in with a data cable (some cheap cables only charge) and tap Allow
HELP
        printf '%s' "$off"
      fi
    fi
    waited=$((waited + 2))
    [ $waited -ge 90 ] && fail "No phone showed up. Check the cable and that USB debugging is on."
    sleep 2
  done
fi

adb_s() { "$ADB" -s "$SERIAL" "$@"; }
prop() { adb_s shell getprop "$1" 2>/dev/null | tr -d '\r'; }
model="$(prop ro.product.manufacturer) $(prop ro.product.model)"
release=$(prop ro.build.version.release)
api=$(prop ro.build.version.sdk)
go=""; [ "$(prop ro.config.low_ram)" = "true" ] && go=" (Go edition)"
say "Phone: ${bold}${model}${off}, Android ${release}${go}, API ${api}"
[ "${api:-0}" -ge 21 ] || fail "Cloudbase needs Android 5.0 (API 21) or newer."

# --- Build -----------------------------------------------------------------------------
first=$(printf '%s' "${VARIANT:0:1}" | tr '[:lower:]' '[:upper:]')
say "Building ${VARIANT}…"
./gradlew --quiet "assemble${first}${VARIANT:1}"
APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ -f "$APK" ] || fail "Build finished but $APK is missing."
say "Built $APK ($(du -k "$APK" | cut -f1) KB)"

# --- Install ---------------------------------------------------------------------------
grant=""; [ "$api" -ge 23 ] && grant="-g"   # pre-grant Bluetooth and notification permissions
say "Installing (slow phones can take a minute)…"
if ! out=$(adb_s install -r $grant "$APK" 2>&1); then
  echo "$out" >&2
  case "$out" in
    *UPDATE_INCOMPATIBLE*|*SIGNATURES*) fail "A Cloudbase signed with a different key is already installed. Run: make uninstall" ;;
    *INSUFFICIENT_STORAGE*) fail "The phone is out of storage." ;;
    *) fail "Install failed." ;;
  esac
fi

# It runs unattended in the car, so stop Android from putting it to sleep.
if [ "$api" -ge 23 ]; then
  adb_s shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 && say "Exempted from battery optimisation"
fi
adb_s shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
printf '%s✓ Cloudbase is on the phone.%s\n' "$amber" "$off"
