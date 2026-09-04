// swift-tools-version: 5.9
import PackageDescription
let package = Package(
  name: "_app_sharedLogic",
  platforms: [
    .iOS("15.0")
  ],
  products: [
    .library(
      name: "_app_sharedLogic",
      type: .none,
      targets: ["_app_sharedLogic"]
    )
  ],
  dependencies: [
    .package(
      url: "https://github.com/tursodatabase/libsql-swift",
      from: "0.3.2"
    )
  ],
  targets: [
    .target(
      name: "_app_sharedLogic",
      dependencies: [
        .product(
          name: "Libsql",
          package: "libsql-swift"
        )
      ]
    )
  ]
)
