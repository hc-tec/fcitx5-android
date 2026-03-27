import groovy.json.JsonSlurper
import org.gradle.api.tasks.Sync
import java.io.File

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

data class DebugAiBootstrapConfig(
    val enabled: Boolean = false,
    val providerType: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val timeoutSeconds: Int = 20
)

data class LocalOpenClawConfig(
    val providerId: String = "",
    val primaryModel: String = "",
    val baseUrl: String = "",
    val apiKey: String = ""
)

fun buildConfigStringLiteral(value: String): String =
    buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

fun readDotEnvFile(file: File): Map<String, String> {
    val values = linkedMapOf<String, String>()
    file.forEachLine { rawLine ->
        val trimmed = rawLine.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            return@forEachLine
        }
        val separator = trimmed.indexOf('=')
        if (separator <= 0) {
            return@forEachLine
        }
        val key = trimmed.substring(0, separator).trim()
        val value =
            trimmed.substring(separator + 1)
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
        if (key.isNotBlank()) {
            values[key] = value
        }
    }
    return values
}

fun lookupNestedString(root: Map<*, *>, vararg path: String): String {
    var current: Any? = root
    for (segment in path) {
        current = (current as? Map<*, *>)?.get(segment) ?: return ""
    }
    return (current as? String)?.trim().orEmpty()
}

fun readOpenClawConfig(file: File): LocalOpenClawConfig {
    val root = JsonSlurper().parse(file) as? Map<*, *> ?: return LocalOpenClawConfig()
    val primaryRef = lookupNestedString(root, "agents", "defaults", "model", "primary")
    val providerId = primaryRef.substringBefore('/', missingDelimiterValue = "").trim()
    val primaryModel = primaryRef.substringAfter('/', missingDelimiterValue = primaryRef).trim()
    val providerMap = ((root["models"] as? Map<*, *>)?.get("providers") as? Map<*, *>)?.get(providerId) as? Map<*, *>
    return LocalOpenClawConfig(
        providerId = providerId,
        primaryModel = primaryModel,
        baseUrl = (providerMap?.get("baseUrl") as? String)?.trim().orEmpty(),
        apiKey = (providerMap?.get("apiKey") as? String)?.trim().orEmpty()
    )
}

fun Project.resolveOptionalFile(vararg candidates: String?): File? =
    candidates
        .asSequence()
        .filterNotNull()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(::file)
        .firstOrNull(File::isFile)

fun Project.resolveDebugAiBootstrapConfig(): DebugAiBootstrapConfig {
    fun propertyOrEnv(
        propertyName: String,
        vararg envNames: String
    ): String =
        firstNonBlank(
            providers.gradleProperty(propertyName).orNull,
            *envNames.map { providers.environmentVariable(it).orNull }.toTypedArray()
        )

    val userHome = System.getProperty("user.home")
    val envFile =
        resolveOptionalFile(
            providers.gradleProperty("fcitx5AndroidAiBootstrapEnvPath").orNull,
            providers.environmentVariable("FCITX5_ANDROID_AI_BOOTSTRAP_ENV_PATH").orNull,
            providers.environmentVariable("OPENCLAW_DEEPSEEK_ENV_PATH").orNull,
            userHome?.let { File(it, ".openclaw/.env").path }
        )
    val envValues = envFile?.let(::readDotEnvFile).orEmpty()

    val openClawConfigFile =
        resolveOptionalFile(
            providers.gradleProperty("fcitx5AndroidAiBootstrapConfigPath").orNull,
            providers.environmentVariable("FCITX5_ANDROID_AI_BOOTSTRAP_CONFIG_PATH").orNull,
            providers.environmentVariable("OPENCLAW_CONFIG_PATH").orNull,
            userHome?.let { File(it, ".openclaw/openclaw.json").path }
        )
    val openClawConfig =
        runCatching {
            openClawConfigFile?.let(::readOpenClawConfig) ?: LocalOpenClawConfig()
        }.getOrElse {
            logger.warn("Ignoring unreadable OpenClaw config at ${openClawConfigFile?.path}: ${it.message}")
            LocalOpenClawConfig()
        }

    val apiKey =
        firstNonBlank(
            propertyOrEnv("fcitx5AndroidAiChatApiKey", "FCITX5_ANDROID_AI_CHAT_API_KEY", "FCITX5_ANDROID_AI_API_KEY"),
            envValues["FCITX5_ANDROID_AI_CHAT_API_KEY"],
            envValues["FCITX5_ANDROID_AI_API_KEY"],
            envValues["OPENCLAW_DEEPSEEK_API_KEY"],
            openClawConfig.apiKey
        )
    val baseUrl =
        firstNonBlank(
            propertyOrEnv("fcitx5AndroidAiChatBaseUrl", "FCITX5_ANDROID_AI_CHAT_BASE_URL", "FCITX5_ANDROID_AI_BASE_URL"),
            envValues["FCITX5_ANDROID_AI_CHAT_BASE_URL"],
            envValues["FCITX5_ANDROID_AI_BASE_URL"],
            envValues["OPENCLAW_DEEPSEEK_BASE_URL"],
            openClawConfig.baseUrl,
            if (apiKey.isNotBlank()) "https://api.deepseek.com/v1" else null
        )
    val model =
        firstNonBlank(
            propertyOrEnv("fcitx5AndroidAiChatModel", "FCITX5_ANDROID_AI_CHAT_MODEL", "FCITX5_ANDROID_AI_MODEL"),
            envValues["FCITX5_ANDROID_AI_CHAT_MODEL"],
            envValues["FCITX5_ANDROID_AI_MODEL"],
            envValues["OPENCLAW_DEEPSEEK_MODEL"],
            openClawConfig.primaryModel,
            if (baseUrl.isNotBlank() || apiKey.isNotBlank()) "deepseek-chat" else null
        )
    val enabledOverride =
        firstNonBlank(
            propertyOrEnv("fcitx5AndroidAiBootstrapEnabled", "FCITX5_ANDROID_AI_BOOTSTRAP_ENABLED"),
            envValues["FCITX5_ANDROID_AI_BOOTSTRAP_ENABLED"]
        )
    val enabled =
        enabledOverride.toBooleanStrictOrNull()
            ?: (baseUrl.isNotBlank() && model.isNotBlank())

    return DebugAiBootstrapConfig(
        enabled = enabled && baseUrl.isNotBlank() && model.isNotBlank(),
        providerType =
            firstNonBlank(
                propertyOrEnv("fcitx5AndroidAiChatProviderType", "FCITX5_ANDROID_AI_CHAT_PROVIDER_TYPE"),
                envValues["FCITX5_ANDROID_AI_CHAT_PROVIDER_TYPE"],
                if (openClawConfig.providerId.isNotBlank()) "openai-compatible" else null,
                "openai-compatible"
            ),
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        timeoutSeconds =
            firstNonBlank(
                propertyOrEnv("fcitx5AndroidAiChatTimeoutSeconds", "FCITX5_ANDROID_AI_CHAT_TIMEOUT_SECONDS", "FCITX5_ANDROID_AI_TIMEOUT_SECONDS"),
                envValues["FCITX5_ANDROID_AI_CHAT_TIMEOUT_SECONDS"],
                envValues["FCITX5_ANDROID_AI_TIMEOUT_SECONDS"]
            ).toIntOrNull()?.coerceIn(1, 300) ?: 20
    )
}

