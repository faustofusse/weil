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

/**
 * Headless UI check — renders a screen offscreen into a PNG (no window, no
 * stolen focus). See Shot.kt.
 *
 *   ./gradlew :app:desktopApp:shot
 *   ./gradlew :app:desktopApp:shot -Pshot.out=/tmp/home.png -Pshot.seconds=6
 */
tasks.register<JavaExec>("shot") {
    group = "verification"
    description = "Renders the app offscreen into a PNG (headless UI check)."
    mainClass = "ar.fausto.weil.ShotKt"
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("java.awt.headless", "true")
    // Belt and braces on macOS: even if something forces AWT out of headless
    // mode, this keeps the process out of the Dock and away from the fore.
    systemProperty("apple.awt.UIElement", "true")
    args(
        (project.findProperty("shot.out") as String?)
            ?: layout.buildDirectory.file("shots/home.png").get().asFile.path,
        (project.findProperty("shot.seconds") as String?) ?: "5",
    )
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