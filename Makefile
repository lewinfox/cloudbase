# Cloudbase. Run `make` for the list.
.DEFAULT_GOAL := help

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
	@echo "make sdk            find or download the Android SDK"

install:
	@./scripts/install.sh

install-debug:
	@VARIANT=debug ./scripts/install.sh

build: sdk
	./gradlew :app:assembleRelease

test: sdk
	./gradlew :core:test :app:testDebugUnitTest

screenshots: sdk
	./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
	@echo "→ docs/screenshots/"


sdk:
	@./scripts/sdk.sh >/dev/null

ADB = "$(shell ./scripts/sdk.sh 2>/dev/null)/platform-tools/adb"

run:
	$(ADB) shell am start -n app.cloudbase/.MainActivity

logs:
	$(ADB) logcat --pid=$$($(ADB) shell pidof -s app.cloudbase | tr -d '\r')

uninstall:
	$(ADB) uninstall app.cloudbase

devices:
	$(ADB) devices -l

clean:
	./gradlew clean
