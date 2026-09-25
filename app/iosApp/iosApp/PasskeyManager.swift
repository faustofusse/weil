import AuthenticationServices
import Foundation
import SharedUI

private extension Data {
    init?(base64url: String) {
        var s = base64url.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        self.init(base64Encoded: s)
    }

    var base64url: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: ["="])
    }
}

enum PasskeyError: LocalizedError {
    case invalidOptions
    case unexpectedCredential

    var errorDescription: String? {
        switch self {
        case .invalidOptions: return "invalid passkey options from server"
        case .unexpectedCredential: return "unexpected credential type"
        }
    }
}

final class PasskeyManager: NSObject, PasskeyCeremony, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    private static let rpId = "finance.fausto.ar"
    private var continuation: CheckedContinuation<String, Error>?
    /// When the current sign-in request was handed to the system. See `didCompleteWithError`.
    private var assertionStartedAt: Date?

    /// A cancellation this soon after `performRequests` cannot have come from a
    /// person: no sheet was ever shown. With `.preferImmediatelyAvailableCredentials`
    /// that is how iOS says "no passkey for this app on this device" (it reports
    /// `.canceled`, not a dedicated code), and the caller must register then.
    private static let silentCancelWindow: TimeInterval = 1.0

    func create(optionsJson: String, completionHandler: @escaping (String?, Error?) -> Void) {
        run(optionsJson: optionsJson, completionHandler: completionHandler, register: true)
    }

    func assert(optionsJson: String, completionHandler: @escaping (String?, Error?) -> Void) {
        run(optionsJson: optionsJson, completionHandler: completionHandler, register: false)
    }

    private func run(optionsJson: String, completionHandler: @escaping (String?, Error?) -> Void, register: Bool) {
        Task {
            do {
                guard let data = optionsJson.data(using: .utf8),
                      let options = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw PasskeyError.invalidOptions
                }
                let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: Self.rpId)
                let request: ASAuthorizationRequest
                if register {
                    let user = options["user"] as? [String: Any] ?? [:]
                    request = provider.createCredentialRegistrationRequest(
                        challenge: Data(base64url: options["challenge"] as? String ?? "") ?? Data(),
                        name: user["name"] as? String ?? "",
                        userID: Data(base64url: user["id"] as? String ?? "") ?? Data(),
                    )
                } else {
                    request = provider.createCredentialAssertionRequest(
                        challenge: Data(base64url: options["challenge"] as? String ?? "") ?? Data(),
                    )
                }
                let controller = ASAuthorizationController(authorizationRequests: [request])
                let result: String = try await withCheckedThrowingContinuation { cont in
                    self.continuation = cont
                    controller.delegate = self
                    controller.presentationContextProvider = self
                    if register {
                        self.assertionStartedAt = nil
                        controller.performRequests()
                    } else {
                        // Only passkeys already on this device (iCloud Keychain
                        // included): no "use a nearby device" QR when there is none.
                        self.assertionStartedAt = Date()
                        controller.performRequests(options: .preferImmediatelyAvailableCredentials)
                    }
                }
                completionHandler(result, nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        let cont = continuation
        continuation = nil
        guard let cont else { return }
        do {
            if let reg = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration {
                let response: [String: Any] = [
                    "id": reg.credentialID.base64url,
                    "rawId": reg.credentialID.base64url,
                    "type": "public-key",
                    "response": [
                        "clientDataJSON": reg.rawClientDataJSON.base64url,
                        "attestationObject": (reg.rawAttestationObject ?? Data()).base64url,
                        "transports": ["internal"],
                    ],
                    "clientExtensionResults": [String: Any](),
                ]
                cont.resume(returning: try Self.jsonString(response))
            } else if let assertion = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion {
                var inner: [String: Any] = [
                    "clientDataJSON": assertion.rawClientDataJSON.base64url,
                    "authenticatorData": assertion.rawAuthenticatorData.base64url,
                    "signature": (assertion.signature ?? Data()).base64url,
                ]
                if let userHandle = assertion.userID?.base64url {
                    inner["userHandle"] = userHandle
                }
                let response: [String: Any] = [
                    "id": assertion.credentialID.base64url,
                    "rawId": assertion.credentialID.base64url,
                    "type": "public-key",
                    "response": inner,
                    "clientExtensionResults": [String: Any](),
                ]
                cont.resume(returning: try Self.jsonString(response))
            } else {
                cont.resume(throwing: PasskeyError.unexpectedCredential)
            }
        } catch {
            cont.resume(throwing: error)
        }
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let cont = continuation
        continuation = nil
        let nsError = error as NSError
        if nsError.domain == ASAuthorizationError.errorDomain,
           let code = ASAuthorizationError.Code(rawValue: nsError.code) {
            let startedAt = assertionStartedAt
            assertionStartedAt = nil
            let silent = startedAt.map { Date().timeIntervalSince($0) < Self.silentCancelWindow } ?? false
            switch code {
            case .canceled where silent:
                cont?.resume(throwing: NSError(
                    domain: "ar.fausto.finance.passkey",
                    code: 1002,
                    userInfo: [NSLocalizedDescriptionKey: "passkey not found"],
                ))
            case .canceled:
                cont?.resume(throwing: NSError(
                    domain: "ar.fausto.finance.passkey",
                    code: 1001,
                    userInfo: [NSLocalizedDescriptionKey: "passkey cancelled"],
                ))
            case .unknown, .notHandled:
                cont?.resume(throwing: NSError(
                    domain: "ar.fausto.finance.passkey",
                    code: 1002,
                    userInfo: [NSLocalizedDescriptionKey: "passkey not found"],
                ))
            default:
                cont?.resume(throwing: error)
            }
        } else {
            cont?.resume(throwing: error)
        }
    }

    // MARK: WebAuthn Signal API (iOS 26+; a no-op before)

    func signalUnknownCredential(rpId: String, credentialId: String, completionHandler: @escaping (Error?) -> Void) {
        guard #available(iOS 26.0, *), let id = Data(base64url: credentialId) else { return completionHandler(nil) }
        Task {
            try? await ASCredentialUpdater().reportUnknownPublicKeyCredential(relyingPartyIdentifier: rpId, credentialID: id)
            completionHandler(nil)
        }
    }

    func signalAcceptedCredentials(rpId: String, userHandle: String, credentialIds: [String], completionHandler: @escaping (Error?) -> Void) {
        guard #available(iOS 26.0, *), let handle = Data(base64url: userHandle) else { return completionHandler(nil) }
        let ids = credentialIds.compactMap { Data(base64url: $0) }
        Task {
            try? await ASCredentialUpdater().reportAllAcceptedPublicKeyCredentials(
                relyingPartyIdentifier: rpId, userHandle: handle, acceptedCredentialIDs: ids,
            )
            completionHandler(nil)
        }
    }

    func signalUserDetails(rpId: String, userHandle: String, name: String, completionHandler: @escaping (Error?) -> Void) {
        guard #available(iOS 26.0, *), let handle = Data(base64url: userHandle) else { return completionHandler(nil) }
        Task {
            try? await ASCredentialUpdater().reportPublicKeyCredentialUpdate(
                relyingPartyIdentifier: rpId, userHandle: handle, newName: name,
            )
            completionHandler(nil)
        }
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }

    private static func jsonString(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object)
        return String(data: data, encoding: .utf8) ?? ""
    }
}
