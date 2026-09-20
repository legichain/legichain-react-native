# KYC v2 integration / KYC v2 entegrasyonu

Configure one Legichain API token. The SDK creates the application and keeps
the API-returned client token internally. No backend changes are required.
Mobile requests originate directly from the device; server SDK requests
originate from the customer's server. Choose the tenant's regional API URL.

Tek Legichain API tokeniyle yapılandırın. SDK başvuruyu oluşturur ve API'nin
döndürdüğü dahili bilgileri yönetir. Mobil istekler cihazdan, sunucu SDK
istekleri müşterinin sunucusundan gider. Legichain backend değişikliği gerekmez.

## Evidence and submission

1. Create with document/check options. Document is mandatory. NFC defaults to
   false; liveness and face matching default to true. Explicit false is retained.
2. Capture front/back (single for a passport), NFC if required, and selfie.
3. For active liveness request a fresh challenge. Follow its ordered sequence;
   supply real observed actions and temporal frames, never client PAD scores.
4. Send evidence to `/v2/kyc/applications/{id}/{documents,nfc,selfie,liveness}`.
   Persist each receipt. A 202 receipt is queued work; poll its operation until
   completed. Failed/expired/cancelled operations require recovery.
5. Submit only after evidence processing. `/v1/.../submit` can return 202 with
   a null outcome. Show submitted and return to your app. The final business
   decision arrives at your configured webhook; verify its signature with the
   SDK webhook helper and handle deliveries idempotently.

Belge işleme bittiğinde başvuru gönderilir. `submitted` sonucu kimliğin
onaylandığı anlamına gelmez. Kullanıcıya başvurunun alındığını gösterin;
doğrulama sonucu webhook üzerinden gelir. Bildirimi müşteri uygulaması gönderir.

When face matching is enabled without active liveness, send the selfie plus a
passive liveness record. This is required by the current backend. Native flows
handle it automatically. A receipt being completed does not mean the evidence
passed all identity checks; the backend consolidates those checks at submission.

## Wire contract and migration

See `kyc-contract.json` for request/response fields exported from the unchanged
backend. Low-level APIs remain available for custom capture integrations.

- Liveness uses `mode`, `frame_b64`, `frame_mime_type`, optional
  `challenge_token`, `completed_actions`, `frames` and `device_attestation`.
- An action contains `action`, `started_at_ms`, `ended_at_ms` (1–8 seconds).
  Frame entries contain `image_b64` and `timestamp_ms`, at most 30 entries.
  Action and frame times share the same monotonic capture origin.
- Remove obsolete `actions_performed`, `frames_b64`, `pad_score` fields.
- NFC carries raw TLV bytes of SOD and data groups, including tags/lengths.
  Do not upload only parsed MRZ fields or decoded portrait pixels as DG data.
  AA response bytes go in `active_authentication_b64`, with the 8-byte challenge
  in `device_attestation.aa_challenge_b64`. Validation remains server-side.
- Use a stable unique idempotency key (1–256 bytes) for each evidence capture.
  Keep the same body/key after uncertain transport failures; a deliberate new
  capture uses a new key. Do not blindly retry unkeyed submission: first read
  application status and reconcile `deciding`/terminal states.
- Store receipts and application state in your server's session store when
  using multiple processes. Session helper objects themselves are in-memory.
- Respect retention in your own application; do not log tokens, raw document
  images, chip files or liveness frames. The native flow keeps capture data in
  memory and clears it when closed.

## react-native integration

```tsx
import { startKyc } from "@legichain/sdk";
const result = await startKyc({
  apiToken: token, language: "tr", application: { nfc_required: true },
});
// Native screen is closed; navigate to your own screen.
// result.status is submitted or cancelled, not identity approval.
```
Install `@legichain/sdk@2`, run `pod install` in ios, then rebuild the native app.
The package auto-links on Android and iOS. Expo Go is insufficient; use a native
development build. No separate NFC bridge package is required. Android bridge
compilation currently uses React Native 0.76.9; newer host versions require
their own integration build. This package has no bundled React Native runtime.

## Included native flow

The package provides a full-screen document selector, camera frame, corner and
MRZ detection, stable auto capture, native NFC guidance animation and chip
reading, selfie, active liveness, retry/cancel and submitted/return screens.
Choose `tr` (default) or `en` before launch. Both languages are bundled;
the host completion result uses stable machine-readable status strings.

Turkish: “Doğrulama başvurunuz gönderildi.” English: “Your verification
application has been submitted.” The next message explains that the provider
will notify the applicant. Returning to the host does not await the webhook.

Active prompts are delivered before the start cue. A neutral pose is required
before each action, followed by actual camera detection and return to center.
Moving early, tapping a button or elapsed time alone cannot complete an action.
The payload includes observed timings and frames covering baseline, movement
and return. Backgrounding interrupts an active challenge and requires a fresh
challenge. Upload retries reuse the original capture and idempotency key.

Android: CameraX, ML Kit text/face, OpenCV corners, JMRTD + Scuba ISO-DEP.
iOS: AVFoundation, Vision rectangle/text/landmarks, NFCPassportReader 2.3.1.
Native code is shared into Flutter/RN; `native-source.json` records file hashes.
Dependencies are resolved by Gradle/CocoaPods/SPM, not simulated adapters.

## Host platform setup

Android minimum API 24, compile SDK 35+, Java 17. Use AndroidX. CAMERA and NFC
permissions and the non-exported KYC activity merge from the library manifest.
Keep the default consumer rules. Runtime camera permission is requested by the
flow. Required NFC on a device without an enabled NFC adapter blocks that step.

iOS minimum 15, Swift 5.9+. In the host target enable “Near Field Communication
Tag Reading”. Include `com.apple.developer.nfc.readersession.formats = [TAG]`
in signed entitlements. Add these Info.plist entries:

```xml
<key>NSCameraUsageDescription</key><string>Identity and liveness verification</string>
<key>NFCReaderUsageDescription</key><string>Read your identity document chip</string>
<key>com.apple.developer.nfc.readersession.iso7816.select-identifiers</key>
<array><string>A0000002471001</string></array>
```

Localize permission descriptions in `tr.lproj/InfoPlist.strings` and
`en.lproj/InfoPlist.strings` in your app. System permission language is controlled
by iOS, while the SDK screen language is selected in its options.

BAC and MRZ-based PACE are implemented. Android additionally supports CAN-based
PACE input. iOS CAN-only documents are not supported by the selected reader.
This release does not claim every eMRTD PACE profile or protected data group.
Passive/active authentication evidence is uploaded for backend verification.

## Device acceptance still required before release

Build/unit tests do not verify NFC antenna performance or camera calibration.
Test real BAC passport and PACE identity card reads, chip removal/retry, camera
permission denial, poor lighting, all five actions (including early movement),
background/foreground interruption, network failure during upload/submit,
TR/EN layouts, cancellation, and host return. Verify webhook outcome separately.
The current Windows build has no Xcode/device acceptance evidence. iPhone
testing requires a signed Xcode build on a Mac or an existing macOS CI build
delivered through TestFlight. NFC cannot be validated in an iOS simulator.

Android host `android {}` packaging configuration for Bouncy Castle OSGi metadata:

```kotlin
packaging { resources.excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF" }
```

This excludes duplicate OSGi manifests only, not the cryptographic code or licenses.
