plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.whispertoinput"
    compileSdk = 34
    // Pinned so a CI runner's default NDK doesn't drift out from under the native build.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        // Private fork: distinct from the upstream application id so both can be installed
        // side by side and upstream updates never overwrite this build.
        applicationId = "com.yair.whispergroqinput"
        minSdk = 24
        targetSdk = 34

        // Native inference engine for the "Local model" transcription backend (whisper.cpp,
        // vendored as a submodule under src/main/cpp/whisper.cpp). Only the two ABIs that cover
        // real devices are built; x86/x86_64 emulator support isn't worth doubling native build time.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
        // Overridden by the Release/Build workflows via -PappVersionCode/-PappVersionName, so
        // the version typed into the Release workflow is the single source of truth and never
        // needs to be bumped here by hand. These defaults are only for a plain local build, and
        // should track the last version actually published.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 6
        versionName = project.findProperty("appVersionName") as String? ?: "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only the languages this fork actually ships translations for.
        resourceConfigurations += listOf("en", "iw")
    }

    signingConfigs {
        create("release") {
            // Populated from the release workflow's secrets. When the variables are absent
            // (any local build), the release build type is simply left unsigned.
            val keystoreFile = System.getenv("RELEASE_KEYSTORE_FILE")
            if (!keystoreFile.isNullOrEmpty()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!System.getenv("RELEASE_KEYSTORE_FILE").isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("io.ktor:ktor-client-okhttp:2.3.6")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}