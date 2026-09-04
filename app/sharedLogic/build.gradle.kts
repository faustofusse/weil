@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
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
            // put your Multiplatform dependencies here
        }
        androidMain.dependencies {
            implementation(libs.libsql)
        }
        iosMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
    }
}

val generateLibsqlConfig by tasks.registering {
    val url = providers.gradleProperty("libsqlUrl")
        .orElse("libsql://weil-prueba-2-faustofusse.aws-us-east-1.turso.io")
    val token = providers.gradleProperty("libsqlToken")
        .orElse("eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3ODcwMTc3MjMsImlkIjoiMDFhMDEyOGQtYWEwMS03YTBiLTgyZjMtYzI5MzZhNWY1MmNiIiwia2lkIjoiVWxab2RGd2tXZE9GXzIwRFBuc2dhNUVQXy1MS2VuZzJlSjNxM0M1SDk4NCIsInJpZCI6IjhlYWQ0NWE4LTNhMmYtNDkyNC04Y2I0LTE0NmYzMjhjZDRhMyJ9.liZJUfz_oF2asp4P9JukdVbJlsVFbVPIC4EjKn6l1GwlCFYapsoH2mFkSe1-kQC1MMGjeHgM0bWRQxKQ9lsADg")

    inputs.property("libsqlUrl", url)
    inputs.property("libsqlToken", token)
    outputs.dir(layout.buildDirectory.dir("generated/libsqlConfig/kotlin"))

    doLast {
        val outDir = outputs.files.singleFile
        val file = File(outDir, "ar/fausto/weil/LibsqlConfig.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package ar.fausto.weil

            object LibsqlConfig {
                const val URL = "${url.get()}"
                const val AUTH_TOKEN = "${token.get()}"
            }
            """.trimIndent()
        )
    }
}

kotlin.sourceSets.named("iosMain") {
    kotlin.srcDir(generateLibsqlConfig)
}