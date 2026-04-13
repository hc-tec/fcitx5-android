// Modified by hc-tec on 2026-04-09: switch the packaged Android applicationId to keyflow.
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

fun Project.resolveFunctionKitWorkspaceRoot(): File? {
    val env = System.getenv("FUNCTION_KIT_WORKSPACE_ROOT")?.trim()
    if (!env.isNullOrBlank()) {
        return file(env)
    }

    val candidates =
        listOf(
            // Allow vendored workspace inside this repo, if the maintainer prefers submodules/subtrees.
            rootDir,
            // Recommended workspace layout:
            // <workspace>/fcitx5-android
            // <workspace>/function-kits
            // <workspace>/function-kit-runtime-sdk
            rootDir.resolve(".."),
            // Backward compatible with the original monorepo layout we use in research workspace:
            // <workspace>/TODO/ime-research/repos/fcitx5-android  -> workspace is <workspace>/TODO
            rootDir.resolve("../../../")
        )
            .map { it.absoluteFile }
            .distinct()

    val requiredChildren = listOf("function-kits", "function-kit-runtime-sdk")
    return candidates.firstOrNull { dir -> requiredChildren.all { dir.resolve(it).isDirectory } }
        ?: candidates.firstOrNull { it.isDirectory }
}

val functionKitWorkspaceRoot = resolveFunctionKitWorkspaceRoot()
val functionKitRuntimeSdkDir = functionKitWorkspaceRoot?.resolve("function-kit-runtime-sdk")
val functionKitCatalogDir = functionKitWorkspaceRoot?.resolve("function-kits")
// Keep devtools and temporary kits out of release builds. Public/public-ish kits can still be installed later
// via Download Center when we don't want them prebundled in the APK.
val functionKitAlwaysExcludedIds = setOf("bridge-debugger")
val functionKitReleaseBundledIds = setOf("kit-store")
val functionKitDirectories =
    functionKitCatalogDir
        ?.listFiles()
        ?.filter { it.isDirectory && it.resolve("manifest.json").isFile && it.name !in functionKitAlwaysExcludedIds }
        .orEmpty()
        .sortedBy { it.name }
val functionKitDebugDirectories = functionKitDirectories
val functionKitReleaseDirectories =
    functionKitDirectories.filter { it.name in functionKitReleaseBundledIds }
val functionKitDebugAssetsDir = layout.buildDirectory.dir("generated/function-kit-debug-assets")
val functionKitReleaseAssetsDir = layout.buildDirectory.dir("generated/function-kit-release-assets")
val functionKitTestAssetsDir = layout.buildDirectory.dir("generated/function-kit-test-assets")
val functionKitDebugFallbackAssetsDir = layout.buildDirectory.dir("generated/function-kit-debug-fallback-assets")
val functionKitReleaseFallbackAssetsDir = layout.buildDirectory.dir("generated/function-kit-release-fallback-assets")
val debugAiBootstrapConfig = resolveDebugAiBootstrapConfig()
val effectiveDebugAiBootstrapConfig = debugAiBootstrapConfig.takeIf { it.enabled } ?: DebugAiBootstrapConfig()
val debugShortCommitHash = buildCommitHash.trim().takeIf { it.isNotBlank() }?.take(7).orEmpty()
val debugAppLabel = listOf("Fcitx5 Debug", buildVersionName, debugShortCommitHash).filter { it.isNotBlank() }.joinToString(" ")

