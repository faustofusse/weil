@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // New Turso sync engine (same C ABI/commit as the Android jniLibs .so):
    // headers are shared with Android, the per-target Rust staticlib lives in
    // src/iosMain/nativeLibs/<target>/libturso_sync_sdk_kit.a.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("turso") {
            defFile(project.file("src/nativeInterop/cinterop/turso.def"))
            includeDirs(project.file("src/androidMain/turso-headers"))
            extraOpts(
                "-libraryPath",
                project.file("src/iosMain/nativeLibs/${target.targetName}").absolutePath,
            )
        }
    }

    jvm()
    
    js {
        outputModuleName = "sharedLogic"
        browser()
        binaries.library()
        generateTypeScriptDefinitions()
        compilerOptions {
            target = "es2015"
            optIn.add("kotlin.js.ExperimentalJsExport")
        }
    }
    
    android {
       namespace = "ar.fausto.weil.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationKotlinxJson)
            // Arbitrary-precision integers for Decimal (see Decimal.kt): only
            // its BigInteger is used, never its BigDecimal (open rounding bugs,
            // plans/inversiones-brokers.md, question 2).
            implementation(libs.bignum)
        }
        androidMain.dependencies {
            // JNA on Android: the "jna" artifact ships both a desktop .jar and
            // an .aar with jni/<abi>/libjnidispatch.so. Gradle resolves the jar
            // by default, which breaks at runtime on Android ("Native library
            // (com/sun/jna/android-aarch64/libjnidispatch.so) not found").
            // The @aar classifier forces the Android variant; its
            // libjnidispatch.so is 16 KB-aligned (verified 5.17.0).
            implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentialsPlayServices)
            implementation(libs.androidx.securityCrypto)
            implementation(libs.play.services.codeScanner)
        }
        iosMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.clientDarwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.clientCio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
            implementation(libs.ktor.clientJs)
        }
    }
}
