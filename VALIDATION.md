# v2 validation record — 2026-09-20

## Completed on Windows

- Android release AAR and React Native Android native bridge compile passed.
- Android JVM tests: 5 passed, including all five server challenge actions,
  early movement, return to neutral, duration boundaries and MRZ corruption.
- Android emulator instrumentation: 1 test passed; six TR/EN welcome, document
  and NFC screenshots captured. This test checks layout using synthetic state;
  it sends no API request and provides no NFC or physical camera evidence.
- Flutter Android sample debug APK built for arm64. Flutter analyzer and two
  contract tests passed; sample language widget test passed.
- React Native JS bridge: 3 tests passed; typecheck and dual-module build passed.
- Swift source syntax parsed; this is not an Xcode compile or device test.

## Required before native stable publication

A physical Android and a signed iOS device build must exercise BAC/PACE chip
reads, all actual camera gestures, permissions, interruptions, uncertain network
retries, backend evidence processing, submitted/host return and webhook delivery.
No physical device acceptance or complete live KYC submission is claimed here.
The iOS reader supports MRZ-based PACE; CAN-only access is currently Android only.

The shared native-source.json in React Native/Flutter records bundled source
and resource hashes. Run python tools/verify_native.py in either source checkout
when editing shared files. Canonical sources are legichain-android and
legichain-ios; copy changes to both wrappers and regenerate the corresponding
SHA-256 entries before release.
