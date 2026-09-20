import Foundation
import UIKit
import React

@objc(LegichainKyc)
final class LegichainKycModule: NSObject {
    private var active = false
    @objc static func requiresMainQueueSetup() -> Bool { true }
    @objc(start:resolver:rejecter:)
    func start(_ options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async {
            guard !self.active else { reject("BUSY","A KYC flow is already running",nil);return }
            guard let token=options["apiToken"] as? String,!token.isEmpty,
                  let url=URL(string:options["baseUrl"] as? String ?? "https://api.legichain.com"),url.scheme=="https",
                  let host=RCTPresentedViewController() else { reject("INVALID_OPTIONS","Invalid KYC configuration or presenter",nil);return }
            let config=KycOptions(apiToken:token,baseURL:url,language:options["language"] as? String ?? "tr",application:options["application"] as? [String:Any] ?? [:])
            let screen=KycViewController(options:config);self.active=true
            screen.onComplete={ result in self.active=false;resolve(result.dictionary) }
            host.present(screen,animated:true)
        }
    }
}
