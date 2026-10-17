# Local Android build tools

This project uses Java and the Android framework directly. No Gradle dependency
graph, bundled browser, Apple SDK, developer token, or account key is needed to
build it. The APK uses the TV's installed Android System WebView for Apple's
playback engine.

The downloaded build tools live in `.toolchain/` and are ignored by Git:

- Temurin JDK 21 for macOS ARM64, obtained from the [Adoptium binary API](https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse).
- [Official Android command-line tools](https://developer.android.com/studio#command-tools), Mac ARM64 build 15859902. Archive SHA-256: `835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e`.
- `platforms;android-35`, `build-tools;35.0.0`, and `platform-tools`, installed with Google's `sdkmanager`.

Build with `bash scripts/build-apk.sh`. The local signing certificate and password
are created in `.keys/` with restrictive permissions. Keep them if you want later
APKs to update the existing installation while preserving its account session.
They are excluded from the source archive.

The SDK binaries are build tools for the Mac, not dependencies shipped to the TV.
The APK has no native CPU libraries and supports both 32-bit and 64-bit devices
on Android 8/API 26 and newer.
