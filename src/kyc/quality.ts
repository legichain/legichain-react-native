/**
 * On-device image-quality pre-flight gate. Pure JS so it runs in any
 * RN runtime without native modules.
 */

import { LegichainCaptureRejected } from "../errors";

export interface ImageQualityGateOptions {
  /** Laplacian variance threshold. Below = blurred. Default 100. */
  blurThreshold?: number;
  /** Random pixel sample size. Default 512 — fast, accurate enough. */
  sampleCount?: number;
}

export class ImageQualityGate {
  private readonly blurThreshold: number;
  private readonly sampleCount: number;

  constructor(opts: ImageQualityGateOptions = {}) {
    this.blurThreshold = opts.blurThreshold ?? 100;
    this.sampleCount = opts.sampleCount ?? 512;
  }

  /** Returns null on pass, or a short reason string. */
  evaluate(input: {
    grayscale: Uint8Array;
    width: number;
    height: number;
  }): string | null {
    const { grayscale, width, height } = input;
    if (width < 800 || height < 600) return "image_too_small";
    const variance = laplacianVariance(
      grayscale,
      width,
      height,
      this.sampleCount,
    );
    if (variance < this.blurThreshold) return "image_blurred";
    return null;
  }

  /** Throw a typed error instead of returning. */
  mustPass(input: {
    grayscale: Uint8Array;
    width: number;
    height: number;
  }): void {
    const reason = this.evaluate(input);
    if (reason) throw new LegichainCaptureRejected(reason);
  }
}

function laplacianVariance(
  bytes: Uint8Array,
  width: number,
  height: number,
  samples: number,
): number {
  if (bytes.length < width * height || width < 3 || height < 3) return 0;
  const vals = new Float64Array(samples);
  for (let i = 0; i < samples; i++) {
    const x = 1 + ((Math.random() * (width - 2)) | 0);
    const y = 1 + ((Math.random() * (height - 2)) | 0);
    const c = bytes[y * width + x] ?? 0;
    const up = bytes[(y - 1) * width + x] ?? 0;
    const dn = bytes[(y + 1) * width + x] ?? 0;
    const lf = bytes[y * width + x - 1] ?? 0;
    const rt = bytes[y * width + x + 1] ?? 0;
    vals[i] = 4 * c - up - dn - lf - rt;
  }
  let mean = 0;
  for (const v of vals) mean += v;
  mean /= vals.length;
  let sumSq = 0;
  for (const v of vals) {
    const d = v - mean;
    sumSq += d * d;
  }
  return sumSq / vals.length;
}

/** Find ICAO MRZ-style lines (OCR-B `[A-Z0-9<]+`) within OCR output.
 *  Returns the matched run if at least two consecutive lines look like
 *  MRZ; otherwise null. Pair with platform OCR
 *  (`@react-native-ml-kit/text-recognition`, Vision via TurboModule). */
export function detectMrzLines(ocrLines: string[]): string | null {
  const stripped = ocrLines.map((l) => l.replace(/\s+/g, ""));
  const mrzRe = /^[A-Z0-9<]+$/;
  const mrz: string[] = [];
  for (const line of stripped) {
    if (line.length < 30) {
      if (mrz.length >= 2) break;
      mrz.length = 0;
      continue;
    }
    if (mrzRe.test(line)) {
      mrz.push(line);
    } else if (mrz.length >= 2) {
      break;
    } else {
      mrz.length = 0;
    }
  }
  return mrz.length >= 2 ? mrz.join("\n") : null;
}
