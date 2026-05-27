# Legichain SDK for React Native

Official React Native client for the **[Legichain](https://legichain.com)**
KYC, AML / sanctions screening, and address-verification API.

```bash
npm install @legichain/sdk
# or
yarn add @legichain/sdk
# or
pnpm add @legichain/sdk
```

[![npm](https://img.shields.io/npm/v/@legichain/sdk.svg)](https://www.npmjs.com/package/@legichain/sdk)
[![types](https://img.shields.io/npm/types/@legichain/sdk.svg)](https://www.npmjs.com/package/@legichain/sdk)
[![License](https://img.shields.io/github/license/legichain/legichain-react-native.svg)](https://github.com/legichain/legichain-react-native/blob/main/LICENSE)

- TypeScript-first, full types for every response.
- One API key wraps every customer-facing `/v1` endpoint.
- KYC `kyc.startFlow()` returns a stateful controller — polling, SSE,
  retry, NFC submission, image-quality gating included.
- Pure-JS Laplacian blur gate + MRZ pattern hint for on-device IQA
  (runs in Hermes / JSC, no native module required).
- Pluggable `NfcReader` interface — bring your own eMRTD chip library
  (e.g. `react-native-nfc-manager`).

## Get an API key

Sign up at **<https://legichain.com>** — the Free plan ships with 1 RPS
and 300 monthly credits, no card required.

> **panel.legichain.com → API keys → New key**

Keys look like `lc_live_<22>.sk_live_<44>` (production) or
`lc_test_<22>.sk_test_<44>` (test mode, never spends credits). Store
the secret half in your secret manager — it's shown once. See the
[full API guide](https://github.com/legichain/legichain/tree/main/docs/api-public)
for plans, rate limits and the OpenAPI spec.

---

## Quick start

```ts
import { LegichainClient } from "@legichain/sdk";

const client = new LegichainClient({ apiKey: "lc_live_xxxx.sk_live_xxxx" });

// AML screening
const r = await client.screening.person({ name: "Vladimir Putin", country: "RU" });
if (r.summary.recommendation === "block") { /* deny */ }

// KYC orchestrated flow
const flow = await client.kyc.startFlow({
  subject_external_id: "your-user-42",
  document_type_allowed: ["passport"],
  claimed_full_name: "ERIKA MUSTERMANN",
  claimed_nationality: "DEU",
});
flow.on("state", s => console.log(s.current_step, s.current_attempt));

await flow.uploadDocument({ side: "single", imageBytes: jpegBuffer });
await flow.uploadSelfie({ imageBytes: selfieBuffer });
const decision = await flow.submit();
console.log(decision.outcome, "risk", decision.risk_score);

flow.dispose();
```

`flow.latest` always has the most recent status; subscribe to
`flow.on("state", …)` for live updates (SSE-driven by default, polling
fallback). Route your UI off `current_step` (typed union) — never off
raw `state`.

## NFC chip reads

The SDK exposes a typed interface — bring your own chip library:

```ts
import { NfcReader, NfcReadResult, SimulatedNfcReader } from "@legichain/sdk";

class MyReader implements NfcReader {
  async read({ documentNumber, dateOfBirthYymmdd, expiryDateYymmdd }) {
    // Use `react-native-nfc-manager` + BAC/PACE parser of your choice.
    return { protocol: "PACE", sod, dg1, dg2, dg14, dg15 } as NfcReadResult;
  }
  async cancel() {/* … */}
}

const r = await new MyReader().read({
  documentNumber: "P12345678",
  dateOfBirthYymmdd: "850615",
  expiryDateYymmdd: "300615",
});
await flow.submitNfcRead(r);
```

If the chip didn't respond (antenna noise, CAN required), call
`flow.reportNfcAccessError()` — the server keeps state at
`awaiting_nfc` so the user retries without burning an attempt.

For prototyping the UI before integrating chip code, `SimulatedNfcReader`
returns canned bytes after a delay.

## Image-quality gate

```ts
import { ImageQualityGate, detectMrzLines } from "@legichain/sdk";

const gate = new ImageQualityGate({ blurThreshold: 100 });
gate.mustPass({ grayscale: graybytes, width: w, height: h });

const mrz = detectMrzLines(ocrLines);
if (!mrz) showHint("Move closer — MRZ not visible");
```

## Error handling

```ts
import { LegichainError } from "@legichain/sdk";

try {
  await flow.uploadDocument({ ... });
} catch (e) {
  if (e instanceof LegichainError) {
    if (e.code === "KYC_INVALID_CLIENT_TOKEN") { /* app expired */ }
    else if (e.status === 429) { /* back off */ }
  }
}
```

Stable codes are documented in the
[public API guide](https://github.com/legichain/legichain/tree/main/docs/api-public).

## Other SDKs

| Platform | Repo |
|---|---|
| Flutter | [legichain-flutter](https://github.com/legichain/legichain-flutter) |
| iOS (native) | [legichain-ios](https://github.com/legichain/legichain-ios) |
| Android (native) | [legichain-android](https://github.com/legichain/legichain-android) |
| Python | [legichain-python](https://github.com/legichain/legichain-python) |
| Node.js / TypeScript | [legichain-node](https://github.com/legichain/legichain-node) |
| Go | [legichain-go](https://github.com/legichain/legichain-go) |
| Java | [legichain-java](https://github.com/legichain/legichain-java) |
| .NET / C# | [legichain-dotnet](https://github.com/legichain/legichain-dotnet) |

Server-side platform code, OpenAPI spec, and Postman collection live
in the main monorepo at
**[legichain/legichain](https://github.com/legichain/legichain)**.

## License

MIT — see [LICENSE](LICENSE).
