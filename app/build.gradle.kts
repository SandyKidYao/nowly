plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.nowly.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nowly.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = providers.environmentVariable("NOWLY_VERSION_NAME").orElse("0.2.5-mvp").get()
        testInstrumentationRunner = "com.nowly.app.NowlyTestRunner"
    }

    val releaseStore = providers.environmentVariable("NOWLY_KEYSTORE_PATH").orNull
    if (releaseStore != null) {
        signingConfigs {
            create("distribution") {
                storeFile = file(releaseStore)
                storePassword = providers.environmentVariable("NOWLY_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NOWLY_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("NOWLY_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("distribution")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        warningsAsErrors = true
        // Versions are deliberately pinned as a compatible build baseline.
        // Remote "latest version" discovery is not a reproducible quality check.
        // Target SDK upgrades need their own behavior testing; the runner's installed
        // SDK inventory must not change the result for our pinned Android 36 target.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.reorderable)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
