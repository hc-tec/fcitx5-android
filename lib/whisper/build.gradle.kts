plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.whisper"
    ndkVersion = "28.0.13004108"

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DVOICE_WHISPER_VULKAN_BUILD=1"
                arguments += "-DVOICE_WHISPER_VULKAN_REV=6"
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.31.6"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
}
