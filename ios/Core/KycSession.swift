import Foundation
import CryptoKit

public struct KycOptions {
    public let apiToken: String
    public let baseURL: URL
    public let language: String
    public let application: [String: Any]
    public init(apiToken: String, baseURL: URL = URL(string: "https://api.legichain.com")!,
                language: String = "tr", application: [String: Any] = [:]) {
        self.apiToken = apiToken; self.baseURL = baseURL
        self.language = language; self.application = application
    }
}

public struct KycResult {
    public let status: String
    public let applicationId: String
    public var dictionary: [String: String] { ["status": status, "application_id": applicationId] }
}

enum FlowError: Error { case invalidConfiguration, network(Int), invalidResponse, operationFailed(String), timeout, cancelled }

// URLSession must not forward the API bearer through arbitrary HTTP redirects.
private final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

@MainActor final class KycSession {
    private let options: KycOptions
    private var base: URL
    private var token = ""
    private(set) var applicationId = ""
    private let createKey = UUID().uuidString
    private var completed: [String: Data] = [:]
    private var pending: [String: (String, Data)] = [:]
    private let http = URLSession(configuration: .ephemeral, delegate: NoRedirect(), delegateQueue: nil)
    init(_ options: KycOptions) { self.options = options; base = options.baseURL }

    func create() async throws -> [String: Any] {
        guard base.scheme == "https", !options.apiToken.isEmpty, ["tr", "en"].contains(options.language) else { throw FlowError.invalidConfiguration }
        let value = try await request("POST", "/v1/kyc/applications", body: options.application, key: createKey)
        guard let id = value["application_id"] as? String, let ct = value["client_token"] as? String else { throw FlowError.invalidResponse }
        applicationId = id; token = ct
        return try await status()
    }
    func status() async throws -> [String: Any] { try await request("GET", "/v1/kyc/applications/\(applicationId)/status") }
    func challenge() async throws -> [String: Any] {
        try await request("POST", "/v1/kyc/applications/\(applicationId)/liveness/challenge", body: ["length": 3, "ttl_seconds": 120])
    }
    func evidence(_ step: String, body: [String: Any], slot: String? = nil) async throws {
        let data = try JSONSerialization.data(withJSONObject: body, options: .sortedKeys)
        let slot = slot ?? step
        let hash = Data(SHA256.hash(data:data))
        if completed[slot] == hash { return }
        let key = pending[slot]?.1 == data ? pending[slot]!.0 : UUID().uuidString
        pending[slot] = (key, data)
        let receipt = try await request("POST", "/v2/kyc/applications/\(applicationId)/\(step)", body: body, key: key)
        guard let id = receipt["operation_id"] as? String else { throw FlowError.invalidResponse }
        let deadline = ProcessInfo.processInfo.systemUptime + 180
        while true {
            try Task.checkCancellation()
            let operation = try await request("GET", "/v2/operations/\(id)")
            let state = operation["status"] as? String
            if state == "completed" {
                pending.removeValue(forKey:slot)
                let result = operation["result"] as? [String:Any]
                let capture = result?["capture"] as? [String:Any] ?? result
                if capture?["iqa_passed"] as? Bool == false { throw FlowError.operationFailed(step) }
                completed[slot]=hash; return
            }
            if ["failed", "expired", "cancelled"].contains(state ?? "") { pending.removeValue(forKey:slot); throw FlowError.operationFailed(step) }
            if ProcessInfo.processInfo.systemUptime >= deadline { throw FlowError.timeout }
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }
    func submit() async throws -> KycResult {
        let current = try await status()
        let state = current["state"] as? String ?? ""
        if !["deciding", "approved", "rejected", "manual_review"].contains(state) {
            _ = try await request("POST", "/v1/kyc/applications/\(applicationId)/submit", body: [:])
        }
        return KycResult(status: "submitted", applicationId: applicationId)
    }
    func close() { http.invalidateAndCancel(); token = ""; pending.removeAll();completed.removeAll() }

    private func request(_ method: String, _ path: String, body: [String: Any]? = nil, key: String? = nil) async throws -> [String: Any] {
        var redirected = false
        var attempts = 0
        while true {
            guard let url = URL(string: base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else { throw FlowError.invalidConfiguration }
            var req = URLRequest(url: url); req.httpMethod = method; req.timeoutInterval = 45
            req.setValue("Bearer \(options.apiToken)", forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            if !token.isEmpty { req.setValue(token, forHTTPHeaderField: "X-KYC-Client-Token") }
            if let key { req.setValue(key, forHTTPHeaderField: "Idempotency-Key") }
            if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body, options: .sortedKeys) }
            let data: Data; let response: URLResponse
            do { (data, response) = try await http.data(for: req) }
            catch {
                try Task.checkCancellation()
                if (method == "GET" || key != nil) && attempts < 2 { attempts += 1; try await Task.sleep(nanoseconds: 1_000_000_000); continue }
                throw error
            }
            guard let res = response as? HTTPURLResponse else { throw FlowError.invalidResponse }
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            if (200..<300).contains(res.statusCode) { return json }
            if res.statusCode == 421 && !redirected {
                guard let next = json["api_base_url"] as? String,
                      ["https://tr-api.legichain.com", "https://eu-api.legichain.com"].contains(next.trimmingCharacters(in: CharacterSet(charactersIn: "/"))),
                      let url = URL(string: next) else { throw FlowError.invalidResponse }
                base = url; redirected = true; continue
            }
            if [429, 502, 503, 504].contains(res.statusCode) && (method == "GET" || key != nil) && attempts < 2 {
                attempts += 1
                let seconds = min(10, max(1, Int(res.value(forHTTPHeaderField: "Retry-After") ?? "1") ?? 1))
                try await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000); continue
            }
            throw FlowError.network(res.statusCode)
        }
    }
}
