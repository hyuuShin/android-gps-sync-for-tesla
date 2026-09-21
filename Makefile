.PHONY: help android-build android-check android-install android-ui-test
ADB ?= adb

help:
	@echo 'Android GPS Sync for Tesla: see README.md'
	@echo 'android-build android-check android-install android-ui-test'

android-build:
	./android/gradlew -p android :app:assembleDebug

android-check:
	./android/gradlew -p android :app:testDebugUnitTest :app:lintDebug

android-install: android-build
	$(ADB) install -r android/app/build/outputs/apk/debug/app-debug.apk

# Direct instrumentation leaves the application and its data installed.
android-ui-test:
	./android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest
	$(ADB) install -r android/app/build/outputs/apk/debug/app-debug.apk
	$(ADB) install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am instrument -w dev.gpssync.for_tesla_probe.test/androidx.test.runner.AndroidJUnitRunner
