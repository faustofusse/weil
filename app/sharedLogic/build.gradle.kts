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

    swiftPMDependencies {
        swiftPackage(
            url = url("https://github.com/tursodatabase/libsql-swift"),
            version = from("0.3.2"),
            products = listOf(product("Libsql")),
        )
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
