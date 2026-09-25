import SwiftUI
import SharedUI

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        IosBridges.shared.secureStore = KeychainStore()
        IosBridges.shared.passkeyCeremony = PasskeyManager()
        IosBridges.shared.qrScanner = QrScannerManager()
        IosBridges.shared.documentPicker = DocumentPickerManager()
        IosBridges.shared.pdfRenderer = PdfManager()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // Documents shared from other apps (Share Extension) or
                // opened with "Open in Weil"; see SharedImportHandoff.
                .onOpenURL { SharedImportHandoff.handle($0) }
                .onChange(of: scenePhase, initial: true) { _, phase in
                    if phase == .active { SharedImportHandoff.drain() }
                }
        }
    }
}
