# 2.0.0 — 2026-09-20

- Current KYC evidence contract, check flags and pending decisions.
- Single configured API token; session helpers manage application state.
- Evidence processing is awaited before submission; final result is webhook-driven.
- Native camera/document frames, MRZ auto capture, BAC/PACE NFC and observed active liveness.
- Turkish/English guided screens and submitted/cancelled host return.
- Device acceptance and iOS Xcode build remain pending; see KYC-V2.md.

# Changelog

## 1.0.0 — 2026-05-27

Initial public release.

- `LegichainClient` — single entry point, API-key auth.
- KYC: `client.kyc.startFlow()` returns a stateful `KycFlowController`
  (polling, SSE, retry, NFC submission, IQA gate).
- AML / sanctions screening: person / company / crypto / batch.
- Address verification: create / proof / submit / status.
- Personas + Webhooks helpers.
- `NfcReader` abstract interface + `SimulatedNfcReader` for UI dev.
- `ImageQualityGate` + `detectMrzLines` (pure-JS, no native module).
- Typed Problem-Details exceptions (`LegichainError`).
- TypeScript strict mode compliant.
