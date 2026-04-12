plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.voice.core"
}

dependencies {
    testImplementation(libs.junit)
}
