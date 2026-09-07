import SwiftUI
import SharedUI

@main
struct iOSApp: App {
    init() {
        IosBridges.shared.secureStore = KeychainStore()
        IosBridges.shared.passkeyCeremony = PasskeyManager()
        IosBridges.shared.qrScanner = QrScannerManager()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
