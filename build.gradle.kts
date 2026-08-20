plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
}

// The iOS app is a plain Xcode project (app/iosApp), so there is no KMP
// "run" task for it. This wraps xcodebuild + simctl; the Xcode build phase
// in turn calls back into Gradle (embedAndSignAppleFrameworkForXcode).
// Optionally pick a device: ./gradlew iosSimulatorRun -PiosSimulator="iPhone 16"
tasks.register<Exec>("iosSimulatorRun") {
    group = "run"
    description = "Builds the iOS app and launches it in an iOS simulator."
    commandLine("bash", "scripts/ios-simulator-run.sh")
    (project.findProperty("iosSimulator") as String?)?.let { args(it) }
}