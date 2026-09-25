import Foundation
import SharedUI
import UniformTypeIdentifiers

/// The app's half of the share-sheet import (the Share Extension writes with
/// `ShareDrop`; directory and sidecar keys must match). Documents end up in
/// `SharedImportInbox`, the same slot Android's ACTION_SEND fills, so the
/// Kotlin side (RootScreen → ImportReviewRoute) is shared.
enum SharedImportHandoff {
    private static let appGroup = "group.ar.fausto.finance"
    private static let directory = "Drops"

    /// `weil://import?id=…` from the extension, or a file handed over with
    /// "Open in Weil" / AirDrop (CFBundleDocumentTypes).
    @MainActor
    static func handle(_ url: URL) {
        if url.isFileURL {
            offerFile(url)
        } else {
            drain()
        }
    }

    /// Picks up whatever the extension parked. Also run on every foreground,
    /// because opening the app from an extension is best effort. Only the
    /// newest document is offered (the inbox holds one); the rest are dropped.
    @MainActor
    static func drain() {
        guard let dir = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent(directory, isDirectory: true),
            let entries = try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: [.contentModificationDateKey])
        else { return }
        let sidecars = entries
            .filter { $0.pathExtension == "json" }
            .sorted { modified($0) > modified($1) }
        guard let newest = sidecars.first else { return }
        let payload = newest.deletingPathExtension().appendingPathExtension("bin")
        let document = readDrop(sidecar: newest, payload: payload)
        for entry in entries { try? FileManager.default.removeItem(at: entry) }
        if let document { SharedImportInbox.shared.offer(document: document) }
    }

    private static func readDrop(sidecar: URL, payload: URL) -> PickedDocument? {
        guard let json = try? Data(contentsOf: sidecar),
              let meta = try? JSONSerialization.jsonObject(with: json) as? [String: String],
              let mime = meta["mimeType"],
              let data = try? Data(contentsOf: payload), !data.isEmpty
        else { return nil }
        return PickedDocument(bytes: KotlinByteArray.from(data), mimeType: mime, name: meta["name"])
    }

    @MainActor
    private static func offerFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try? Data(contentsOf: url)
        // Without in-place opening iOS copies the file into Documents/Inbox;
        // once read it is ours to delete.
        if url.deletingLastPathComponent().lastPathComponent == "Inbox" {
            try? FileManager.default.removeItem(at: url)
        }
        guard let data, !data.isEmpty else { return }
        let mime = mimeType(for: url)
        guard ImportRepositoryKt.IMPORTABLE_MIME_TYPES.contains(mime) else { return }
        SharedImportInbox.shared.offer(document: PickedDocument(
            bytes: KotlinByteArray.from(data), mimeType: mime, name: url.lastPathComponent))
    }

    private static func mimeType(for url: URL) -> String {
        if url.pathExtension.lowercased() == "csv" { return "text/csv" }
        return UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"
    }

    private static func modified(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?
            .contentModificationDate ?? .distantPast
    }
}
