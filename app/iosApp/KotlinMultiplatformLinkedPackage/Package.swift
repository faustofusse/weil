// swift-tools-version: 5.9
import PackageDescription
let package = Package(
  name: "KotlinMultiplatformLinkedPackage",
  platforms: [
    .iOS("15.0")
  ],
  products: [
    .library(
      name: "KotlinMultiplatformLinkedPackage",
      type: .none,
      targets: ["KotlinMultiplatformLinkedPackage"]
    )
  ],
  dependencies: [
    .package(
      url: "https://github.com/tursodatabase/libsql-swift",
      from: "0.1.1"
    )
  ],
  targets: [
    .target(
      name: "KotlinMultiplatformLinkedPackage",
      dependencies: [
        .product(
          name: "Libsql",
          package: "libsql-swift"
        )
      ]
    )
  ]
)
