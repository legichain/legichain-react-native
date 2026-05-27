export type AVDocumentType =
  | "utility_bill"
  | "bank_statement"
  | "gov_letter"
  | "telco_bill"
  | "residency_certificate"
  | "tax_letter";

export interface ClaimedAddress {
  line1?: string;
  line2?: string;
  city?: string;
  state?: string;
  postal_code?: string;
  country: string; // ISO 3166-1 alpha-2/-3
}

export interface AVCreateInput {
  external_reference?: string;
  subject_external_id?: string;
  persona_id?: string;
  claimed_address: ClaimedAddress;
  accepted_document_types?: AVDocumentType[];
  /** 30..365, default 90. */
  max_age_days?: number;
  callback_url?: string;
}

export interface AVCreated {
  verification_id: string;
  persona_id: string;
  state: string;
  client_token: string;
  client_token_expires_at?: string;
  expires_at: string;
}

export interface AVProofUploadInput {
  documentType: AVDocumentType;
  mimeType: "application/pdf" | "image/jpeg" | "image/png" | "image/heic";
  imageBytes?: Uint8Array | ArrayBuffer;
  imageBase64?: string;
  capturedAt?: Date;
}

export interface AVProofUploaded {
  proof_id: string;
  state: string;
  extraction_status: string;
  parsed_issuer: string | null;
  parsed_issued_at: string | null;
  parsed_address: string | null;
}

export interface AVStatus {
  verification_id: string;
  persona_id: string;
  state: string;
  current_attempt: number;
  max_attempts: number;
  match_confidence: number | null;
  outcome: string | null;
  outcome_reason: string | null;
  proofs: Record<string, unknown>[];
  completed_at: string | null;
  expires_at: string;
  requested_at: string;
}
