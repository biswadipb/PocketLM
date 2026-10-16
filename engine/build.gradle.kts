plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.arm.aichat"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
                // Build libraries that also work on phones with 16 KB memory pages (required for newer Android).
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }
    buildTypes {
        // Debug builds also include x86_64 so they run on the emulator; release builds are for real phones.
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
