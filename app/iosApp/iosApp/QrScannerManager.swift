import AVFoundation
import SharedUI
import UIKit
import VisionKit

enum QrScannerError: LocalizedError {
    case unsupported
    case unavailable

    var errorDescription: String? {
        switch self {
        case .unsupported: return "this device cannot scan QR codes"
        case .unavailable: return "camera unavailable (permission denied or in use)"
        }
    }
}

/// System QR scanning via VisionKit's DataScannerViewController, presented full
/// screen. Resumes the Kotlin continuation with the QR payload, or nil when the
/// user cancels. Following PasskeyManager's bridge style.
final class QrScannerManager: NSObject, QrScanner, DataScannerViewControllerDelegate {
    private var continuation: CheckedContinuation<String?, Error>?
    private var container: UIViewController?

    func scan(completionHandler: @escaping (String?, Error?) -> Void) {
        Task { @MainActor in
            do {
                completionHandler(try await runScan(), nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }

    @MainActor
    private func runScan() async throws -> String? {
        guard DataScannerViewController.isSupported else {
            throw QrScannerError.unsupported
        }
        if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
            guard await AVCaptureDevice.requestAccess(for: .video) else {
                throw QrScannerError.unavailable
            }
        }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized,
              DataScannerViewController.isAvailable else {
            throw QrScannerError.unavailable
        }
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            self.presentScanner()
        }
    }

    @MainActor
    private func presentScanner() {
        let dataScanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: true,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: false,
        )
        dataScanner.delegate = self

        // The scanner's view hierarchy is private, so overlay the Cancel
        // button from a container view controller instead.
        let wrapper = UIViewController()
        wrapper.view.backgroundColor = .black
        wrapper.addChild(dataScanner)
        dataScanner.view.translatesAutoresizingMaskIntoConstraints = false
        wrapper.view.addSubview(dataScanner.view)
        dataScanner.didMove(toParent: wrapper)
        NSLayoutConstraint.activate([
            dataScanner.view.topAnchor.constraint(equalTo: wrapper.view.topAnchor),
            dataScanner.view.bottomAnchor.constraint(equalTo: wrapper.view.bottomAnchor),
            dataScanner.view.leadingAnchor.constraint(equalTo: wrapper.view.leadingAnchor),
            dataScanner.view.trailingAnchor.constraint(equalTo: wrapper.view.trailingAnchor),
        ])

        var cancelConfig = UIButton.Configuration.gray()
        cancelConfig.title = "Cancel"
        cancelConfig.buttonSize = .large
        let cancel = UIButton(configuration: cancelConfig, primaryAction: UIAction { [weak self] _ in
            self?.finish(nil, nil)
            self?.dismissScanner()
        })
        cancel.translatesAutoresizingMaskIntoConstraints = false
        wrapper.view.addSubview(cancel)
        NSLayoutConstraint.activate([
            cancel.topAnchor.constraint(equalTo: wrapper.view.safeAreaLayoutGuide.topAnchor, constant: 16),
            cancel.trailingAnchor.constraint(equalTo: wrapper.view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
        ])

        container = wrapper
        topViewController()?.present(wrapper, animated: true) {
            do {
                try dataScanner.startScanning()
            } catch {
                self.finish(nil, error)
                self.dismissScanner()
            }
        }
    }

    @MainActor
    private func dismissScanner() {
        container?.dismiss(animated: true)
        container = nil
    }

    @MainActor
    private func finish(_ value: String?, _ error: Error?) {
        guard let continuation else { return }
        self.continuation = nil
        if let error {
            continuation.resume(throwing: error)
        } else {
            continuation.resume(returning: value)
        }
    }

    private func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        var top = scene?.keyWindow?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }

    // MARK: DataScannerViewControllerDelegate

    func dataScanner(
        _ dataScanner: DataScannerViewController,
        didAdd addedItems: [RecognizedItem],
        allItems: [RecognizedItem]
    ) {
        guard continuation != nil else { return }
        for item in addedItems {
            guard case let .barcode(barcode) = item,
                  let payload = barcode.payloadStringValue else { continue }
            dataScanner.stopScanning()
            finish(payload, nil)
            dismissScanner()
            return
        }
    }
}
