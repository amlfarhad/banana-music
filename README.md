# Banana Music

A personal, unofficial Apple Music client for Android TV. Banana Music 0.2.1 uses
native Android views and Apple's music APIs for browsing. Apple's configured
MusicKit player in a covered System WebView supplies sign-in and subscription audio.

APK: `downloads/Banana-Music-0.2.1.apk`. Installation: [INSTALL.md](INSTALL.md).
Build instructions: [TOOLCHAIN.md](TOOLCHAIN.md).

## Features

- Native Home, New, Radio, search, playlist library, saved albums and song lists.
- Complete playlist pagination: one continuous, recycled song list, without a
  50-song cap, suggested-song rows or contributors mixed into the playlist.
- Private disk metadata cache, background section/playlist preloading, immediate
  cached navigation, and retained selection while switching screens.
- Bounded shelves create only visible cards. Artwork has an 8 MiB bitmap cache
  and a 24 MiB disk cache, including Apple's custom playlist image host.
- Play/pause, previous/next, shuffle, repeat off/all/one, Now Playing and seeking.
- Dark glass-style panels, rose focus outlines and short native focus transforms;
  no live backdrop blur or continuously running decoration.
- High quality requests Apple's 256 kbps AAC target; Data Saver requests 64 kbps.
- Existing app identity and signing key preserve the owner's Apple session.
- Android 8/API26+, landscape TV launcher; no native CPU libraries or app frameworks.

## Practical limits

Cached screens open locally; a new, uncached collection still needs a network
request. Authentication/audio startup also depends on Apple and Android WebView.
Apple's APIs and exposed player can change. This is not an official Apple app.

Sign-in stays in private WebView storage when the app closes or updates, but Apple
can expire or revoke its session. QR/passkey sign-in is not implemented. The app
does not read or copy passwords, subscriber tokens, developer tokens or cookies.

This audio path has no documented lossless or Atmos setting. The quality choice
is a requested target, not a measured delivered bitrate. Playback pauses when
leaving the app. Offline downloads and lyrics are not implemented.

## Build

Run `bash scripts/build-apk.sh`. Preserve the ignored `.keys/` directory to sign
future updates. `--diagnostic` creates a local WebView-debuggable build; the
normal delivered build disables WebView debugging. Private account diagnostics,
images, toolchains and signing material are excluded from the source archive.

## Repository snapshot

This repository starts from the existing app source as of October 16, 2026.
Local development history, account data, signing keys, private diagnostics, and
unrelated projects are excluded. Historical validation notes describe earlier
local runs; local rollback tags and artifacts are not included here.
