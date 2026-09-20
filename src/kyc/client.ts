import type { LegichainClient } from "../client";
import type {
  DocumentSubmitResponse,
  DocumentUploadInput,
  KycApplicationCreateInput,
  KycApplicationCreated,
  KycDecision,
  KycStatus,
  LivenessChallenge,
  LivenessSubmitInput,
  NfcSubmitInput,
  NfcSubmitResponse,
  SelfieSubmitInput,
} from "../types/kyc";
import { toBase64 } from "../utils";
import { KycFlowController } from "./flow";

export class KycApi {
  constructor(private readonly client: LegichainClient) {}

  createApplication(
    input: KycApplicationCreateInput,
  ): Promise<KycApplicationCreated> {
    return this.client.request<KycApplicationCreated>(
      "/v1/kyc/applications",
      { body: input as Record<string, unknown> },
    );
  }

  /** One-shot: create an application AND wrap it in a flow
   *  controller. 90% of integrations want this. */
  async startFlow(
    input: KycApplicationCreateInput & {
      /** Begin polling/SSE immediately. Default `true`. */
      autoStart?: boolean;
      /** When true, use SSE; otherwise polling. Default `true`. */
      useEvents?: boolean;
      /** Polling cadence (ms) when SSE is off / falls back. */
      pollIntervalMs?: number;
    },
  ): Promise<KycFlowController> {
    const { autoStart, useEvents, pollIntervalMs, ...createInput } = input;
    const created = await this.createApplication(createInput);
    const flow = new KycFlowController(this.client, this, created);
    if (autoStart !== false) {
      await flow.startListening({
        useEvents: useEvents !== false,
        intervalMs: pollIntervalMs,
      });
    }
    return flow;
  }

  getStatus(
    applicationId: string,
    opts: { includeExtracted?: boolean } = {},
  ): Promise<KycStatus> {
    return this.client.request<KycStatus>(
      `/v1/kyc/applications/${applicationId}/status`,
      {
        query: opts.includeExtracted
          ? { include_extracted: "true" }
          : undefined,
      },
    );
  }

  uploadDocument(
    applicationId: string,
    clientToken: string,
    input: DocumentUploadInput,
  ): Promise<DocumentSubmitResponse> {
    const body: Record<string, unknown> = {
      document_type: input.documentType ?? "passport",
      side: input.side,
      mime_type: input.mimeType ?? "image/jpeg",
      image_b64:
        input.imageBase64 ??
        (input.imageBytes ? toBase64(input.imageBytes) : ""),
    };
    if (input.capturedAt) {
      body.captured_at_client = input.capturedAt.toISOString();
    }
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/documents`,
      { body, clientToken },
    );
  }

  submitNfc(
    applicationId: string,
    clientToken: string,
    input: NfcSubmitInput,
  ): Promise<NfcSubmitResponse> {
    const body: Record<string, unknown> = {
      protocol: input.protocol,
      access_error: false,
      sod_b64: toBase64(input.sod),
    };
    if (input.keyDerivation) body.key_derivation = input.keyDerivation;
    if (input.dg1) body.dg1_b64 = toBase64(input.dg1);
    if (input.dg2) body.dg2_b64 = toBase64(input.dg2);
    if (input.dg7) body.dg7_b64 = toBase64(input.dg7);
    if (input.dg12) body.dg12_b64 = toBase64(input.dg12);
    if (input.dg13) body.dg13_b64 = toBase64(input.dg13);
    if (input.deviceAttestation) body.device_attestation = input.deviceAttestation;
    if (input.dg11) body.dg11_b64 = toBase64(input.dg11);
    if (input.dg14) body.dg14_b64 = toBase64(input.dg14);
    if (input.dg15) body.dg15_b64 = toBase64(input.dg15);
    if (input.activeAuthentication) {
      body.active_authentication_b64 = toBase64(input.activeAuthentication);
    }
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/nfc`,
      { body, clientToken },
    );
  }

  /** Chip-access failure (antenna noise, CAN required, no SOD).
   *  Server keeps state at `awaiting_nfc` — no attempt burned. */
  submitNfcAccessError(
    applicationId: string,
    clientToken: string,
    opts: { protocol?: "BAC" | "PACE"; code?: string } = {},
  ): Promise<NfcSubmitResponse> {
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/nfc`,
      {
        body: {
          protocol: opts.protocol ?? "PACE",
          access_error: true,
          access_error_code: opts.code ?? "chip_not_responding",
        },
        clientToken,
      },
    );
  }

  uploadSelfie(
    applicationId: string,
    clientToken: string,
    input: SelfieSubmitInput,
  ): Promise<Record<string, unknown>> {
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/selfie`,
      {
        body: {
          mime_type: input.mimeType ?? "image/jpeg",
          image_b64:
            input.imageBase64 ??
            (input.imageBytes ? toBase64(input.imageBytes) : ""),
          is_video: input.isVideo ?? false,
        },
        clientToken,
      },
    );
  }

  issueLivenessChallenge(
    applicationId: string,
    clientToken: string,
    opts: { length?: number; ttlSeconds?: number } = {},
  ): Promise<LivenessChallenge> {
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/liveness/challenge`,
      {
        body: {
          length: opts.length ?? 3,
          ttl_seconds: opts.ttlSeconds ?? 60,
        },
        clientToken,
      },
    );
  }

  submitLiveness(
    applicationId: string,
    clientToken: string,
    input: LivenessSubmitInput,
  ): Promise<Record<string, unknown>> {
    const body = {
      mode: input.mode, frame_b64: input.frameBase64,
      frame_mime_type: input.frameMimeType ?? "image/jpeg",
      challenge_token: input.challengeToken, completed_actions: input.completedActions ?? [],
      frames: input.frames ?? [], device_attestation: input.deviceAttestation,
    };
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/liveness`,
      { body, clientToken },
    );
  }

  submit(applicationId: string, clientToken: string): Promise<KycDecision> {
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/submit`,
      { body: {}, clientToken },
    );
  }

  requestRetry(
    applicationId: string,
    clientToken: string,
    reason?: string,
  ): Promise<Record<string, unknown>> {
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/retry`,
      { body: reason ? { reason } : {}, clientToken },
    );
  }

  extendTtl(
    applicationId: string,
    clientToken: string,
  ): Promise<Record<string, unknown>> {
    return this.client.request(
      `/v1/kyc/applications/${applicationId}/extend-ttl`,
      { method: "POST", clientToken },
    );
  }

  events(applicationId: string, clientToken: string): AsyncGenerator<KycStatus> {
    return this.client.openEventStream<KycStatus>(
      `/v1/kyc/applications/${applicationId}/events`,
      clientToken,
    );
  }
}
