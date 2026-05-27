export type ScreeningRecommendation = "clear" | "review" | "block";
export type RiskLevel = "no" | "low" | "medium" | "high" | "critical";

export interface HitFlags {
  is_sanctioned: boolean;
  is_pep: boolean;
  is_wanted: boolean;
  is_crime: boolean;
  is_adverse_media: boolean;
}

export interface Hit {
  entity_id: string;
  canonical_id: string;
  schema?: string;
  caption: string | null;
  /** Legacy 0..10 raw strength. Do NOT show this; show `match_confidence`. */
  score: number;
  match_signals: string[];
  topics: string[];
  sources: string[];
  countries: string[];
  risk_score: number;
  risk_source: string;
  degree: number;
  /** 0..100 — the only "% match" number to display. */
  match_confidence: number;
  name_ratio: number;
  flags: HitFlags;
  risk_level: RiskLevel;
}

export interface ScreeningSummary {
  matched: boolean;
  hit_count: number;
  has_sanctioned_hit: boolean;
  has_pep_hit: boolean;
  has_wanted_hit: boolean;
  has_crime_hit: boolean;
  has_adverse_media_hit: boolean;
  top_risk_score: number;
  top_risk_level: RiskLevel;
  top_match_confidence: number;
  recommendation: ScreeningRecommendation;
  authorities: string[];
  sources: string[];
}

export interface ScreeningResponse {
  request_id: string;
  screening_id: string | null;
  matched: boolean;
  summary: ScreeningSummary;
  hits: Hit[];
  search_time_ms: number;
  cost_credits: number;
  credits_remaining: number;
}

export interface PersonScreeningInput {
  name: string;
  country?: string;
  dob?: string;
  document?: string;
  topics?: string[];
  top_n?: number;
}

export interface CompanyScreeningInput {
  name: string;
  country?: string;
  registration_number?: string;
  top_n?: number;
}

export interface CryptoScreeningInput {
  address: string;
  chain?: string;
}

export interface AsyncJobInfo {
  job_id: string;
  status: "queued" | "running" | "done" | "failed";
  progress?: number;
  total_items?: number;
  results?: ScreeningResponse[];
}
