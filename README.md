# Cosmos 90GT Controller (single APK, Java, Android SDK only)

A minimal Android remote for a Celestron Cosmos 90GT telescope over its SkyQ WiFi module
(TCP, the AUX protocol in plaintext) or a CP210x / FTDI USB-serial adapter. Plain Java,
no Gradle, no third-party libraries: the build is one script, or a GitHub Actions workflow.

## Build on GitHub (no tools needed locally)
The workflow in `.github/workflows/build-apk.yml` runs on every push to `main`, runs the unit
tests, builds and signs the APK, and uploads it: Actions tab > latest run > Artifacts >
`cosmos90gt-apk` (a zip containing the APK). Push a tag such as `v0.1` and the APK is also
attached to a GitHub Release (a stable download link). The Actions tab also has a manual
"Run workflow" button.

Signing: the workflow restores the signing keystore from the repo secret
`COSMOS90GT_KEYSTORE_B64` (base64 of the keystore file). Without that secret the build
fails on purpose rather than silently signing with a random throwaway key. The keystore is
deliberately not committed, so the signing key never becomes public.

## Build locally
`bash build.sh` (JDK + aapt + dx/d8 + zipalign + apksigner + android.jar API 23+). Output:
`build/cosmos90gt.apk`. Install: copy the APK to the phone, open it, allow "install unknown
apps". minSdk 23, targetSdk 29. Unit tests for the AUX layer (plain JVM): `test/AuxTest.java`.

## Layout
- `Aux` / `AuxParser` / `HexLog` : AUX frame builder, checksum-validating stream parser,
  timestamped TX/RX hex log (pure Java, unit-testable on a PC)
- `LinkManager` : connection thread, auto-reconnect with backoff (1,2,4,8,15 s), all I/O off
  the main thread
- `WifiTransport` : TCP 1.2.3.4:2000, 5 s connect timeout, socket bound to the WiFi network
  (works without internet)
- `UsbSerialTransport` : CP210x / FTDI FT232R, 19200 8N1, plain Android USB host API
- `MainActivity` : Control (GET_POS 1 Hz, hold-to-slew with live rate, STOP), Diagnostics
  (`$$$` command mode, ver / get uart / get comm / show sys / show connection, raw frame
  sender), Log (export via file picker, share)

## Protocol notes
Frames are Celestron AUX: `0x3B`, len, src, dst, cmd, data..., checksum, with
checksum = two's complement of the sum of len..data. The app is device 0x20 (APP) talking to
azimuth 0x10 and altitude 0x11. AUX over the SkyQ WiFi module is plaintext and unauthenticated
on an open network, which is inherent to the protocol: anyone joined to the same SkyQLink
network could drive the mount, so stay on a private network you control. The app asks for
fine location only so it can display the WiFi name (SSID) in the join hint.

## UNVERIFIED (compile-verified only; no hardware test has been run)
1. The APK has not been run on a phone or a scope. It compiles, packages, signs and verifies
   (v1/v2/v3); behaviour is untested.
2. 0x25 semantics: treated as move negative (standard AUX map), with STOP sending rate 0 on
   both 0x24 and 0x25 per axis.
3. Rate scale 1-9 (0 = stop) and which physical direction is "+" are standard-map assumptions.
4. Signed 24-bit BAM and 2^24 counts per 360 degrees; Alt shown as -180..180, Az as 0..360.
5. GET_VERSION reply layout: two data bytes shown as major.minor, anything else as hex.
6. Module command-mode terminator: commands sent with CR+LF; `$$$` sent with no terminator
   after a 400 ms pause (WiFly guard time).
7. "Module busy" status is a heuristic (immediate close with no data, refused, or timeout).
8. USB-serial: never tested. FTDI H-series (FT232H etc.) and PL2303/CH340 are not supported.
   Check the mount's hand-control port signal levels (RS-232 vs TTL) before wiring an adapter.
9. Goto, align, sync: not implemented (leads only).
10. Binding to a no-internet WiFi uses `ConnectivityManager.requestNetwork`, falling back to
    the default route if Android does not return a network within 6 s.
