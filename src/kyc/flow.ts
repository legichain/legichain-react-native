/**
 * Stateful KYC orchestration. Wraps one application and gives the
 * caller an EventEmitter-style API that surfaces every state change.
 *
 * ```ts
 * const flow = await client.kyc.startFlow({...});
 * flow.on("state", s => console.log(s.current_step));
 * await flow.uploadDocument({ side: "single", imageBytes });
 * await flow.uploadSelfie({ imageBytes: selfie });
 * const decision = await flow.submit();
 * flow.dispose();
 * ```
 */

import type { LegichainClient } from "../client";
import { LegichainStateError } from "../errors";
import type {
  DocumentSubmitResponse,
  DocumentUploadInput,
  KycApplicationCreated,
  KycDecision,
  KycStatus,
  LivenessChallenge,
  LivenessSubmitInput,
  NfcSubmitInput,
  NfcSubmitResponse,
  SelfieSubmitInput,
} from "../types/kyc";
import { TERMINAL_STEPS } from "../types/common";
import type { KycApi } from "./client";

type Listener<T> = (event: T) => void;

interface StartListeningOptions {
  /** Use SSE when available; fall back to polling on stream error. */
  useEvents?: boolean;
  /** Polling cadence when SSE is off / falls back. Default 2000 ms. */
  intervalMs?: number;
}

export class KycFlowController {
  private _latest: KycStatus | null = null;
  private _disposed = false;
  private _stopPolling: (() => void) | null = null;
  private _eventsAbort: AbortController | null = null;
  private readonly _listeners = new Set<Listener<KycStatus>>();

  constructor(
    private readonly _client: LegichainClient,
    private readonly _api: KycApi,
    readonly created: KycApplicationCreated,
  ) {}

  get applicationId(): string {
    return this.created.application_id;
  }
  get personaId(): string {
    return this.created.persona_id;
  }
  get clientToken(): string {
    return this.created.client_token;
  }
  get latest(): KycStatus | null {
    return this._latest;
  }

  /** Subscribe to state updates. Returns an unsubscribe function. */
  on(event: "state", listener: Listener<KycStatus>): () => void {
    if (event !== "state") throw new Error(`unknown event: ${event}`);
    this._listeners.add(listener);
    return () => this._listeners.delete(listener);
  }

  private _emit(s: KycStatus): void {
    this._latest = s;
    for (const fn of this._listeners) fn(s);
  }

  async startListening(opts: StartListeningOptions = {}): Promise<void> {
    if (this._disposed) {
      throw new LegichainStateError("controller is disposed");
    }
    await this.refresh();
    const useEvents = opts.useEvents !== false;
    const interval = opts.intervalMs ?? 2000;
    if (useEvents) {
      this._startEventStream(interval);
    } else {
      this._startPolling(interval);
    }
  }

  private async _startEventStream(fallbackInterval: number): Promise<void> {
    const ctrl = new AbortController();
    this._eventsAbort = ctrl;
    try {
      const stream = this._api.events(
        this.applicationId,
        this.clientToken,
      );
      for await (const s of stream) {
        if (ctrl.signal.aborted) return;
        this._emit(s);
        if (this._isTerminal(s)) return;
      }
    } catch {
      // SSE crashed — fall back to polling.
      if (!ctrl.signal.aborted) this._startPolling(fallbackInterval);
    }
  }

  private _startPolling(intervalMs: number): void {
    let stopped = false;
    const tick = async (): Promise<void> => {
      if (stopped) return;
      try {
        const s = await this.refresh();
        if (this._isTerminal(s)) {
          stopped = true;
          return;
        }
      } catch {/* swallow transient errors */}
      if (!stopped) setTimeout(tick, intervalMs);
    };
    setTimeout(tick, intervalMs);
    this._stopPolling = () => {
      stopped = true;
    };
  }

  async refresh(): Promise<KycStatus> {
    const s = await this._api.getStatus(this.applicationId);
    this._emit(s);
    return s;
  }

  dispose(): void {
    this._disposed = true;
    this._stopPolling?.();
    this._stopPolling = null;
    this._eventsAbort?.abort();
    this._eventsAbort = null;
    this._listeners.clear();
  }

  private _isTerminal(s: KycStatus): boolean {
    return TERMINAL_STEPS.includes(s.current_step);
  }

  // ── Artefact uploads ─────────────────────────────────────────

  uploadDocument(
    input: DocumentUploadInput,
  ): Promise<DocumentSubmitResponse> {
    return this._api.uploadDocument(
      this.applicationId,
      this.clientToken,
      input,
    );
  }

  submitNfcRead(input: NfcSubmitInput): Promise<NfcSubmitResponse> {
    return this._api.submitNfc(this.applicationId, this.clientToken, input);
  }

  /** No SOD obtained — keep state at `awaiting_nfc`, no attempt
   *  burned. Call when the chip itself didn't respond. */
  reportNfcAccessError(opts: {
    protocol?: "BAC" | "PACE";
    code?: string;
  } = {}): Promise<NfcSubmitResponse> {
    return this._api.submitNfcAccessError(
      this.applicationId,
      this.clientToken,
      opts,
    );
  }

  uploadSelfie(input: SelfieSubmitInput): Promise<Record<string, unknown>> {
    return this._api.uploadSelfie(
      this.applicationId,
      this.clientToken,
      input,
    );
  }

  issueLivenessChallenge(
    opts: { length?: number; ttlSeconds?: number } = {},
  ): Promise<LivenessChallenge> {
    return this._api.issueLivenessChallenge(
      this.applicationId,
      this.clientToken,
      opts,
    );
  }

  submitLiveness(
    input: LivenessSubmitInput,
  ): Promise<Record<string, unknown>> {
    return this._api.submitLiveness(
      this.applicationId,
      this.clientToken,
      input,
    );
  }

  submit(): Promise<KycDecision> {
    return this._api.submit(this.applicationId, this.clientToken);
  }

  /** Customer-initiated retry. Throws if the latest status reports
   *  `retry_available: false`. */
  async retry(reason?: string): Promise<void> {
    const s = this._latest;
    if (!s || !s.retry_available) {
      throw new LegichainStateError(
        `retry not available from state ${s?.state} ` +
          `(attempt ${s?.current_attempt}/${s?.max_attempts})`,
      );
    }
    await this._api.requestRetry(this.applicationId, this.clientToken, reason);
    await this.refresh();
  }

  async extendTtl(): Promise<void> {
    await this._api.extendTtl(this.applicationId, this.clientToken);
    await this.refresh();
  }
}
