import SwiftUI
import Teslable

// The smallest app that links Teslable through Swift Package Manager. It does not talk to a car: the point of this
// target is that the release XCFramework links and runs in an app bundle (see ConsumerAppTests).
@main
struct ConsumerApp: App {
    var body: some Scene {
        WindowGroup {
            // DEFAULT_ALIAS is documented in 13-lib-pairing.md §Key store API (Swift: VehicleKeyStoreCompanion.shared).
            Text("Teslable linked: \(VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS)")
        }
    }
}
