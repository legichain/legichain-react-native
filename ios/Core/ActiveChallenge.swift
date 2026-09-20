import Foundation

final class ActiveChallenge {
    struct Face {
        let yaw: Double; let pitch: Double; let smile: Double; let leftEye: Double; let rightEye: Double
        var neutral: Bool { abs(yaw) < 12 && abs(pitch) < 12 && smile < 0.45 && leftEye > 0.65 && rightEye > 0.65 }
    }
    struct Action {
        let action: String; let start: Int; let end: Int
        var json: [String: Any] { ["action":action, "started_at_ms":start, "ended_at_ms":end] }
    }
    enum Phase { case instruction, ready, perform, returnToCenter, complete, failed }
    private(set) var phase = Phase.instruction
    private(set) var index = 0
    private(set) var records: [Action] = []
    private let sequence: [String]
    private let started: Int
    private var phaseSince: Int
    private var neutralSince: Int?
    private var hitSince: Int?
    private var actionSince = 0
    var action: String { sequence[min(index, sequence.count - 1)] }
    var count: Int { sequence.count }
    init(sequence: [String], started: Int) throws {
        guard !sequence.isEmpty && sequence.count <= 5 && sequence.allSatisfy({ ["blink","head_left","head_right","smile","look_up"].contains($0) }) else { throw FlowError.invalidResponse }
        self.sequence = sequence; self.started = started; phaseSince = started
    }
    func update(now: Int, face: Face?) {
        if phase == .complete || phase == .failed { return }
        if now - started > 100_000 { phase = .failed; return }
        if phase == .instruction && now - phaseSince >= 1800 { phase = .ready; phaseSince = now }
        if phase == .ready {
            if face?.neutral == true {
                if neutralSince == nil { neutralSince = now }
                if now - neutralSince! >= 500 { phase = .perform; actionSince = now; hitSince = nil }
            } else { neutralSince = nil }
        } else if phase == .perform || phase == .returnToCenter {
            if now - actionSince > 7800 { phase = .failed; return }
            guard let face else { hitSince = nil; neutralSince = nil; return }
            if phase == .perform {
                let hit: Bool
                switch action {
                case "head_left": hit = face.yaw < -22
                case "head_right": hit = face.yaw > 22
                case "look_up": hit = face.pitch > 18
                case "smile": hit = face.smile > 0.75
                case "blink": hit = face.leftEye < 0.25 && face.rightEye < 0.25
                default: hit = false
                }
                if hit {
                    if hitSince == nil { hitSince = now }
                    if action == "blink" || now - hitSince! >= 250 { phase = .returnToCenter; neutralSince = nil }
                } else { hitSince = nil }
            } else if face.neutral {
                if neutralSince == nil { neutralSince = now }
                if now - actionSince >= 1000 && now - neutralSince! >= 250 {
                    records.append(Action(action:action,start:actionSince-started,end:now-started))
                    index += 1; phase = index == sequence.count ? .complete : .instruction; phaseSince = now; neutralSince = nil
                }
            } else { neutralSince = nil }
        }
    }
}
