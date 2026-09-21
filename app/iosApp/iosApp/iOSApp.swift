import SwiftUI
import SharedUI

@main
struct iOSApp: App {
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
        }
    }
}
