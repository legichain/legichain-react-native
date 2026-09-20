/** Custom-reader compatibility interface. The packaged startKyc() flow uses real native JMRTD / NFCPassportReader readers. SimulatedNfcReader is for fixtures only. */
export interface NfcReadResult {
  /** `"BAC"` or `"PACE"`. */
  protocol: "BAC" | "PACE";
  /** PACE password derivation: `"MRZ"` or `"CAN"`. */
  keyDerivation?: "MRZ" | "CAN";
  sod: Uint8Array;
  dg1?: Uint8Array;
  dg2?: Uint8Array;
  dg11?: Uint8Array;
  dg7?: Uint8Array | ArrayBuffer | string;
  dg12?: Uint8Array | ArrayBuffer | string;
  dg13?: Uint8Array | ArrayBuffer | string;
  deviceAttestation?: Record<string, unknown>;
  dg14?: Uint8Array;
  dg15?: Uint8Array;
  /** AA challenge response if DG15 was present + AA ran. */
  activeAuthentication?: Uint8Array;
}

export interface NfcReader {
  /** Read the chip using the supplied MRZ for BAC/PACE password
   *  derivation. All three values come from the visual MRZ on the
   *  document. Date strings are YYMMDD. */
  read(input: {
    documentNumber: string;
    dateOfBirthYymmdd: string;
    expiryDateYymmdd: string;
    timeoutMs?: number;
  }): Promise<NfcReadResult>;

  /** Cancel any in-flight read. */
  cancel(): Promise<void>;
}

/** Returns a canned result after a delay. Use it during UI dev. */
export class SimulatedNfcReader implements NfcReader {
  constructor(
    private readonly fixture: NfcReadResult,
    private readonly delayMs: number = 2000,
  ) {}

  async read(): Promise<NfcReadResult> {
    await new Promise((r) => setTimeout(r, this.delayMs));
    return this.fixture;
  }

  async cancel(): Promise<void> {/* no-op */}
}
