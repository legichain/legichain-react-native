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
