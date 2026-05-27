import type { LegichainClient } from "./client";

export class WebhooksApi {
  constructor(private readonly client: LegichainClient) {}

  /** Subscribe. The `secret` on the response is returned ONCE; store
   *  it server-side — needed to verify HMAC signatures on deliveries. */
  subscribe(input: {
    url: string;
    event_types: string[];
    description?: string;
  }): Promise<Record<string, unknown>> {
    return this.client.request("/v1/admin/webhooks", { body: input });
  }

  list(): Promise<Record<string, unknown>[]> {
    return this.client.request("/v1/admin/webhooks");
  }

  delete(webhookId: string): Promise<void> {
    return this.client.request(`/v1/admin/webhooks/${webhookId}`, {
      method: "DELETE",
    });
  }

  test(webhookId: string): Promise<Record<string, unknown>> {
    return this.client.request(`/v1/admin/webhooks/${webhookId}/test`, {
      method: "POST",
    });
  }
}
