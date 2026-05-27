import { LegichainError } from "./errors";
import { AddressVerificationApi } from "./addressVerification";
import { KycApi } from "./kyc/client";
import { PersonasApi } from "./personas";
import { ScreeningApi } from "./screening";
import { WebhooksApi } from "./webhooks";

export interface LegichainClientOptions {
  /** `key_xxx.secret_yyy` from `panel.legichain.com/app/api-keys`. */
  apiKey: string;
  /** Defaults to production: `https://panel.legichain.com`. */
  baseUrl?: string;
  /** Per-request timeout in ms. Default 30 000. */
  timeoutMs?: number;
  /** Custom fetch implementation (defaults to global `fetch`). */
  fetch?: typeof fetch;
  /** Free-text suffix on the User-Agent header. */
  userAgent?: string;
}

export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  code?: string;
  detail?: string;
  instance?: string;
}

interface RequestInit_ {
  method?: "GET" | "POST" | "DELETE" | "PATCH" | "PUT";
  body?: Record<string, unknown>;
  query?: Record<string, string | number | boolean | undefined | null>;
  clientToken?: string;
  idempotencyKey?: string;
  timeoutMs?: number;
  /** When `true`, expect a `text/event-stream` response and skip JSON parse. */
  stream?: boolean;
}

export class LegichainClient {
  readonly apiKey: string;
  readonly baseUrl: string;
  readonly timeoutMs: number;
  private readonly _fetch: typeof fetch;
  private readonly _userAgent: string;

  readonly screening: ScreeningApi;
  readonly kyc: KycApi;
  readonly addressVerification: AddressVerificationApi;
  readonly personas: PersonasApi;
  readonly webhooks: WebhooksApi;

  constructor(opts: LegichainClientOptions) {
    if (!opts.apiKey) throw new Error("apiKey is required");
    this.apiKey = opts.apiKey;
    this.baseUrl = (opts.baseUrl ?? "https://panel.legichain.com").replace(
      /\/$/,
      "",
    );
    this.timeoutMs = opts.timeoutMs ?? 30_000;
    this._fetch = opts.fetch ?? globalThis.fetch.bind(globalThis);
    this._userAgent = `legichain-rn-sdk/1.0.0${
      opts.userAgent ? ` (${opts.userAgent})` : ""
    }`;

    this.screening = new ScreeningApi(this);
    this.kyc = new KycApi(this);
    this.addressVerification = new AddressVerificationApi(this);
    this.personas = new PersonasApi(this);
    this.webhooks = new WebhooksApi(this);
  }

  /** Internal: shared HTTP layer. */
  async request<T>(path: string, init: RequestInit_ = {}): Promise<T> {
    const url = this._url(path, init.query);
    const headers: Record<string, string> = {
      authorization: `Bearer ${this.apiKey}`,
      accept: init.stream ? "text/event-stream" : "application/json",
      "user-agent": this._userAgent,
    };
    if (init.clientToken) headers["x-kyc-client-token"] = init.clientToken;
    if (init.idempotencyKey) headers["idempotency-key"] = init.idempotencyKey;
    let body: string | undefined;
    if (init.body !== undefined) {
      headers["content-type"] = "application/json";
      body = JSON.stringify(init.body);
    }
    const controller = new AbortController();
    const t = setTimeout(
      () => controller.abort(),
      init.timeoutMs ?? this.timeoutMs,
    );
    try {
      const resp = await this._fetch(url, {
        method: init.method ?? (init.body ? "POST" : "GET"),
        headers,
        body,
        signal: controller.signal,
      });
      if (!resp.ok) {
        const text = await resp.text();
        let problem: ProblemDetails = {};
        try {
          problem = JSON.parse(text) as ProblemDetails;
        } catch {/* non-JSON */}
        throw new LegichainError({
          status: resp.status,
          code: problem.code ?? `HTTP_${resp.status}`,
          message: problem.detail ?? problem.title ?? `HTTP ${resp.status}`,
          problem: problem as Record<string, unknown>,
        });
      }
      if (init.stream) {
        // Caller will consume `resp.body` directly. Return the Response.
        return resp as unknown as T;
      }
      if (resp.status === 204) return undefined as T;
      const text = await resp.text();
      if (!text) return undefined as T;
      return JSON.parse(text) as T;
    } finally {
      clearTimeout(t);
    }
  }

  private _url(path: string, query?: RequestInit_["query"]): string {
    let url = this.baseUrl + path;
    if (query) {
      const qs = Object.entries(query)
        .filter(([, v]) => v !== undefined && v !== null && v !== "")
        .map(
          ([k, v]) =>
            `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`,
        )
        .join("&");
      if (qs) url += (url.includes("?") ? "&" : "?") + qs;
    }
    return url;
  }

  /** Convenience: long-lived SSE. Yields each `data:` payload as JSON. */
  async *openEventStream<T>(
    path: string,
    clientToken: string,
  ): AsyncGenerator<T> {
    const resp = (await this.request<Response>(path, {
      method: "GET",
      clientToken,
      stream: true,
    })) as Response;
    const reader = resp.body?.getReader();
    if (!reader) return;
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // SSE framing: events separated by \n\n.
      let idx;
      while ((idx = buffer.indexOf("\n\n")) !== -1) {
        const frame = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const data = frame
          .split("\n")
          .filter((l) => l.startsWith("data:"))
          .map((l) => l.slice(5).trimStart())
          .join("\n");
        if (!data) continue;
        try {
          yield JSON.parse(data) as T;
        } catch { /* skip malformed */ }
      }
    }
  }
}