val functionKitWorkspaceRoot =
    System.getenv("FUNCTION_KIT_WORKSPACE_ROOT")?.let(::file)
        ?: rootDir.resolve("../../../")
val functionKitRuntimeSdkDir = functionKitWorkspaceRoot.resolve("function-kit-runtime-sdk")
val functionKitCatalogDir = functionKitWorkspaceRoot.resolve("function-kits")
val functionKitExcludedIds = setOf("bridge-debugger")
val functionKitDirectories =
    functionKitCatalogDir.listFiles()
        ?.filter { it.isDirectory && it.resolve("manifest.json").isFile && it.name !in functionKitExcludedIds }
        .orEmpty()
        .sortedBy { it.name }
val functionKitAssetsDir = layout.buildDirectory.dir("generated/function-kit-assets")
val functionKitTestAssetsDir = layout.buildDirectory.dir("generated/function-kit-test-assets")
val debugAiBootstrapConfig = resolveDebugAiBootstrapConfig()
val effectiveDebugAiBootstrapConfig = debugAiBootstrapConfig.takeIf { it.enabled } ?: DebugAiBootstrapConfig()
val debugShortCommitHash = buildCommitHash.trim().takeIf { it.isNotBlank() }?.take(7).orEmpty()
val debugAppLabel = listOf("Fcitx5 Debug", buildVersionName, debugShortCommitHash).filter { it.isNotBlank() }.joinToString(" ")

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
    // Shared UI assets (no manifest.json, so it is not included in [functionKitDirectories]).
    val sharedDir = functionKitCatalogDir.resolve("_shared")
    if (sharedDir.isDirectory) {
        from(sharedDir) {
            into("function-kits/_shared")
            exclude("README.md")
            exclude("skills/**")
            exclude("tools/**")
            exclude("tests/**")
        }
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
            buildConfigField("boolean", "FUNCTION_KIT_DEBUG_AI_BOOTSTRAP_ENABLED", "false")
            buildConfigField("String", "FUNCTION_KIT_DEBUG_AI_PROVIDER_TYPE", buildConfigStringLiteral(""))
            buildConfigField("String", "FUNCTION_KIT_DEBUG_AI_BASE_URL", buildConfigStringLiteral(""))
            buildConfigField("String", "FUNCTION_KIT_DEBUG_AI_API_KEY", buildConfigStringLiteral(""))
            buildConfigField("String", "FUNCTION_KIT_DEBUG_AI_MODEL", buildConfigStringLiteral(""))
            buildConfigField("int", "FUNCTION_KIT_DEBUG_AI_TIMEOUT_SECONDS", "20")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", buildConfigStringLiteral(debugAppLabel))
            buildConfigField(
                "boolean",
                "FUNCTION_KIT_DEBUG_AI_BOOTSTRAP_ENABLED",
                effectiveDebugAiBootstrapConfig.enabled.toString()
            )
            buildConfigField(
                "String",
                "FUNCTION_KIT_DEBUG_AI_PROVIDER_TYPE",
                buildConfigStringLiteral(effectiveDebugAiBootstrapConfig.providerType)
            )
            buildConfigField(
                "String",
                "FUNCTION_KIT_DEBUG_AI_BASE_URL",
                buildConfigStringLiteral(effectiveDebugAiBootstrapConfig.baseUrl)
            )
            buildConfigField(
                "String",
                "FUNCTION_KIT_DEBUG_AI_API_KEY",
                buildConfigStringLiteral(effectiveDebugAiBootstrapConfig.apiKey)
            )
            buildConfigField(
                "String",
                "FUNCTION_KIT_DEBUG_AI_MODEL",
                buildConfigStringLiteral(effectiveDebugAiBootstrapConfig.model)
            )
            buildConfigField(
                "int",
                "FUNCTION_KIT_DEBUG_AI_TIMEOUT_SECONDS",
                effectiveDebugAiBootstrapConfig.timeoutSeconds.toString()
            )
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
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
