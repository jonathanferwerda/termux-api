# JawnOS fork of termux-api

Base: upstream tag v0.53.0, branch jawnos.
Purpose: device APIs (camera, microphone, notifications, SMS, fingerprint,
...) for JawnOS on Android. See ../PLAN.md.

## Deltas from upstream

1. app/build.gradle: rebranded TERMUX_API_APP_NAME to "JawnOS API".
2. Dependencies: replaced the JitPack termux-shared dependency
   (com.termux.termux-app:termux-shared:7bceab88e2) with vendored local modules
   under termux-modules/ (JitPack was not serving artifacts at build time):
   - terminal-emulator, terminal-view, termux-shared: sources from termux-app
     commit 7bceab88e2, the commit this API release pins.
   - termux-am-library: sources from github.com/termux/termux-am-library.
   - Their build.gradle files were rewritten for AGP 8 (namespace,
     compileSdk/minSdk/targetSdk); native builds and maven-publish blocks were
     removed (this app strips those JNI libs upstream anyway).
3. settings.gradle includes the vendored modules.
4. gradle.properties: android.nonTransitiveRClass=false (merged-R behavior the
   vendored termux-shared code requires).

## Build (local)

    JAVA_HOME=<jdk-17 dir> ANDROID_SDK_ROOT=<android-sdk dir> \
      ./gradlew -p . :app:assembleDebug --no-daemon --max-workers=4

Signing: debug builds use app/testkey_untrusted.jks, which is byte-identical
to termux-app's app/dev_keystore.jks, so both JawnOS apps share one
certificate (required for the shared com.termux UID).

## JawnOS delta: grant-all permissions screen

- JawnGrantAllActivity (exported; dark platform theme for the same reason as
  the dashboard: avoid Light-theme-with-dark-background mismatches): requests
  the runtime permissions behind jawn's termux-* utilities (camera, microphone,
  location, SMS, call log, contacts, biometric, Bluetooth LE on Android 12+)
  and links the special-access pages (overlay, notification listener,
  background location). Launched from the JawnOS app dashboard's
  "Grant device permissions" button.
- AndroidManifest: Bluetooth LE permissions added ahead of the jawn BLE
  bridge (legacy BLUETOOTH/BLUETOOTH_ADMIN plus BLUETOOTH_SCAN/CONNECT).

## JawnOS delta: jawn-bt BLE bridge (JawnBleService)

- Replaces the Tasker + nl.steinov.bletaskerplugin middleman. Owns a
  persistent BLE GATT link (Nordic UART Service) and speaks a line-based JSON
  protocol over a filesystem unix socket at
  $PREFIX/var/run/jawn-bt.sock (same shared UID, so jawn connects directly).
- Commands: scan / connect / send / disconnect / status / shutdown.
  Events: status, scan, scan_done, connected, disconnected, message, sent, error.
- Keeps the link up with auto-reconnect (3s backoff doubling to 30s); writes
  are chunked to (MTU-3) after requesting MTU 512; text is relayed raw so
  jawn's base64+JSON conventions and the ESP32 chunked streams (===EOF===)
  pass through unchanged. Notification-channel "jawn-bt", silent.
- Client: tools/jawn-bt (Perl; auto-starts the service via
  am start-foreground-service when the socket is absent).
- Manifest: service JawnBleService (exported=false, foregroundServiceType
  connectedDevice).

## JawnOS delta: LocationAPI timeout (ANR protection)

- REQUEST_ONCE previously used requestSingleUpdate + Looper.loop() with no
  timeout: while there was no fix (indoors, GPS cold), the broadcast receiver
  hung past Android's timeout and the whole app got ANR-killed every time
  jawn's navigation utility called `termux-location` (~2 min cadence observed
  on the phone). It now waits at most 25 s, then falls back to
  getLastKnownLocation (or returns the usual API_ERROR) and always returns.
  REQUEST_UPDATES already had a 30 s bound; this brings "once" in line.
- JawnBleService: unlink-and-retry when the socket file is left behind by a
  killed instance (onDestroy never ran), fixing "Address already in use".

## JawnOS delta: jawn-bt transport moved to loopback TCP

- The filesystem unix socket at $PREFIX/var/run/jawn-bt.sock never worked in
  practice: Android app mount namespaces make a socket file created by
  com.termux.api invisible to com.termux processes despite the shared UID, and
  android.net.LocalServerSocket defaults to SOCK_SEQPACKET which Perl's
  IO::Socket::UNIX (SOCK_STREAM) cannot connect to. JawnBleService now listens
  on 127.0.0.1:45999 (loopback only) and tools/jawn-bt connects over TCP.
  Same line-based JSON protocol, no other changes.

## JawnOS delta: MTU request is opt-in

- The connect command no longer requests MTU 512 by default: a large-MTU link
  disturbed the T-Watch's LVGL rendering (radio/flush contention showed as
  leftover button artifacts on its screen). Default is now the classic 23
  (writes are chunked at mtu-3 either way); pass "mtu":N in the connect
  command to opt back in (e.g. to test bulk throughput).

## JawnOS delta: logo1 branding for the API app

- Launcher icon now uses the logo1 art (black + yellow doodle), legacy
  density PNGs plus an adaptive icon; the old vector drawable is gone.
- Notifications use a white alpha-mask silhouette of the doodle
  (drawable-*/jawn_notification.png): the default small icon in
  NotificationAPI and the JawnBleService notification both point at it.
