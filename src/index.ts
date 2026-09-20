/**
 * @legichain/sdk — public entry point.
 *
 * Tenants get one entry point: `LegichainClient`. Construct it with
 * the API key from `panel.legichain.com/app/api-keys`:
 *
 * ```ts
 * import { LegichainClient } from "@legichain/sdk";
 *
 * const c = new LegichainClient({ apiKey: "key_xxx.secret_yyy" });
 *
 * // AML screening
 * const r = await c.screening.person({ name: "Vladimir Putin", country: "RU" });
 * if (r.summary.recommendation === "block") { ... }
 *
 * // KYC — orchestrated flow
 * const flow = await c.kyc.startFlow({
 *   subjectExternalId: "your-user-42",
 *   documentTypes: ["passport"],
 * });
 * flow.on("state", s => console.log(s.currentStep, s.currentAttempt));
 * await flow.uploadDocument({ side: "single", imageBytes: jpegBuffer });
 * await flow.uploadSelfie({ imageBytes: selfieBuffer });
 * const decision = await flow.submit();
 *
 * // Address verification
 * const av = await c.addressVerification.create({
 *   subjectExternalId: "your-user-42",
 *   claimedAddress: { country: "TR", city: "İstanbul" },
 * });
 * ```
 */

export { LegichainClient } from "./client";
export { startKyc } from "./nativeKyc";
export type { NativeKycOptions, NativeKycResult } from "./nativeKyc";
export type {
  LegichainClientOptions,
  ProblemDetails,
} from "./client";
export {
  LegichainError,
  LegichainStateError,
  LegichainCaptureRejected,
} from "./errors";
export { ScreeningApi } from "./screening";
export { KycApi } from "./kyc/client";
export { KycFlowController } from "./kyc/flow";
export { AddressVerificationApi } from "./addressVerification";
export { PersonasApi } from "./personas";
export { WebhooksApi } from "./webhooks";
export { ImageQualityGate, detectMrzLines } from "./kyc/quality";
export type {
  NfcReader,
  NfcReadResult,
} from "./kyc/nfc";
export { SimulatedNfcReader } from "./kyc/nfc";
export * from "./types/common";
export * from "./types/screening";
export * from "./types/kyc";
export * from "./types/av";