fun registerFunctionKitAssetsTask(
    taskName: String,
    outputDir: Provider<out org.gradle.api.file.Directory>,
    fallbackDir: Provider<out org.gradle.api.file.Directory>,
    bundledDirectories: List<File>,
    variantLabel: String
) = tasks.register(taskName, Sync::class.java) {
    group = "function-kit"
    description = "Sync browser Function Kit runtime assets into the Android app bundle ($variantLabel)."
    into(outputDir)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    doFirst {
        val fallbackRoot = fallbackDir.get().asFile
        fallbackRoot.deleteRecursively()
        fallbackRoot.mkdirs()
        val placeholderKitId =
            bundledDirectories.firstOrNull()?.name
                ?: if (variantLabel == "release") "kit-store" else "chat-auto-reply"
        val placeholderKitName =
            placeholderKitId
                .split('-')
                .joinToString(" ") { part -> part.replaceFirstChar(Char::titlecase) }
        val runtimeDistDir = functionKitRuntimeSdkDir?.resolve("dist")
        val hasRuntimeDist = runtimeDistDir?.isDirectory == true
        val hasCatalog = functionKitCatalogDir?.isDirectory == true
        val hasAnyKits = bundledDirectories.isNotEmpty()
        val needsPlaceholder = !hasRuntimeDist || !hasCatalog || !hasAnyKits
        if (needsPlaceholder) {
            val placeholderManifest =
                fallbackRoot.resolve("function-kits/$placeholderKitId/manifest.json")
            placeholderManifest.parentFile.mkdirs()
            placeholderManifest.writeText(
                // Keep this placeholder kit extremely small: it is only used when the Function Kit
                // workspace is not available (e.g. CI, first-time contributors, standalone clones).
                // Real kits will override this asset path when present.
                """
                {
                  "id": "$placeholderKitId",
                  "name": "$placeholderKitName (Placeholder)",
                  "version": "0.0.0",
                  "description": "Placeholder kit bundled by the host build. Clone function-kits workspace or set FUNCTION_KIT_WORKSPACE_ROOT to enable real kits.",
                  "entry": { "bundle": { "html": "ui/app/index.html" } },
                  "runtimePermissions": []
                }
                """.trimIndent() + "\n"
            )

            val placeholderEntryHtml =
                fallbackRoot.resolve("function-kits/$placeholderKitId/ui/app/index.html")
            placeholderEntryHtml.parentFile.mkdirs()
            placeholderEntryHtml.writeText(
                """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <title>Function Kit (Placeholder)</title>
                    <style>
                      :root { color-scheme: light dark; }
                      body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 0; padding: 16px; }
                      .card { border: 1px solid rgba(127,127,127,.35); border-radius: 12px; padding: 12px; }
                      h1 { font-size: 16px; margin: 0 0 8px; }
                      p { font-size: 13px; line-height: 1.45; margin: 0 0 8px; opacity: .9; }
                      code { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
                      ul { margin: 8px 0 0; padding-left: 18px; font-size: 13px; }
                    </style>
                  </head>
                  <body>
                    <div class="card">
                      <h1>Function Kit assets are not bundled</h1>
                      <p>This is a placeholder kit packaged by the Android host build.</p>
                      <p>To develop or use real Function Kits, provide the workspace and rebuild:</p>
                      <ul>
                        <li>Clone <code>function-kits</code> and <code>function-kit-runtime-sdk</code> next to this repo, or</li>
                        <li>Set <code>FUNCTION_KIT_WORKSPACE_ROOT</code> to the workspace root that contains both.</li>
                      </ul>
                    </div>
                  </body>
                </html>
                """.trimIndent() + "\n"
            )
            logger.warn(
                buildString {
                    appendLine("Function Kit workspace not detected for $variantLabel; bundling placeholder kit only.")
                    appendLine("To include real kits/runtime in the APK, provide a workspace containing:")
                    appendLine("  - function-kits/")
                    appendLine("  - function-kit-runtime-sdk/")
                    appendLine("Fix one of:")
                    appendLine("  - set env FUNCTION_KIT_WORKSPACE_ROOT=<workspaceRoot>")
                    appendLine("  - clone the repos next to this repo (../function-kits, ../function-kit-runtime-sdk)")
                    appendLine("Detected: rootDir=$rootDir, FUNCTION_KIT_WORKSPACE_ROOT=${System.getenv("FUNCTION_KIT_WORKSPACE_ROOT") ?: "(unset)"}")
                }
            )
        }
        if (variantLabel == "release") {
            val excludedKitIds =
                functionKitDirectories.map { it.name }.filterNot { it in bundledDirectories.map(File::getName).toSet() }
            if (excludedKitIds.isNotEmpty()) {
                logger.lifecycle("Function Kit release bundle keeps only curated kits: ${bundledDirectories.joinToString { it.name }}; excluded: ${excludedKitIds.joinToString()}")
            }
        }
    }
    from(fallbackDir)

    val runtimeDistDir = functionKitRuntimeSdkDir?.resolve("dist")
    if (runtimeDistDir?.isDirectory == true) {
        from(runtimeDistDir) {
            into("function-kit-runtime-sdk/dist")
        }
    }
    val sharedDir = functionKitCatalogDir?.resolve("shared")
    if (sharedDir?.isDirectory == true) {
        from(sharedDir) {
            into("function-kits/shared")
            exclude("README.md")
            exclude("skills/**")
            exclude("tools/**")
            exclude("tests/**")
        }
    }
    bundledDirectories.forEach { kitDir ->
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

val syncFunctionKitDebugAssets =
    registerFunctionKitAssetsTask(
        taskName = "syncFunctionKitDebugAssets",
        outputDir = functionKitDebugAssetsDir,
        fallbackDir = functionKitDebugFallbackAssetsDir,
        bundledDirectories = functionKitDebugDirectories,
        variantLabel = "debug"
    )

val syncFunctionKitReleaseAssets =
    registerFunctionKitAssetsTask(
        taskName = "syncFunctionKitReleaseAssets",
        outputDir = functionKitReleaseAssetsDir,
        fallbackDir = functionKitReleaseFallbackAssetsDir,
        bundledDirectories = functionKitReleaseDirectories,
        variantLabel = "release"
    )

val syncFunctionKitTestAssets by tasks.registering(Sync::class) {
    group = "function-kit"
    description = "Sync browser Function Kit test fixtures into the Android test assets."
    into(functionKitTestAssetsDir)
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
        applicationId = "io.github.hctec.keyflow"
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
        noCompress += "bin"
    }

    sourceSets.named("debug") {
        assets.srcDir(functionKitDebugAssetsDir.get().asFile)
    }
    sourceSets.named("release") {
        assets.srcDir(functionKitReleaseAssetsDir.get().asFile)
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
    implementation(project(":lib:voice-core"))
    implementation(project(":lib:whisper"))
    implementation(project(":lib:common"))
    implementation(files("libs/vad-release.aar"))
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
    dependsOn(syncFunctionKitDebugAssets)
    dependsOn(syncFunctionKitReleaseAssets)
    dependsOn(syncFunctionKitTestAssets)
}
