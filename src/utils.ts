/** Internal helpers shared across sub-APIs. */

/**
 * Convert a Uint8Array / ArrayBuffer / base64 string into a base64
 * string the API expects. Pure JS — no Buffer / atob dependency, so
 * it works in any RN runtime (Hermes, JSC) and bare Node.
 */
export function toBase64(
  input: Uint8Array | ArrayBuffer | string,
): string {
  if (typeof input === "string") return input;
  const bytes =
    input instanceof Uint8Array ? input : new Uint8Array(input);
  // Standard base64 alphabet.
  const TABLE =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i] ?? 0;
    const b1 = bytes[i + 1] ?? 0;
    const b2 = bytes[i + 2] ?? 0;
    out += TABLE[b0 >> 2];
    out += TABLE[((b0 & 0x03) << 4) | (b1 >> 4)];
    out += i + 1 < bytes.length ? TABLE[((b1 & 0x0f) << 2) | (b2 >> 6)] : "=";
    out += i + 2 < bytes.length ? TABLE[b2 & 0x3f] : "=";
  }
  return out;
}

export function isoDate(d: Date): string {
  const pad = (n: number): string => n.toString().padStart(2, "0");
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}
