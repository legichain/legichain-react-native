import { NativeModules, Platform } from "react-native";

export interface NativeKycOptions {
  /** The single API token issued by Legichain. All calls run from the app. */
  apiToken: string;
  baseUrl?: string;
  language?: "tr" | "en";
  /** Current POST /v1/kyc/applications request fields, including check flags. */
  application?: {
    subject_external_id?: string;
    external_reference?: string;
    document_type_allowed?: ("tr_id_card" | "passport" | "driver_license" | "eu_national_id" | "uk_passport")[];
    nfc_required?: boolean;
    liveness_required?: boolean;
    face_match_required?: boolean;
    callback_url?: string;
    [key: string]: unknown;
  };
}
export interface NativeKycResult {
  status: "submitted" | "cancelled";
  application_id: string;
}

/** Presents the packaged native capture/NFC/liveness flow and returns to the host app. */
export async function startKyc(options: NativeKycOptions): Promise<NativeKycResult> {
  if (Platform.OS !== "android" && Platform.OS !== "ios") throw new Error("Native KYC requires iOS or Android");
  if (!options.apiToken?.trim()) throw new Error("apiToken is required");
  const module = NativeModules.LegichainKyc;
  if (!module?.start) throw new Error("Legichain native module is missing; install pods and rebuild the app");
  return module.start({ ...options, baseUrl: options.baseUrl ?? "https://api.legichain.com", language: options.language ?? "tr", application: options.application ?? {} });
}
