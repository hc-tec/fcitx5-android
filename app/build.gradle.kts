import org.gradle.api.tasks.Sync

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val functionKitWorkspaceRoot =
    System.getenv("FUNCTION_KIT_WORKSPACE_ROOT")?.let(::file)
        ?: rootDir.resolve("../../../")
val functionKitRuntimeSdkDir = functionKitWorkspaceRoot.resolve("function-kit-runtime-sdk")
val functionKitCatalogDir = functionKitWorkspaceRoot.resolve("function-kits")
val functionKitDirectories =
    functionKitCatalogDir.listFiles()
        ?.filter { it.isDirectory && it.resolve("manifest.json").isFile }
        .orEmpty()
        .sortedBy { it.name }
val functionKitAssetsDir = layout.buildDirectory.dir("generated/function-kit-assets")
val functionKitTestAssetsDir = layout.buildDirectory.dir("generated/function-kit-test-assets")

val syncFunctionKitAssets by tasks.registering(Sync::class) {
    group = "function-kit"
    description = "Sync browser Function Kit runtime assets into the Android app bundle."
    into(functionKitAssetsDir)
    doFirst {
        check(functionKitRuntimeSdkDir.resolve("dist").isDirectory) {
            "Missing Function Kit runtime bundle directory: ${functionKitRuntimeSdkDir.resolve("dist")}"
        }
        check(functionKitDirectories.isNotEmpty()) {
            "Missing Function Kit directories under: $functionKitCatalogDir"
        }
    }
    from(functionKitRuntimeSdkDir.resolve("dist")) {
        into("function-kit-runtime-sdk/dist")
    }
    functionKitDirectories.forEach { kitDir ->
        val kitId = kitDir.name
        from(kitDir) {
            into("function-kits/$kitId")
            exclude("README.md")
            exclude("skills/**")
            exclude("tools/**")
            exclude("tests/**")
        }
    }
}

val syncFunctionKitTestAssets by tasks.registering(Sync::class) {
    group = "function-kit"
    description = "Sync browser Function Kit test fixtures into the Android test assets."
    into(functionKitTestAssetsDir)
    doFirst {
        check(functionKitDirectories.isNotEmpty()) {
            "Missing Function Kit directories under: $functionKitCatalogDir"
        }
    }
    functionKitDirectories.forEach { kitDir ->
        val fixturesDir = kitDir.resolve("tests/fixtures")
        if (fixturesDir.isDirectory) {
            from(fixturesDir) {
                into("function-kits/${kitDir.name}/tests/fixtures")
            }
        }
    }
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    sourceSets.named("main") {
        assets.srcDir(functionKitAssetsDir.get().asFile)
    }
    sourceSets.named("androidTest") {
        assets.srcDir(functionKitTestAssetsDir.get().asFile)
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
    installPrebuiltAssets = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.webkit)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}

tasks.named("preBuild") {
    dependsOn(syncFunctionKitAssets)
    dependsOn(syncFunctionKitTestAssets)
}
