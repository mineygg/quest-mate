plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.mineygg.questmate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mineygg.questmate"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("questmateVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("questmateVersionName") as String?) ?: "1.2.0"
    }

    // Release signing key comes from the environment (CI secrets). Without it, builds fall back to the
    // debug key, which is fine locally but must not be published.
    val releaseStore = System.getenv("QUESTMATE_KEYSTORE_FILE")?.let { file(it) }
    signingConfigs {
        if (releaseStore != null && releaseStore.exists()) {
            create("questmate") {
                storeFile = releaseStore
                storePassword = System.getenv("QUESTMATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QUESTMATE_KEY_ALIAS")
                keyPassword = System.getenv("QUESTMATE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 removes unused code and resources from the libraries (Compose, OkHttp, jsoup, Tink ...).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("questmate") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // The app is English only: don't ship the translated strings of every library.
    androidResources { localeFilters += "en" }

    // Google's signing-block dependency list: not needed, and a little bigger every build.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests { isIncludeAndroidResources = true }
    }

    packaging {
        // zstd-jni ships prebuilt (already stripped) libraries; without an NDK AGP can't strip them
        // and warns. Keep them as they are.
        jniLibs { keepDebugSymbols += "**/libzstd-jni*.so" }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

ktlint {
    version.set("1.3.1")
    filter { exclude("**/etf/**") }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // zstd-stream gateway compression (JNI, so the Android aar, not the jar).
    implementation("com.github.luben:zstd-jni:1.5.7-4@aar")
    implementation("org.brotli:dec:0.1.2") // Brotli decoding for REST responses (Accept-Encoding: br)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Test-only: never packaged into the APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
