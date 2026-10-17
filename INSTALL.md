# Install on the ONN Full HD stick

1. Transfer **Banana-Music-0.2.1.apk** to the TV using your preferred file transfer or
   file manager app. Open the file on the TV.
2. If Android asks, allow that file app to install unknown apps in the TV's
   settings, then finish installation.
3. Open **Banana Music** from the TV launcher. Choose **Apple account** and sign in
   with the Apple account that has your active Music subscription. Complete any
   Apple two-factor prompt yourself.
4. On Apple's account screen, D-pad moves the pointer and OK selects. Use the TV
   keyboard for typing. Press Back when sign-in finishes to return to the native UI.
5. Open an album or song and check that playback continues **past 30 seconds**.
   Then check Library and your playlists. This distinguishes subscription
   playback from Apple's public previews.

**Settings → Audio quality** defaults to High (prefer 256 kbps AAC). It takes
effect on the next item. **Settings → Storefront / country** should match your
Apple Music account country if catalog availability differs.

## Transfer from this Mac over the same Wi-Fi

If your TV has a downloader that accepts a URL, you can serve only the output
folder temporarily. In Terminal from the project folder:

```sh
cd banana-music
python3 scripts/serve-apk.py --bind YOUR_MAC_IP --port 8765
```

Find the Mac's local IP in System Settings → Network/Wi-Fi details. On the TV,
open `http://YOUR_MAC_IP:8765/app.apk`. Keep both devices on the same
network. Stop the server with Ctrl-C after transfer. No signing keys or account
files are in the served directory.

## Controls

- D-pad: native navigation; OK: open or play; Back: previous screen.
- Bottom transport bar: shuffle, previous, play/pause, next, repeat.
  Shuffle lights rose when enabled. Repeat cycles off → all → one; one shows a 1.
- Now Playing: artwork and a seek bar; left/right on the seek bar changes time.
- Apple account screen: pointer mode, with hold-OK switching pointer/focus mode.
- Settings: quality, playback support, reload, account, country and remote help.

## If playback needs attention

Open **Settings → Playback support**. It reports the player, WebView and protected
media capabilities. These are diagnostics rather than a subscription certification.
Update Android System WebView on the TV if an update is available, check the
Apple sign-in, and try a different song. If only previews play, recheck the
subscription account and country. The optional Apple Player view in Settings can
help distinguish an adapter issue from a playback issue in Apple's own website.

The build is signed with a personal certificate and supports Android 8 or newer.
Its version is **0.2.1**, package **dev.amal.aural**. Future APKs built with the same
local key can update it without wiping its session. Playback stops when you leave
the app; lossless and Atmos are not offered by this version.

Install updates over the existing app. Keep package data and the signing key to
retain the saved sign-in. Closing the app does not sign out; clearing app data,
uninstalling, or choosing Sign out removes its session. Apple may independently
require reauthentication.
