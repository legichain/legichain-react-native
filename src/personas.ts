import type { LegichainClient } from "./client";

export class PersonasApi {
  constructor(private readonly client: LegichainClient) {}

  create(input: {
    subject_external_id?: string;
    display_name?: string;
    meta?: Record<string, unknown>;
  }): Promise<Record<string, unknown>> {
    return this.client.request("/v1/personas", { body: input });
  }

  list(opts: {
    subject_external_id?: string;
    limit?: number;
    cursor?: string;
  } = {}): Promise<Record<string, unknown>> {
    return this.client.request("/v1/personas", {
      query: {
        subject_external_id: opts.subject_external_id,
        limit: opts.limit ?? 50,
        cursor: opts.cursor,
      },
    });
  }

  get(personaId: string): Promise<Record<string, unknown>> {
    return this.client.request(`/v1/personas/${personaId}`);
  }
}
