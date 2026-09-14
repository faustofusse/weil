import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

dependencies {
    implementation(project(":app:sharedUI"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    // Dev-only fake Database backing the desktop UI harness (Phase 1). Not
    // used on Android/iOS — those talk to the real Turso sync engine.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
}

compose.desktop {
    application {
        mainClass = "ar.fausto.weil.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ar.fausto.weil"
            packageVersion = "1.0.0"
        }
    }
}