/** Shared enums + literal-string unions. */

export type DocumentType =
  | "tr_id_card"
  | "passport"
  | "driver_license"
  | "eu_national_id"
  | "uk_passport";

export type DocumentSide = "front" | "back" | "single";

export type Intent = "onboarding" | "re_verification" | "periodic_review";

export type Sex = "M" | "F";

/** UI buckets returned by `GET /status`. Route screens off this. */
export type KycCurrentStep =
  | "ready_to_upload_document"
  | "upload_document"
  | "processing_document"
  | "upload_nfc"
  | "upload_selfie"
  | "processing_biometrics"
  | "deciding"
  | "completed_approved"
  | "completed_rejected"
  | "in_manual_review"
  | "retry_pending"
  | "expired"
  | "canceled"
  | "unknown";

export const TERMINAL_STEPS: readonly KycCurrentStep[] = [
  "completed_approved",
  "completed_rejected",
  "expired",
  "canceled",
];

export type DecisionOutcome = "approved" | "rejected" | "manual_review";
