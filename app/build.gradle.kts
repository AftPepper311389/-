plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nemoclient.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rlin.inject"
        minSdk = 28
        targetSdk = 34
        versionCode = 204
        versionName = "0.20.4"
        ndkVersion = "27.2.12479018"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }
    buildFeatures { prefab = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("mp4") }   // 彩蛋视频要以“不压缩”的形式留在 APK 里，才能直接从资源里打开
    packaging {
        // 模块的 .so 要以“不压缩 + 页对齐”的形式留在 APK 里，框架才能从 base.apk!/lib/arm64-v8a 直接加载
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    // Xposed 旧版 API：编译时可见，运行时由框架提供
    compileOnly("de.robv.android.xposed:api:82")
    // inline hook 库（字节跳动 ShadowHook），用来钩 libEGL 的 eglSwapBuffers
    implementation("com.bytedance.android:shadowhook:1.0.9")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
