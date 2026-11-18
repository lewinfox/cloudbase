#!/usr/bin/env bash
# Make sure local.properties points at an Android SDK, downloading one into .android-sdk/ if needed.
set -euo pipefail
cd "$(dirname "$0")/.."

PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"

have_sdk() { [ -n "$1" ] && [ -d "$1/platforms" ]; }

sdk=""
[ -f local.properties ] && sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
found=""
for candidate in "$sdk" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$PWD/.android-sdk"; do
  if have_sdk "$candidate"; then found=$candidate; break; fi
done
sdk=$found

if [ -z "$sdk" ]; then
  sdk="$PWD/.android-sdk"
  case "$(uname -s)" in
    Darwin) os=mac ;;
    Linux) os=linux ;;
    *) echo "Unsupported OS for automatic SDK download; install Android Studio instead." >&2; exit 1 ;;
  esac
  echo "▸ No Android SDK found. Downloading command-line tools into .android-sdk/ (one-off, ~600 MB)…" >&2
  zip=$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml \
        | grep -o "commandlinetools-$os-[0-9]*_latest.zip" | sort -t- -k3 -n | tail -1)
  mkdir -p "$sdk/cmdline-tools"
  curl -fsSL -o "$sdk/cmdline-tools/tools.zip" "https://dl.google.com/android/repository/$zip"
  (cd "$sdk/cmdline-tools" && unzip -q tools.zip && mv cmdline-tools latest)
fi

sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$sdkmanager" ] && { [ ! -d "$sdk/platforms/android-35" ] || [ ! -x "$sdk/platform-tools/adb" ]; }; then
  echo "▸ Installing Android platform, build tools and adb…" >&2
  yes | "$sdkmanager" --sdk_root="$sdk" --licenses >/dev/null
  "$sdkmanager" --sdk_root="$sdk" "$PLATFORM" "$BUILD_TOOLS" "platform-tools" >/dev/null
fi

if [ ! -x "$sdk/platform-tools/adb" ] && [ ! -x "$sdkmanager" ]; then
  echo "▸ Note: this SDK has no adb and no sdkmanager; install 'platform-tools' to use make install." >&2
fi

echo "sdk.dir=$sdk" > local.properties
echo "$sdk"
