# Blexsy Android

A Spotify-style music player for Android, built with Kotlin and Jetpack Compose.

## Requirements

- JDK 17+ (Android Studio's bundled JBR works too)
- Android SDK Platform 37
- Android Studio, or just the Gradle wrapper in this repo

If your Android SDK is not in the default location, point to it from `local.properties`:

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

## Project structure

```
app/
  src/main/java/com/blek/blexsy/   Compose UI, data layer, player
  src/main/res/                    Android resources (Poppins fonts, icons, strings)
  src/main/AndroidManifest.xml
  src/test/java/com/blek/blexsy/   JVM unit tests
gradle/libs.versions.toml          Version catalog
```

The app is a single Android application module (`:app`). Compose UI, networking, models,
and playback all live in that module; the audio player is backed by Media3 ExoPlayer.

## Build and run

```bash
# Build the debug APK
./gradlew :app:assembleDebug

# Install on a connected device/emulator
./gradlew :app:installDebug

# Run unit tests
./gradlew :app:testDebugUnitTest
```

On Windows use `gradlew.bat` instead of `./gradlew`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. You can also open
the project in Android Studio and run the `app` configuration.

## Stack

- Kotlin 2.4 with AGP 9.4 built-in Kotlin support, Gradle 9.8
- Jetpack Compose (BOM 2026.09.00) and Material 3
- Media3 ExoPlayer / MediaSession for playback and notifications
- Ktor + kotlinx.serialization for the API, Coil 3 for artwork
- Poppins (bundled under `app/src/main/res/font`), licensed under the SIL Open Font License
  (`app/src/main/assets/Poppins-OFL.txt`)
