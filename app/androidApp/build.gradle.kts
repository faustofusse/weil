import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:sharedUI"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.workRuntime)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "ar.fausto.weil"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "finance.fausto.ar"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // scripts/android-play-internal.sh passes these; a plain local build keeps 1 / 1.0.
        versionCode = providers.gradleProperty("weilVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("weilVersionName").orNull ?: "1.0"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "finance-debug"
            keyPassword = "android"
        }
        // Play upload key. Supplied by scripts/android-play-internal.sh as
        // ORG_GRADLE_PROJECT_* env vars so the password never hits argv.
        providers.gradleProperty("weilUploadStoreFile").orNull?.let { store ->
            create("upload") {
                storeFile = file(store)
                storePassword = providers.gradleProperty("weilUploadStorePassword").get()
                keyAlias = providers.gradleProperty("weilUploadKeyAlias").orNull ?: "upload"
                keyPassword = providers.gradleProperty("weilUploadKeyPassword").orNull
                    ?: providers.gradleProperty("weilUploadStorePassword").get()
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // jna@aar bundles ABIs no modern Android device or emulator uses.
            excludes += listOf(
                "lib/armeabi/**",
                "lib/mips/**",
                "lib/mips64/**",
            )
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}