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

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    implementation(libs.libsql)
}

android {
    namespace = "ar.fausto.weil"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ar.fausto.weil"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        // Turso cloud (new rust-rewrite platform — serves the replication
        // protocol embedded replicas need). Override for other environments:
        //   ./gradlew installDebug -PlibsqlUrl=http://host:8080 -PlibsqlToken=<token>
        // or in ~/.gradle/gradle.properties.
        val libsqlUrl = (project.findProperty("libsqlUrl") as String?)
            ?: "libsql://weil-prueba-2-faustofusse.aws-us-east-1.turso.io"
        val libsqlToken = (project.findProperty("libsqlToken") as String?)
            ?: "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3ODcwMTc3MjMsImlkIjoiMDFhMDEyOGQtYWEwMS03YTBiLTgyZjMtYzI5MzZhNWY1MmNiIiwia2lkIjoiVWxab2RGd2tXZE9GXzIwRFBuc2dhNUVQXy1MS2VuZzJlSjNxM0M1SDk4NCIsInJpZCI6IjhlYWQ0NWE4LTNhMmYtNDkyNC04Y2I0LTE0NmYzMjhjZDRhMyJ9.liZJUfz_oF2asp4P9JukdVbJlsVFbVPIC4EjKn6l1GwlCFYapsoH2mFkSe1-kQC1MMGjeHgM0bWRQxKQ9lsADg"
        buildConfigField("String", "LIBSQL_URL", "\"$libsqlUrl\"")
        buildConfigField("String", "LIBSQL_AUTH_TOKEN", "\"$libsqlToken\"")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
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