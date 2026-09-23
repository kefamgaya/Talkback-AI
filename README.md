# Soma AI

Soma AI is a private, on-device study companion for Android. Students can open
their own text notes, read them in an accessible interface, find relevant
passages, and download a local language model selected for their phone's
hardware. Notes remain on the device.

The Android package is `com.kefamgaya.somaai`. The installed application asks
for no contacts, location, phone, notification, Bluetooth, broad storage,
package-list, or accessibility-service permission. Internet access is used for
optional model downloads. Microphone access is optional and requested only when
the student taps **Ask by voice**; Android's on-device recognizer is required.

The launcher experience has three accessible destinations: **Study** for notes
and questions, **Models** for hardware-aware local AI downloads, and
**Settings** for speech, study behavior, permissions, and privacy controls.
System bars, display cutouts, gesture navigation, and the keyboard are handled
with runtime window insets instead of fixed device-specific padding.

This project began from the open-source Google TalkBack codebase. The original
Apache 2.0 license and copyright notices are retained.

### How to Build

Run `gradle assemblePhoneDebug`, or use the GitHub Actions workflow. Each
successful workflow run stores an APK artifact, and a `v*` tag publishes the APK
to GitHub Releases.

### How to Install

Download the latest APK from GitHub Releases and install it, or use `adb install`.

### How to Run

Open **Soma AI** from the normal app launcher. Use **Open a note** to choose a
supported document through Android's system document picker; broad storage
access is not required.
