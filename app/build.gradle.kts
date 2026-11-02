plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.iverlein.shinkan"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iverlein.shinkan"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // Debug builds install beside the F-Droid release: own package, own
        // name on the launcher ("Shinkan beta", src/debug/res), own settings.
        debug {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // F-Droid rejects the dependency list AGP embeds encrypted with Google's key.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        // NewPipe Extractor uses Java APIs newer than API 28 (SPEC §7.2).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates what only the beta does (SPEC §10).
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    // The player (SPEC §7): the extractor, playback, and the extractor's HTTP.
    implementation(libs.newpipe.extractor)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
