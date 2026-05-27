import type { LegichainClient } from "./client";
import type {
  AVCreateInput,
  AVCreated,
  AVProofUploadInput,
  AVProofUploaded,
  AVStatus,
} from "./types/av";
import { toBase64 } from "./utils";

export class AddressVerificationApi {
  constructor(private readonly client: LegichainClient) {}

  create(input: AVCreateInput): Promise<AVCreated> {
    return this.client.request<AVCreated>("/v1/address-verifications", {
      body: input as unknown as Record<string, unknown>,
    });
  }

  uploadProof(
    verificationId: string,
    clientToken: string,
    input: AVProofUploadInput,
  ): Promise<AVProofUploaded> {
    const body: Record<string, unknown> = {
      document_type: input.documentType,
      mime_type: input.mimeType,
      image_b64:
        input.imageBase64 ??
        (input.imageBytes ? toBase64(input.imageBytes) : ""),
    };
    if (input.capturedAt) {
      body.captured_at_client = input.capturedAt.toISOString();
    }
    return this.client.request(
      `/v1/address-verifications/${verificationId}/proof`,
      { body, clientToken },
    );
  }

  submit(
    verificationId: string,
    clientToken: string,
  ): Promise<Record<string, unknown>> {
    return this.client.request(
      `/v1/address-verifications/${verificationId}/submit`,
      { body: {}, clientToken },
    );
  }

  getStatus(verificationId: string): Promise<AVStatus> {
    return this.client.request<AVStatus>(
      `/v1/address-verifications/${verificationId}/status`,
    );
  }
}
