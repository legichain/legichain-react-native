// LegichainKYCClient.swift
//
// Faz G-7.5 — iOS reference client for the Legichain KYC pipeline.
//
// Implements the full mobile flow described in docs/MOBILE-SDK-GUIDE.md:
//
//   1. POST   /v1/kyc/applications          (tenant Bearer)
//   2. POST   /v1/kyc/applications/:id/documents (front, back/single)
//   3. POST   /v1/kyc/applications/:id/nfc   (optional)
//   4. POST   /v1/kyc/applications/:id/selfie
//   5. POST   /v1/kyc/applications/:id/liveness
//   6. POST   /v1/kyc/applications/:id/submit
//   7. GET    /v1/kyc/applications/:id/status  (polling)
//   8. GET    /v1/kyc/applications/:id/events  (SSE, alternative to #7)
//   9. POST   /v1/kyc/applications/:id/retry   (after rejection / soft fail)
//
// Pre-flight quality gates use Vision framework so blurred or off-angle
// frames never leave the device.

import Foundation
import Vision
import UIKit

public enum KYCError: Error, LocalizedError {
    case httpError(status: Int, body: String)
    case missingClientToken
    case captureQualityRejected(reason: String)
    case mrzNotDetected
    case decodingFailure

    public var errorDescription: String? {
        switch self {
        case .httpError(let status, let body):
            return "HTTP \(status): \(body)"
        case .missingClientToken:
            return "Application not started — call createApplication() first"
        case .captureQualityRejected(let reason):
            return "Capture quality too low: \(reason)"
        case .mrzNotDetected:
            return "MRZ not detected in document image"
        case .decodingFailure:
            return "Response decode failed"
        }
    }
}

/// Pre-flight image quality gate. Returns nil when the image is good
/// enough to upload; otherwise returns the rejection reason so the SDK
/// host can show "kartı düz tutun" / "ışık yansıması var" etc.
public struct ImageQualityGate {
    /// Laplacian-variance threshold. Below this = blurred.
    public static let blurThreshold: Double = 100.0

    public static func evaluate(_ image: UIImage) -> String? {
        guard let cg = image.cgImage else { return "no_cgimage" }
        // Run a Vision request to detect the document outline.
        let req = VNDetectRectanglesRequest()
        req.minimumAspectRatio = VNAspectRatio(0.55)
        req.maximumAspectRatio = VNAspectRatio(0.75)
        req.minimumConfidence = 0.8
        req.maximumObservations = 1
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])
        try? handler.perform([req])
        guard let observation = req.results?.first as? VNRectangleObservation else {
            return "document_outline_not_found"
        }
        if observation.confidence < 0.85 {
            return "document_outline_low_confidence"
        }
        // Blur check via Laplacian variance — approximated by computing
        // the variance of a 3x3 Laplacian kernel over a CIImage.
        let variance = Self.laplacianVariance(cgImage: cg)
        if variance < blurThreshold {
            return "image_blurred"
        }
        return nil
    }

    /// Approximate Laplacian variance — host should replace with a
    /// fast implementation (Metal Performance Shaders or Accelerate)
    /// if running on every frame. Reference impl uses CoreImage.
    private static func laplacianVariance(cgImage: CGImage) -> Double {
        let ci = CIImage(cgImage: cgImage)
        let context = CIContext(options: nil)
        guard let lap = CIFilter(name: "CIConvolution3X3", parameters: [
            kCIInputImageKey: ci,
            "inputWeights": CIVector(values: [0, -1, 0, -1, 4, -1, 0, -1, 0], count: 9),
            "inputBias": NSNumber(value: 0.5),
        ])?.outputImage else { return 0 }
        guard let cgOut = context.createCGImage(lap, from: lap.extent) else { return 0 }
        // Sample 512 random pixels and compute variance of the
        // luminance channel.
        let width = cgOut.width
        let height = cgOut.height
        guard let data = cgOut.dataProvider?.data,
              let bytes = CFDataGetBytePtr(data) else { return 0 }
        let bpr = cgOut.bytesPerRow
        let count = 512
        var samples: [Double] = []
        samples.reserveCapacity(count)
        for _ in 0..<count {
            let x = Int.random(in: 0..<width)
            let y = Int.random(in: 0..<height)
            let r = Double(bytes[y * bpr + x * 4 + 0])
            let g = Double(bytes[y * bpr + x * 4 + 1])
            let b = Double(bytes[y * bpr + x * 4 + 2])
            samples.append(0.299 * r + 0.587 * g + 0.114 * b)
        }
        let mean = samples.reduce(0, +) / Double(samples.count)
        let variance = samples
            .map { ($0 - mean) * ($0 - mean) }
            .reduce(0, +) / Double(samples.count)
        return variance
    }
}

