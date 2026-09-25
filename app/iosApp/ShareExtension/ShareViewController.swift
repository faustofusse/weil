import UIKit
import UniformTypeIdentifiers

/// iOS counterpart of Android's ACTION_SEND filter: shows up in the share
/// sheet for images, PDFs and CSVs, parks the document in the App Group
/// container and opens Weil, which drains it into `SharedImportInbox`
/// (see `SharedImportHandoff.swift` in the app target).
///
/// Deliberately pure Swift with no link to the Kotlin framework: share
/// extensions run under a ~120 MB memory cap and the framework alone is a
/// large share of that.
final class ShareViewController: UIViewController {
    private var started = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        Task { await run() }
    }

    @MainActor
    private func run() async {
        do {
            guard let document = try await loadDocument() else {
                finish(message: "Weil solo importa imágenes, PDF o CSV.")
                return
            }
            let id = try ShareDrop.write(document)
            var components = URLComponents()
            components.scheme = ShareDrop.urlScheme
            components.host = "import"
            components.queryItems = [URLQueryItem(name: "id", value: id)]
            if let url = components.url, await openHostApp(url) {
                extensionContext?.completeRequest(returningItems: nil)
            } else {
                // The document is already in the App Group: the app drains
                // it the next time it comes to the foreground.
                finish(message: "Listo. Abrí Weil para revisar el documento.")
            }
        } catch {
            finish(message: "No se pudo leer el archivo.")
        }
    }

    // MARK: - Reading the shared item

    private func loadDocument() async throws -> SharedDocument? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        for provider in providers {
            if let document = try await Self.load(provider) {
                return document
            }
        }
        return nil
    }

    private static let passthroughImages: [UTType] = [.jpeg, .png, .webP, .heic, .heif]

    private static func load(_ provider: NSItemProvider) async throws -> SharedDocument? {
        let name = provider.suggestedName
        if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
            let data = try await provider.data(for: .pdf)
            return SharedDocument(data: data, mimeType: "application/pdf", name: named(name, .pdf))
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.commaSeparatedText.identifier) {
            let data = try await provider.data(for: .commaSeparatedText)
            return SharedDocument(data: data, mimeType: "text/csv", name: named(name, .commaSeparatedText))
        }
        guard provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else {
            return nil
        }
        // Photos registers the original format (usually HEIC or JPEG), which
        // the worker takes as is.
        for type in passthroughImages
        where provider.hasItemConformingToTypeIdentifier(type.identifier) {
            if let data = try? await provider.data(for: type), let mime = type.preferredMIMEType {
                return SharedDocument(data: data, mimeType: mime, name: named(name, type))
            }
        }
        // Anything else (GIF, TIFF, a screenshot handed over as a UIImage
        // straight from the markup editor) is re-encoded as JPEG.
        let image: UIImage? = await withCheckedContinuation { continuation in
            if provider.canLoadObject(ofClass: UIImage.self) {
                provider.loadObject(ofClass: UIImage.self) { object, _ in
                    continuation.resume(returning: object as? UIImage)
                }
            } else {
                continuation.resume(returning: nil)
            }
        }
        guard let jpeg = image?.jpegData(compressionQuality: 0.9) else { return nil }
        return SharedDocument(data: jpeg, mimeType: "image/jpeg", name: named(name, .jpeg))
    }

    private static func named(_ base: String?, _ type: UTType) -> String? {
        guard let base, !base.isEmpty else { return nil }
        guard let ext = type.preferredFilenameExtension,
              (base as NSString).pathExtension.isEmpty
        else { return base }
        return "\(base).\(ext)"
    }

    // MARK: - Leaving

    /// Extensions may not call `UIApplication.shared`, but the application
    /// object is still in the responder chain and answers `open(_:)`.
    @MainActor
    private func openHostApp(_ url: URL) async -> Bool {
        var responder: UIResponder? = self
        while let current = responder {
            if let application = current as? UIApplication {
                return await application.open(url)
            }
            responder = current.next
        }
        return false
    }

    @MainActor
    private func finish(message: String) {
        let alert = UIAlertController(title: "Weil", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.extensionContext?.completeRequest(returningItems: nil)
        })
        present(alert, animated: true)
    }
}

private extension NSItemProvider {
    /// Reads the file representation inside the callback: the temporary file
    /// is deleted as soon as it returns.
    func data(for type: UTType) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            _ = loadFileRepresentation(forTypeIdentifier: type.identifier) { url, error in
                if let url, let data = try? Data(contentsOf: url) {
                    continuation.resume(returning: data)
                    return
                }
                // Some providers only offer in-memory data for the type.
                _ = self.loadDataRepresentation(forTypeIdentifier: type.identifier) { data, dataError in
                    if let data {
                        continuation.resume(returning: data)
                    } else {
                        continuation.resume(throwing: dataError ?? error ?? CocoaError(.fileReadUnknown))
                    }
                }
            }
        }
    }
}
