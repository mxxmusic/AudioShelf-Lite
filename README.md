# AudioShelf Lite

AudioShelf Lite is a lightweight Android client for a local Audiobookshelf server. It is designed as a simple child-friendly audiobook player for older Android devices, especially Android 6 era phones.

## Features

- Login to a self-hosted Audiobookshelf server
- Browse libraries, books, and audio chapters
- Stream audio from Audiobookshelf over the local network
- Play, pause, previous chapter, and next chapter controls
- Continue listening from the latest local playback position
- Sync playback progress back to Audiobookshelf playback sessions
- Sleep timer: off by default, then 15 / 20 / 25 / 30 minutes
- Portrait-only layout to avoid playback interruption on device rotation
- Dark theme for night listening
- Simple setting protection: tap Settings three times to open it
- Single-activity Java implementation without third-party runtime dependencies

## Target Runtime

The app is designed for:

- Android 6.0.1 or newer
- Xiaomi Mi 4 / MIUI 8 class devices
- Local Audiobookshelf server over HTTP or HTTPS

The current default server address in the app is:

```text
http://192.168.3.102:13378
```

You can change it from the app settings. Tap `设置` three times quickly to show the login/settings panel.

## Project Structure

```text
.
|-- app/
|   |-- build.gradle
|   `-- src/main/
|       |-- AndroidManifest.xml
|       |-- java/com/local/audiobookshelfclient/MainActivity.java
|       `-- res/
|-- build.gradle
|-- settings.gradle
`-- README.md
```

## Build Requirements

This project currently uses:

- JDK 17
- Android Gradle Plugin 8.7.3
- Gradle 8.10.2
- Android SDK platform 35
- Minimum SDK 23

On the original development machine:

```text
JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot
ANDROID_HOME=C:\Users\mxxmu\AppData\Local\Android\Sdk
Gradle=C:\tools\gradle-8.10.2\bin\gradle
```

## Build

From the project root:

```powershell
gradle assembleDebug
```

The debug APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install On Xiaomi Mi 4

MIUI may block direct `adb install`. The working approach is to push the APK and install it manually from the phone file manager:

```powershell
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/AudioShelfLite.apk
adb shell ls -l /sdcard/Download/AudioShelfLite.apk
```

Then open this file on the phone:

```text
Download/AudioShelfLite.apk
```

## App Controls

- `书架`: load the Audiobookshelf libraries
- `继续听`: resume the latest local playback position, falling back to Audiobookshelf progress when needed
- `定时`: sleep timer, cycling through off, 15, 20, 25, and 30 minutes
- `设置`: tap three times quickly to show or hide the login/settings panel
- `≪`: previous chapter in the active chapter list
- `▶ / II`: play or pause
- `≫`: next chapter in the active chapter list

## Audiobookshelf API Usage

The app uses these Audiobookshelf endpoints:

```text
POST /login
GET  /api/libraries
GET  /api/libraries/{libraryId}/items?limit=100&sort=media.metadata.title
GET  /api/items/{itemId}
GET  /api/items/{itemId}/file/{ino}
POST /api/items/{itemId}/play
POST /api/session/{sessionId}/sync
POST /api/session/{sessionId}/close
GET  /api/me
```

Progress sync is implemented through Audiobookshelf playback sessions. The app creates a session when playback starts, syncs progress periodically while playing, and closes the session when playback stops or changes chapters.

## Notes

- Cleartext HTTP is intentionally allowed for local LAN Audiobookshelf servers.
- The activity is locked to portrait to avoid playback interruption on old Android devices when the phone rotates.
- The launcher icon uses vector resources for Android 6 and adaptive icon resources for newer Android versions.

## License

MIT