/// MRZ detector — surfaces a hit when a 3-line TD1 or 2-line TD2/TD3
/// block is recognisable. Pre-flight: only send a document to the
/// server when we already KNOW the MRZ is on the visible page.
public struct MRZDetector {
    public static func detect(in image: UIImage) -> String? {
        guard let cg = image.cgImage else { return nil }
        let req = VNRecognizeTextRequest()
        req.recognitionLevel = .accurate
        req.usesLanguageCorrection = false
        req.recognitionLanguages = ["en-US"]
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])
        try? handler.perform([req])
        let observations = req.results as? [VNRecognizedTextObservation] ?? []
        let lines = observations.compactMap { $0.topCandidates(1).first?.string }
        // Look for runs of 2-3 consecutive lines whose chars are within
        // the MRZ character set [A-Z0-9<].
        let mrzLines = lines.filter { line in
            let stripped = line.replacingOccurrences(of: " ", with: "")
            guard stripped.count >= 30 else { return false }
            return stripped.allSatisfy { c in
                c.isUppercase || c.isNumber || c == "<"
            }
        }
        if mrzLines.count >= 2 {
            return mrzLines.joined(separator: "\n")
        }
        return nil
    }
}

/// Top-level KYC client.
public final class LegichainKYCClient {
    public struct Config {
        public let baseURL: URL
        public let tenantBearer: String
        public let urlSession: URLSession
        public init(baseURL: URL, tenantBearer: String,
                    urlSession: URLSession = .shared) {
            self.baseURL = baseURL
            self.tenantBearer = tenantBearer
            self.urlSession = urlSession
        }
    }

    public private(set) var applicationId: String?
    public private(set) var clientToken: String?

    private let config: Config

    public init(config: Config) {
        self.config = config
    }

    // MARK: - 1. Create application

    public struct CreateApplicationRequest: Encodable {
        public var external_reference: String?
        public var subject_external_id: String?
        public var persona_id: String?
        public var intent: String = "onboarding"
        public var document_type_allowed: [String]
        public var nfc_required: Bool = false
        public var callback_url: String?
        public var claimed_full_name: String?
        public var claimed_personal_number: String?
        public var claimed_birth_date: String?   // YYYY-MM-DD
        public var claimed_expiry_date: String?  // YYYY-MM-DD
        public var claimed_document_number: String?
        public var claimed_nationality: String?  // alpha-3
        public var claimed_issuing_country: String?
        public var claimed_sex: String?          // "M" | "F"
        public var claimed_document_type: String?

        public init(documentTypes: [String]) {
            self.document_type_allowed = documentTypes
        }
    }

    public struct CreateApplicationResponse: Decodable {
        public let application_id: String
        public let persona_id: String
        public let persona_created: Bool
        public let client_token: String
        public let state: String
        public let next_steps: [String]
        public let expires_at: String
    }

    public func createApplication(
        _ req: CreateApplicationRequest
    ) async throws -> CreateApplicationResponse {
        let r: CreateApplicationResponse = try await post(
            path: "/v1/kyc/applications",
            body: req,
            useClientToken: false,
        )
        self.applicationId = r.application_id
        self.clientToken = r.client_token
        return r
    }

    // MARK: - 2. Upload document

    public struct DocumentUploadRequest: Encodable {
        public let document_type: String
        public let side: String       // "front" | "back" | "single"
        public let mime_type: String  // "image/jpeg" | "image/png" | "image/heic"
        public let image_b64: String
        public let captured_at_client: String?
    }

    public struct DocumentUploadResponse: Decodable {
        public let document_id: String
        public let image_id: String
        public let state: String
        public let iqa_passed: Bool
        public let iqa_reason: String?
        public let extraction_status: String
    }

    public func uploadDocument(
        image: UIImage, side: String, documentType: String,
    ) async throws -> DocumentUploadResponse {
        // Pre-flight quality gate — never ship a useless image.
        if let reason = ImageQualityGate.evaluate(image) {
            throw KYCError.captureQualityRejected(reason: reason)
        }
        // For doc front + single, also require MRZ visible.
        if side != "back" && MRZDetector.detect(in: image) == nil
            && documentType != "tr_id_card" && documentType != "driver_license" {
            throw KYCError.mrzNotDetected
        }
        guard let data = image.jpegData(compressionQuality: 0.85) else {
            throw KYCError.captureQualityRejected(reason: "jpeg_encode_failed")
        }
        let req = DocumentUploadRequest(
            document_type: documentType,
            side: side,
            mime_type: "image/jpeg",
            image_b64: data.base64EncodedString(),
            captured_at_client: ISO8601DateFormatter().string(from: Date()),
        )
        return try await post(
            path: "/v1/kyc/applications/\(requireApp())/documents",
            body: req,
            useClientToken: true,
        )
    }

    // MARK: - 3. Selfie + liveness — abbreviated; see MOBILE-SDK-GUIDE.md

    public struct SelfieUploadResponse: Decodable {
        public let selfie_id: String
        public let state: String
        public let iqa_passed: Bool
        public let iqa_reason: String?
        public let face_count: Int
    }

