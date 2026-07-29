# Elitecaller — Android WebView App

A production-ready native Android WebView wrapper for **https://vishal-flask-app.onrender.com**, built in Java with AndroidX, targeting Android 16 (API 36) while supporting devices back to Android 6.0 (API 23).

## Project facts
- **Package name:** `com.elitecaller.app`
- **Language:** Java
- **minSdk:** 23  **targetSdk / compileSdk:** 36
- **Build system:** Gradle 8.11.1 + Android Gradle Plugin 8.9.1

## Features implemented
Full-screen WebView · JavaScript & DOM storage · file upload (`<input type=file>`) · file downloads via `DownloadManager` · camera/microphone permission bridging (`getUserMedia`) · geolocation permission bridging · external links opened in the system browser · HTTPS/SSL via a network security config · pull-to-refresh · top loading progress bar · splash screen (AndroidX SplashScreen) · custom adaptive app icon · live internet connectivity detection with an offline retry screen · WebView back-history navigation + double-back-to-exit · full light/dark theme support · portrait & landscape support.

## Opening in Android Studio
1. Unzip the project and open the root folder in Android Studio (Ladybug or newer recommended).
2. Copy `local.properties.template` to `local.properties` and set `sdk.dir` to your local Android SDK path (Android Studio usually does this automatically on first sync).
3. If your local checkout is missing `gradle/wrapper/gradle-wrapper.jar` (see note below), run `gradle wrapper --gradle-version 8.11.1` once from a terminal with Gradle installed, or simply let Android Studio's "Sync Project with Gradle Files" regenerate it.
4. Sync Gradle and run the `app` configuration.

## Building an APK via GitHub Actions
Push this repository to GitHub as-is. The included workflow at `.github/workflows/build.yml` runs automatically on every push/PR to `main`/`master` (and can also be triggered manually from the **Actions** tab) and will:
1. Set up JDK 17 and the Android SDK (API 36 platform + build-tools 36.0.0).
2. Provision Gradle 8.11.1 and regenerate a valid `gradlew` / `gradle-wrapper.jar` automatically — no manual step needed.
3. Build both `assembleDebug` and `assembleRelease`.
4. Upload both APKs as downloadable workflow artifacts (**elitecaller-debug-apk**, **elitecaller-release-apk**).

## Signing the release APK
Without extra configuration, the release build automatically falls back to debug signing so CI always produces an installable APK. To produce a properly store-signed release APK, either:
- Copy `keystore.properties.example` to `keystore.properties`, fill in your real keystore details, and place your `.jks` file as referenced — **do not commit these**; or
- Set the `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables (e.g. as GitHub Actions secrets passed into the job's `env:`).

## Note on the Gradle Wrapper JAR
This repository intentionally does **not** ship a `gradle/wrapper/gradle-wrapper.jar`. GitHub's wrapper-validation tooling checks that file's checksum against Gradle's list of known-good official releases, and any hand-built or third-party-compiled jar — even a fully functional one — will be rejected as "unknown." To guarantee a 100% genuine, validation-passing wrapper, the GitHub Actions workflow downloads the real Gradle 8.11.1 distribution directly from `services.gradle.org` and runs its own `gradle wrapper` task, which writes an authentic, byte-identical `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar` before the build starts. No manual steps required.

If you want a working `gradlew` locally (outside Android Studio), run this once in the project root with any Gradle install (including the one bundled with Android Studio, under `Android Studio.app/Contents/gradle` or `%LOCALAPPDATA%\Google\AndroidStudio*\gradle`):
```
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```
Android Studio itself does **not** need this file to open or sync the project — it reads `gradle-wrapper.properties` directly and manages its own Gradle install via the IDE's built-in tooling.
