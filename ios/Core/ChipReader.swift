import Foundation
import CoreNFC
import NFCPassportReader

@MainActor final class ChipReader {
    private let reader = PassportReader()
    var available: Bool { NFCTagReaderSession.readingAvailable }
    func read(_ mrz: MRZAccess, message: @escaping () -> String) async throws -> [String: Any] {
        guard available else { throw FlowError.invalidConfiguration }
        let number = mrz.number.padding(toLength: 9, withPad: "<", startingAt: 0)
        let key = number + MRZAccess.digit(number) + mrz.birth + MRZAccess.digit(mrz.birth) + mrz.expiry + MRZAccess.digit(mrz.expiry)
        // The library negotiates PACE (MRZ) or BAC and performs secure
        // messaging. No hand-written cryptography and no simulated success.
        let passport = try await reader.readPassport(mrzKey: key, tags: [], skipSecureElements: true,
                                                     skipCA: true, customDisplayMessage: { _ in message() })
        guard let sod = passport.dataGroupsRead[.SOD], let dg1 = passport.dataGroupsRead[.DG1],
              let dg2 = passport.dataGroupsRead[.DG2] else { throw FlowError.invalidResponse }
        var body: [String: Any] = ["protocol": passport.PACEStatus == .success ? "PACE" : "BAC", "key_derivation": "MRZ",
                                  "sod_b64": Data(sod.data).base64EncodedString(), "dg1_b64": Data(dg1.data).base64EncodedString(),
                                  "dg2_b64": Data(dg2.data).base64EncodedString()]
        for (id, name) in [(DataGroupId.DG7, "dg7_b64"), (.DG11, "dg11_b64"), (.DG12, "dg12_b64"), (.DG14, "dg14_b64"), (.DG15, "dg15_b64")] {
            if let value = passport.dataGroupsRead[id] { body[name] = Data(value.data).base64EncodedString() }
        }
        if !passport.activeAuthenticationSignature.isEmpty {
            body["active_authentication_b64"] = Data(passport.activeAuthenticationSignature).base64EncodedString()
            body["device_attestation"] = ["aa_challenge_b64": Data(passport.activeAuthenticationChallenge).base64EncodedString()]
        }
        return body
    }
}

struct MRZAccess {
    let number: String; let birth: String; let expiry: String; let raw: String
    static func digit(_ value: String) -> String {
        let weights = [7, 3, 1]
        return String(value.utf8.enumerated().reduce(0) { sum, pair in
            let c = Int(pair.element); let n = c == 60 ? 0 : c >= 65 ? c - 55 : c - 48
            return sum + n * weights[pair.offset % 3]
        } % 10)
    }
    static func parse(_ text: String) -> MRZAccess? {
        let lines = text.uppercased().components(separatedBy: .newlines).map { $0.replacingOccurrences(of: " ", with: "") }
            .filter { [30, 36, 44].contains($0.count) && $0.range(of: "^[A-Z0-9<]+$", options: .regularExpression) != nil }
        func s(_ line: String, _ range: Range<Int>) -> String { String(Array(line)[range]) }
        func ok(_ value: String, _ digit: String) -> Bool { Self.digit(value) == digit }
        guard lines.count >= 2 else { return nil }
        for i in 0..<(lines.count - 1) {
            let a = lines[i], b = lines[i + 1]
            if a.count == 30 && b.count == 30 && i + 2 < lines.count && lines[i + 2].count == 30 {
                if ok(s(a,5..<14),s(a,14..<15)) && ok(s(b,0..<6),s(b,6..<7)) && ok(s(b,8..<14),s(b,14..<15)) &&
                    ok(s(a,5..<30)+s(b,0..<7)+s(b,8..<15)+s(b,18..<29),s(b,29..<30)) {
                    return MRZAccess(number:s(a,5..<14).replacingOccurrences(of:"<",with:""),birth:s(b,0..<6),expiry:s(b,8..<14),raw:a+"\n"+b+"\n"+lines[i+2])
                }
            } else if (a.count == 44 && b.count == 44) || (a.count == 36 && b.count == 36) {
                let end = b.count - 1
                if ok(s(b,0..<9),s(b,9..<10)) && ok(s(b,13..<19),s(b,19..<20)) && ok(s(b,21..<27),s(b,27..<28)) &&
                    ok(s(b,0..<10)+s(b,13..<20)+s(b,21..<end),s(b,end..<(end+1))) {
                    return MRZAccess(number:s(b,0..<9).replacingOccurrences(of:"<",with:""),birth:s(b,13..<19),expiry:s(b,21..<27),raw:a+"\n"+b)
                }
            }
        }
        return nil
    }
}