    public func uploadSelfie(image: UIImage) async throws -> SelfieUploadResponse {
        guard let data = image.jpegData(compressionQuality: 0.9) else {
            throw KYCError.captureQualityRejected(reason: "jpeg_encode_failed")
        }
        struct R: Encodable {
            let mime_type = "image/jpeg"
            let image_b64: String
            let is_video = false
        }
        return try await post(
            path: "/v1/kyc/applications/\(requireApp())/selfie",
            body: R(image_b64: data.base64EncodedString()),
            useClientToken: true,
        )
    }

    // MARK: - 4. Submit

    public struct SubmitResponse: Decodable {
        public let outcome: String      // "approved" | "rejected" | "manual_review"
        public let outcome_reason: String?
        public let risk_score: Double?
    }

    public func submit() async throws -> SubmitResponse {
        struct E: Encodable {}
        return try await post(
            path: "/v1/kyc/applications/\(requireApp())/submit",
            body: E(),
            useClientToken: true,
        )
    }

    // MARK: - 5. Retry

    public struct RetryRequest: Encodable {
        public var reason: String?
    }

    public struct RetryResponse: Decodable {
        public let state: String
        public let current_step: String
        public let current_attempt: Int
        public let max_attempts: Int
        public let retry_available: Bool
    }

    public func retry(reason: String? = nil) async throws -> RetryResponse {
        return try await post(
            path: "/v1/kyc/applications/\(requireApp())/retry",
            body: RetryRequest(reason: reason),
            useClientToken: true,
        )
    }

    // MARK: - 6. Status (polling)

    public struct StatusResponse: Decodable {
        public let state: String
        public let current_step: String
        public let retry_available: Bool
        public let current_attempt: Int
        public let max_attempts: Int
        public let risk_score: Double?
    }

    public func status() async throws -> StatusResponse {
        return try await get(
            path: "/v1/kyc/applications/\(requireApp())/status",
            useClientToken: false,
        )
    }

    // MARK: - 7. SSE event stream
    //
    // Apple's URLSession doesn't ship native SSE support. We use a
    // long-running URLSessionDataTask and parse the chunked body
    // line-by-line. Caller passes a callback that fires for each
    // state event.

    public func subscribeEvents(
        onState: @escaping (StatusResponse) -> Void,
    ) -> URLSessionDataTask {
        var req = URLRequest(url: config.baseURL.appendingPathComponent(
            "/v1/kyc/applications/\(applicationId ?? "")/events"
        ))
        req.httpMethod = "GET"
        req.addValue("text/event-stream", forHTTPHeaderField: "Accept")
        req.addValue("Bearer \(config.tenantBearer)",
                     forHTTPHeaderField: "Authorization")
        if let token = clientToken {
            req.addValue(token, forHTTPHeaderField: "X-KYC-Client-Token")
        }
        // Real SSE parsing belongs in a dedicated delegate; this is
        // the reference sketch.
        return config.urlSession.dataTask(with: req) { data, _, _ in
            guard let data = data,
                  let text = String(data: data, encoding: .utf8) else { return }
            for line in text.split(separator: "\n") {
                if line.hasPrefix("data: "),
                   let payload = line.dropFirst(6).data(using: .utf8),
                   let parsed = try? JSONDecoder().decode(
                        StatusResponse.self, from: payload) {
                    DispatchQueue.main.async { onState(parsed) }
                }
            }
        }
    }

    // MARK: - HTTP plumbing

    private func requireApp() throws -> String {
        guard let id = applicationId else {
            throw KYCError.missingClientToken
        }
        return id
    }

    private func post<B: Encodable, R: Decodable>(
        path: String, body: B, useClientToken: Bool,
    ) async throws -> R {
        var req = URLRequest(url: config.baseURL.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.addValue("application/json", forHTTPHeaderField: "Content-Type")
        req.addValue("Bearer \(config.tenantBearer)",
                     forHTTPHeaderField: "Authorization")
        if useClientToken, let token = clientToken {
            req.addValue(token, forHTTPHeaderField: "X-KYC-Client-Token")
        }
        req.httpBody = try JSONEncoder().encode(body)
        let (data, resp) = try await config.urlSession.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw KYCError.decodingFailure
        }
        guard (200..<300).contains(http.statusCode) else {
            throw KYCError.httpError(
                status: http.statusCode,
                body: String(data: data, encoding: .utf8) ?? "",
            )
        }
        return try JSONDecoder().decode(R.self, from: data)
    }

    private func get<R: Decodable>(
        path: String, useClientToken: Bool,
    ) async throws -> R {
        var req = URLRequest(url: config.baseURL.appendingPathComponent(path))
        req.httpMethod = "GET"
        req.addValue("Bearer \(config.tenantBearer)",
                     forHTTPHeaderField: "Authorization")
        if useClientToken, let token = clientToken {
            req.addValue(token, forHTTPHeaderField: "X-KYC-Client-Token")
        }
        let (data, resp) = try await config.urlSession.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw KYCError.decodingFailure
        }
        guard (200..<300).contains(http.statusCode) else {
            throw KYCError.httpError(
                status: http.statusCode,
                body: String(data: data, encoding: .utf8) ?? "",
            )
        }
        return try JSONDecoder().decode(R.self, from: data)
    }
}
