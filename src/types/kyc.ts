import type {
  DecisionOutcome,
  DocumentSide,
  DocumentType,
  Intent,
  KycCurrentStep,
  Sex,
} from "./common";

export interface KycApplicationCreateInput {
  subject_external_id?: string;
  persona_id?: string;
  external_reference?: string;
  intent?: Intent;
  document_type_allowed?: DocumentType[];
  nfc_required?: boolean;
  callback_url?: string;
  claimed_full_name?: string;
  claimed_personal_number?: string;
  /** YYYY-MM-DD */
  claimed_birth_date?: string;
  /** YYYY-MM-DD */
  claimed_expiry_date?: string;
  claimed_document_number?: string;
  /** ISO 3166-1 alpha-3, e.g. `TUR`. */
  claimed_nationality?: string;
  claimed_issuing_country?: string;
  claimed_sex?: Sex;
  claimed_document_type?: DocumentType;
  meta?: Record<string, unknown>;
}

export interface KycApplicationCreated {
  application_id: string;
  persona_id: string;
  persona_created: boolean;
  client_token: string;
  client_token_expires_at?: string;
  state: string;
  next_steps: string[];
  expires_at: string;
}

export interface KycDecisionSummary {
  outcome: DecisionOutcome | null;
  outcome_reason: string | null;
  decision_id: string | null;
  risk_score: number | null;
}

export interface KycStatus {
  application_id: string;
  persona_id: string;
  state: string;
  current_step: KycCurrentStep;
  retry_available: boolean;
  current_attempt: number;
  max_attempts: number;
  intent?: string;
  nfc_required?: boolean;
  document_type_allowed?: DocumentType[];
  risk_score: number | null;
  decision: KycDecisionSummary | null;
  extracted_fields: Record<string, unknown> | null;
  extracted_fields_by_source: Record<string, unknown> | null;
  completed_at: string | null;
  expires_at: string;
  requested_at: string;
}

export interface DocumentSubmitResponse {
  document_id: string;
  image_id: string;
  application_id: string;
  persona_id: string;
  state: string;
  iqa_passed: boolean;
  iqa_reason: string | null;
  blur_laplacian: number | null;
  glare_score: number | null;
  bytes_size: number;
  sha256: string;
  extraction_status: "pending" | "extracting" | "extracted" | "failed";
  ocr_job_id: string | null;
}

export interface DocumentUploadInput {
  documentType?: DocumentType;
  side: DocumentSide;
  /** Raw image bytes (Uint8Array) or a base64 string. */
  imageBytes?: Uint8Array | ArrayBuffer;
  imageBase64?: string;
  mimeType?: "image/jpeg" | "image/png" | "image/heic";
  capturedAt?: Date;
}

export interface NfcSubmitInput {
  protocol: "BAC" | "PACE";
  keyDerivation?: "MRZ" | "CAN";
  sod: Uint8Array | ArrayBuffer | string;
  dg1?: Uint8Array | ArrayBuffer | string;
  dg2?: Uint8Array | ArrayBuffer | string;
  dg11?: Uint8Array | ArrayBuffer | string;
  dg14?: Uint8Array | ArrayBuffer | string;
  dg15?: Uint8Array | ArrayBuffer | string;
  activeAuthentication?: Uint8Array | ArrayBuffer | string;
}

export interface NfcSubmitResponse {
  nfc_read_id: string;
  application_id: string;
  persona_id: string;
  state: string;
  verification_status:
    | "passed"
    | "failed"
    | "access_error"
    | "pending";
  passive_auth_passed: boolean | null;
  cert_chain_valid: boolean | null;
  csca_country: string | null;
  csca_fingerprint_sha256: string | null;
  dg_hash_results: Record<string, boolean>;
  failure_codes: string[];
}

export interface SelfieSubmitInput {
  imageBytes?: Uint8Array | ArrayBuffer;
  imageBase64?: string;
  mimeType?: "image/jpeg" | "image/png" | "image/heic";
  isVideo?: boolean;
}

export interface LivenessChallenge {
  application_id: string;
  challenge_token: string;
  sequence: string[];
  issued_at: string;
  valid_until: string;
}

export interface LivenessSubmitInput {
  challengeToken: string;
  actionsPerformed: string[];
  frames?: (Uint8Array | ArrayBuffer | string)[];
  padScore?: number;
}

export interface KycDecision {
  application_id: string;
  persona_id: string;
  outcome: DecisionOutcome;
  outcome_reason: string | null;
  risk_score: number | null;
  decision_id: string;
  hard_fail_codes: string[];
  manual_review_id: string | null;
  manual_review_priority: string | null;
  completed_at: string | null;
}
