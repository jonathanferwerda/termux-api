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
