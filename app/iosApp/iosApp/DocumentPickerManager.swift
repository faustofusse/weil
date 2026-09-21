import PhotosUI
import SharedUI
import UIKit
import UniformTypeIdentifiers

enum DocumentPickerError: LocalizedError {
    case unreadable

    var errorDescription: String? {
        switch self {
        case .unreadable: return "the selected file could not be read"
        }
    }
}

/// Picks a receipt image or statement PDF for the AI import flow. Presents a
/// UIDocumentPicker (Files, which also surfaces iCloud/Drive) — photos reach
/// it through the Photos provider, so one sheet covers both cases.
/// Bridged into Kotlin like PasskeyManager/QrScannerManager.
final class DocumentPickerManager: NSObject, DocumentPicker, UIDocumentPickerDelegate {
    private var continuation: CheckedContinuation<PickedDocument?, Error>?

    func pick(completionHandler: @escaping (PickedDocument?, Error?) -> Void) {
        Task { @MainActor in
            do {
                completionHandler(try await runPicker(), nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }

    @MainActor
    private func runPicker() async throws -> PickedDocument? {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let types: [UTType] = [.pdf, .jpeg, .png, .webP, .heic, .heif, .commaSeparatedText]
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
            picker.delegate = self
            picker.allowsMultipleSelection = false
            guard let presenter = Self.topViewController() else {
                self.continuation = nil
                continuation.resume(returning: nil)
                return
            }
            presenter.present(picker, animated: true)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            finish(.success(nil))
            return
        }
        // asCopy: true hands us a file in the app's temp dir, no security scope
        // dance needed; still guarded so a read failure surfaces as an error.
        do {
            let data = try Data(contentsOf: url)
            let mime = Self.mimeType(for: url)
            finish(.success(PickedDocument(
                bytes: KotlinByteArray.from(data),
                mimeType: mime,
                name: url.lastPathComponent
            )))
        } catch {
            finish(.failure(DocumentPickerError.unreadable))
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finish(.success(nil))
    }

    private func finish(_ result: Result<PickedDocument?, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(with: result)
    }

    private static func mimeType(for url: URL) -> String {
        // A .csv can resolve to a spreadsheet UTI whose preferred MIME type is
        // not text/csv (the only spelling the worker accepts), so the
        // extension wins for this one.
        if url.pathExtension.lowercased() == "csv" {
            return "text/csv"
        }
        let type = UTType(filenameExtension: url.pathExtension)
        if let mime = type?.preferredMIMEType {
            return mime
        }
        return "application/octet-stream"
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}

