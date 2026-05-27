/**
 * RFC 7807 Problem-Details translated to typed errors.
 * Branch on `code` (stable) — never on `message` (human text).
 */

export interface LegichainErrorOptions {
  status: number;
  code: string;
  message: string;
  problem?: Record<string, unknown>;
}

export class LegichainError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: Record<string, unknown>;
  constructor(opts: LegichainErrorOptions) {
    super(`LegichainError(${opts.status} ${opts.code}): ${opts.message}`);
    this.name = "LegichainError";
    this.status = opts.status;
    this.code = opts.code;
    this.problem = opts.problem ?? {};
  }
}

/** Thrown by the SDK when a pre-condition fails (e.g. calling
 *  `flow.uploadDocument` before `startFlow`). */
export class LegichainStateError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LegichainStateError";
  }
}

/** Thrown by the on-device IQA gate when the frame is rejected. */
export class LegichainCaptureRejected extends Error {
  readonly reason: string;
  constructor(reason: string) {
    super(`capture rejected: ${reason}`);
    this.name = "LegichainCaptureRejected";
    this.reason = reason;
  }
}
