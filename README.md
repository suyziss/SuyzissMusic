# Suyziss Music

Android music player built with Kotlin, Jetpack Compose, Material 3 and AndroidX Media3.

## Current implementation
- Local audio library scan through MediaStore with runtime permission.
- Audio import through Android Storage Access Framework, including audio selected from Telegram.
- Media3 playback service and media-session integration.
- Library, Telegram import, Studio and Settings tabs.
- Persistent system/light/dark theme, purple/blue/green/pink accent choices, Android 12+ dynamic wallpaper colors, Russian and English UI.

## Planned / not yet implemented
- Full Now Playing screen, playback queue, seek bar and album art.
- Functional audio-session equalizer and visualizer.
- Embedded lyrics and timed LRC synchronization.
- Playlists, sorting, search and richer metadata.
- Automated tests and release signing.

## Build
Requires JDK 17, Android SDK 36 and Gradle 8.11.1.

```bash
gradle --no-daemon assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds a debug APK on pushes to main/master and on manual workflow dispatch. Download the `SuyzissMusic-debug-apk` artifact from the workflow run.

Package: `com.suyziss.music`
