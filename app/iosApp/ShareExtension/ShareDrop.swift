import Foundation

/// A document read out of the share sheet, before it is handed to the app.
struct SharedDocument {
    let data: Data
    let mimeType: String
    let name: String?
}

/// The extension's half of the hand-off. The app's half is
/// `SharedImportHandoff` (iosApp target); the directory layout and the
/// sidecar keys must stay in sync with it.
enum ShareDrop {
    static let appGroup = "group.ar.fausto.finance"
    static let urlScheme = "weil"
    static let directory = "Drops"

    enum Failure: Error { case noContainer }

    /// Writes `<id>.bin` and then `<id>.json` (mime + name). The app only
    /// looks at the sidecar, so a half-written payload is never picked up.
    static func write(_ document: SharedDocument) throws -> String {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)
        else { throw Failure.noContainer }
        let dir = container.appendingPathComponent(directory, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let id = UUID().uuidString
        try document.data.write(to: dir.appendingPathComponent("\(id).bin"), options: .atomic)
        var meta: [String: String] = ["mimeType": document.mimeType]
        if let name = document.name { meta["name"] = name }
        let json = try JSONSerialization.data(withJSONObject: meta)
        try json.write(to: dir.appendingPathComponent("\(id).json"), options: .atomic)
        return id
    }
}
