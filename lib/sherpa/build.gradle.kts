plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.sherpa"

    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
