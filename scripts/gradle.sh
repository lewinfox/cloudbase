#!/usr/bin/env bash
# Run ./gradlew inside a container (docker/Dockerfile) so the host needs no Java or
# Android SDK. Gradle's caches live in .gradle-home/ in this project.
#   ./scripts/gradle.sh :app:assembleRelease
#   AIRSPACE_SAMPLES=~/airspace ./scripts/gradle.sh :core:test   (mounted read-only)
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=cloudbase-build
engine=${CONTAINER_ENGINE:-$(command -v docker || command -v podman || true)}
[ -n "$engine" ] || { echo "✗ Needs docker or podman." >&2; exit 1; }

if ! "$engine" image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "▸ Building the build container (one-off, a few minutes)…" >&2
fi
"$engine" build -q -t "$IMAGE" docker/ >/dev/null

# An sdk.dir here (left by the old host-SDK setup) would override the container's SDK.
[ -f local.properties ] && sed -i '/^sdk\.dir=/d' local.properties

if [ "$(basename "$engine")" = podman ]; then
  user=(--userns=keep-id)
else
  user=(--user "$(id -u):$(id -g)")
fi
tty=(); [ -t 0 ] && [ -t 1 ] && tty=(-it)

# Airspace sample files live on the host; mount them at the same path inside.
samples=()
if [ -n "${AIRSPACE_SAMPLES:-}" ]; then
  dir=$(cd "$AIRSPACE_SAMPLES" && pwd)
  samples=(-v "$dir:$dir:ro" -e "AIRSPACE_SAMPLES=$dir")
fi

# The host uid has no passwd entry in the container, so Java's user.home would be "?" and
# Robolectric (which caches its Android jars there) fails. Point every JVM at a real dir.
mkdir -p .gradle-home/home .gradle-home/android
exec "$engine" run --rm "${tty[@]}" "${user[@]}" "${samples[@]}" \
  -v "$PWD:/work" -w /work \
  -e HOME=/work/.gradle-home/home -e ANDROID_USER_HOME=/work/.gradle-home/android \
  -e GRADLE_USER_HOME=/work/.gradle-home \
  -e JAVA_TOOL_OPTIONS=-Duser.home=/work/.gradle-home/home \
  "$IMAGE" ./gradlew "$@"
