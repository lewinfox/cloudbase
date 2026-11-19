# Cloudbase. Run `make` for the list.
.DEFAULT_GOAL := help
.PHONY: help install install-debug build test screenshots run logs uninstall devices image clean

help:
	@echo "make install        build a release APK and install it on the USB-connected phone"
	@echo "make install-debug  same, debug build (slower on the phone, but debuggable)"
	@echo "make build          just build app/build/outputs/apk/release/app-release.apk"
	@echo "make test           unit tests (no phone needed)"
	@echo "make screenshots    render every screen with a simulated flight to docs/screenshots/"
	@echo "make run            launch the app on the phone"
	@echo "make logs           stream the app's log from the phone"
	@echo "make uninstall      remove the app from the phone"
	@echo "make devices        list connected phones"
	@echo "make image          build the container that runs Gradle (Java + Android SDK)"
	@echo "Builds run in docker/podman; the host only needs adb."

install:
	@./scripts/install.sh

install-debug:
	@VARIANT=debug ./scripts/install.sh

build:
	./scripts/gradle.sh :app:assembleRelease

test:
	./scripts/gradle.sh :core:test :app:testDebugUnitTest

screenshots:
	./scripts/gradle.sh :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
	@echo "→ docs/screenshots/"

image:
	./scripts/gradle.sh --version

ADB = "$(shell ./scripts/adb.sh)"

run:
	$(ADB) shell am start -n app.cloudbase/.MainActivity

logs:
	$(ADB) logcat --pid=$$($(ADB) shell pidof -s app.cloudbase | tr -d '\r')

uninstall:
	$(ADB) uninstall app.cloudbase

devices:
	$(ADB) devices -l

clean:
	./scripts/gradle.sh clean
