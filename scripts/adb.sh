#!/usr/bin/env bash
# Print the path to adb: the one on PATH, an existing SDK's, or download platform-tools
# (~10 MB) into .android-sdk/. adb is the only Android tool the host needs.
set -euo pipefail
cd "$(dirname "$0")/.."

for candidate in "$(command -v adb || true)" \
    "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "$PWD/.android-sdk/platform-tools/adb"; do
  [ -n "$candidate" ] && [ -x "$candidate" ] && { echo "$candidate"; exit 0; }
done

case "$(uname -s)" in
  Darwin) os=darwin ;;
  Linux) os=linux ;;
  *) echo "✗ Install Android platform-tools (adb) yourself on this OS." >&2; exit 1 ;;
esac
echo "▸ Downloading adb into .android-sdk/ (one-off)…" >&2
mkdir -p .android-sdk
curl -fsSL -o .android-sdk/pt.zip "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
(cd .android-sdk && unzip -q -o pt.zip && rm pt.zip)
echo "$PWD/.android-sdk/platform-tools/adb"
