# BlueTube

**BlueTube** is an Android app for watching online video, designed for **Android TV** (remote control) and **Android phones/tablets** (touch, portrait and landscape).

## Features
- Android TV interface with sidebar, rows and a full-screen player.
- Mobile mode (automatic): vertical home feed, search with suggestions, YouTube-like watch page with suggested videos, picture-in-picture, full screen with lock, and a "Me" page (accounts, settings).
- Optional background playback (audio keeps playing with the screen locked).
- Spanish by default, 24-hour clock, sleep timer, PIN to protect the settings.

## Build
Requirements: JDK 17, Android SDK (platform 34, build-tools 30.0.3, NDK 21.0.6113669).

```bash
git submodule update --init --recursive
./gradlew assembleStbetaDebug      # APKs in smarttubetv/build/outputs/apk/stbeta/debug/
```

## Credits and license
BlueTube is based on the open-source project **SmartTube** by Yuriy Liskov (MIT License). The original copyright and license notice is kept in [LICENSE](LICENSE), as the license requires.
