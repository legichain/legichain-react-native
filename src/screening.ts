import type { LegichainClient } from "./client";
import type {
  AsyncJobInfo,
  CompanyScreeningInput,
  CryptoScreeningInput,
  PersonScreeningInput,
  ScreeningResponse,
} from "./types/screening";

export class ScreeningApi {
  constructor(private readonly client: LegichainClient) {}

  /** 1 credit per call. Branch on `summary.recommendation`
   *  (`clear | review | block`). */
  person(input: PersonScreeningInput): Promise<ScreeningResponse> {
    return this.client.request<ScreeningResponse>("/v1/screen/person", {
      body: input as unknown as Record<string, unknown>,
    });
  }

  company(input: CompanyScreeningInput): Promise<ScreeningResponse> {
    return this.client.request<ScreeningResponse>("/v1/screen/company", {
      body: input as unknown as Record<string, unknown>,
    });
  }

  /** 3 credits per call. `chain` hint is optional but speeds the lookup. */
  crypto(input: CryptoScreeningInput): Promise<ScreeningResponse> {
    return this.client.request<ScreeningResponse>("/v1/screen/crypto", {
      body: input as unknown as Record<string, unknown>,
    });
  }

  /** Synchronous batch (≤ 200 items). */
  batch(
    items: Array<
      PersonScreeningInput | CompanyScreeningInput | CryptoScreeningInput
    >,
  ): Promise<ScreeningResponse[]> {
    return this.client.request<ScreeningResponse[]>("/v1/screen/batch", {
      body: { items } as Record<string, unknown>,
    });
  }

  /** Async batch — returns a job id; poll `jobStatus`. */
  batchAsync(
    items: Array<
      PersonScreeningInput | CompanyScreeningInput | CryptoScreeningInput
    >,
  ): Promise<AsyncJobInfo> {
    return this.client.request<AsyncJobInfo>("/v1/screen/batch/async", {
      body: { items } as Record<string, unknown>,
    });
  }

  jobStatus(jobId: string): Promise<AsyncJobInfo> {
    return this.client.request<AsyncJobInfo>(`/v1/screen/jobs/${jobId}`);
  }
}
