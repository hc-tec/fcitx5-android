/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

internal object FunctionKitPackageManager {
    private const val ManifestFileName = "manifest.json"
    private const val KitDirectoryName = "function-kits"
    private const val PackagesDirectoryName = "_packages"
    private const val TempDirectoryName = "function-kits-tmp"
    private const val PackageMetaFileName = "_install.json"

    private const val MaxZipEntries = 4096
    private const val MaxZipBytes = 64L * 1024L * 1024L

    private val KitIdRegex = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")

    private data class PackageMeta(
        val installKey: String,
        val kitId: String,
        val version: String?,
        val installedAtEpochMs: Long?
    )

    sealed class InstallOutcome {
        data class Ok(val kitId: String, val replaced: Boolean) : InstallOutcome()
        data class Error(val message: String, val cause: Throwable? = null) : InstallOutcome()
    }

    fun isUserInstalled(
        context: Context,
        kitId: String
    ): Boolean {
        val legacy = resolveKitDirectory(context, kitId).resolve(ManifestFileName).isFile
        if (legacy) {
            return true
        }
        val activeKey = FunctionKitKitSettings.activeInstallKey(kitId)
        if (!activeKey.isNullOrBlank()) {
            val pkgManifest = resolvePackageDirectory(context, activeKey).resolve(ManifestFileName)
            if (pkgManifest.isFile) {
                return true
            }
        }
        return findAnyInstallKeyForKitId(context, kitId) != null
    }

    fun listUserInstalledKitIds(context: Context): List<String> {
        val root = kitsRootDir(context)
        val legacy =
            root.listFiles()
            ?.filter { it.isDirectory && it.resolve(ManifestFileName).isFile }
            ?.map { it.name }
            .orEmpty()

        val fromPackages =
            listPackages(context)
                .map(PackageMeta::kitId)

        return (legacy + fromPackages)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    fun listUserInstalledManifests(context: Context): List<FunctionKitManifest> =
        listUserInstalledKitIds(context).mapNotNull { kitId ->
            loadUserInstalledManifest(context, kitId)
        }

    fun loadUserInstalledManifest(
        context: Context,
        kitId: String
    ): FunctionKitManifest? {
        if (kitId.isBlank()) {
            return null
        }

        val activeKey = FunctionKitKitSettings.activeInstallKey(kitId)
        if (!activeKey.isNullOrBlank()) {
            loadManifestFromPackageInstallKey(context, kitId, activeKey)?.let { return it }
            FunctionKitKitSettings.setActiveInstallKey(kitId, null)
        }

        loadManifestFromLegacyDirectory(context, kitId)?.let { return it }

        val fallbackKey = findAnyInstallKeyForKitId(context, kitId)
        if (!fallbackKey.isNullOrBlank()) {
            FunctionKitKitSettings.setActiveInstallKey(kitId, fallbackKey)
            loadManifestFromPackageInstallKey(context, kitId, fallbackKey)?.let { return it }
        }

        return null
    }

    fun installFromZipUri(
        context: Context,
        uri: Uri,
        installKey: String? = null
    ): InstallOutcome {
        val ctx = resolveStorageContext(context)
        val tempDir = File(ctx.cacheDir, TempDirectoryName).apply { mkdirs() }
        val tempFile = File(tempDir, "kit-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return InstallOutcome.Error("Failed to open zip content: $uri")
            return installFromZipFile(context, tempFile, installKey = installKey)
        } catch (error: Throwable) {
            return InstallOutcome.Error("Failed to install kit from zip: $uri", error)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    fun installFromZipFile(
        context: Context,
        zipFile: File,
        installKey: String? = null
    ): InstallOutcome {
        if (!zipFile.isFile) {
            return InstallOutcome.Error("Zip file does not exist: ${zipFile.path}")
        }
        val ctx = resolveStorageContext(context)
        val kitsRoot = kitsRootDir(context).apply { mkdirs() }
        val packagesRoot = packagesRootDir(context).apply { mkdirs() }
        val stagingRoot = File(ctx.cacheDir, TempDirectoryName).apply { mkdirs() }
        val stagingDir = File(stagingRoot, "kit-staging-${UUID.randomUUID()}")
        val manifestEntryInfo = findManifestEntry(zipFile) ?: return InstallOutcome.Error("manifest.json not found in zip")
        val manifestRead = readZipEntryText(zipFile, manifestEntryInfo.entryName)
        val manifestRaw =
            manifestRead.text
                ?: run {
                    Timber.w(
                        manifestRead.error,
                        "Failed to read manifest.json from zip (entry=%s, rootPrefix=%s): %s",
                        manifestEntryInfo.entryName,
                        manifestEntryInfo.rootPrefix,
                        zipFile.path
                    )
                    return InstallOutcome.Error("Failed to read manifest.json from zip", manifestRead.error)
                }
        val kitId = parseKitId(manifestRaw) ?: return InstallOutcome.Error("Missing kit id in manifest.json")
        if (!KitIdRegex.matches(kitId)) {
            return InstallOutcome.Error("Invalid kit id: $kitId")
        }

        val normalizedInstallKey = installKey?.trim()?.takeIf { it.isNotBlank() }
        val targetDir =
            if (normalizedInstallKey == null) {
                resolveKitDirectory(context, kitId)
            } else {
                resolvePackageDirectory(context, normalizedInstallKey)
            }
        val replaced = targetDir.isDirectory
        runCatching { deleteRecursively(stagingDir) }
        stagingDir.mkdirs()

        try {
            var extractedEntries = 0
            var extractedBytes = 0L
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) {
                        continue
                    }
                    extractedEntries += 1
                    if (extractedEntries > MaxZipEntries) {
                        return InstallOutcome.Error("Zip contains too many files (>$MaxZipEntries)")
                    }

                    val rawName = entry.name.replace('\\', '/')
                    val stripped = stripPrefix(rawName, manifestEntryInfo.rootPrefix) ?: continue
                    val normalized = normalizeRelativePath(stripped) ?: continue

                    val target = File(stagingDir, normalized)
                    if (!target.canonicalPath.startsWith(stagingDir.canonicalPath + File.separator)) {
                        return InstallOutcome.Error("Blocked zip entry path traversal: $rawName")
                    }
                    target.parentFile?.mkdirs()

                    zip.getInputStream(entry).use { input ->
                        BufferedInputStream(input).use { buffered ->
                            FileOutputStream(target).use { output ->
                                val copied = buffered.copyTo(output)
                                extractedBytes += copied
                                if (extractedBytes > MaxZipBytes) {
                                    return InstallOutcome.Error("Zip is too large after extraction (>${MaxZipBytes / 1024 / 1024}MB)")
                                }
                            }
                        }
                    }
                }
            }

            val stagedManifest = File(stagingDir, ManifestFileName)
            if (!stagedManifest.isFile) {
                return InstallOutcome.Error("Zip did not contain $ManifestFileName at expected location")
            }

            val stagedManifestRaw =
                stagedManifest.inputStream()
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            val stagedKitId = parseKitId(stagedManifestRaw) ?: return InstallOutcome.Error("Staged manifest missing kit id")
            if (stagedKitId != kitId) {
                return InstallOutcome.Error("Kit id mismatch: $kitId (zip) vs $stagedKitId (staged)")
            }

            val backupRoot = if (normalizedInstallKey == null) kitsRoot else packagesRoot
            val backupDir = File(backupRoot, ".backup-${targetDir.name}-${UUID.randomUUID()}")
            if (targetDir.exists()) {
                if (!targetDir.renameTo(backupDir)) {
                    deleteRecursively(targetDir)
                }
            }
            if (!stagingDir.renameTo(targetDir)) {
                return InstallOutcome.Error("Failed to move kit into place: ${targetDir.path}")
            }
            deleteRecursively(backupDir)

            if (normalizedInstallKey != null) {
                writePackageMeta(
                    dir = targetDir,
                    installKey = normalizedInstallKey,
                    kitId = kitId,
                    version = JSONObject(stagedManifestRaw).optString("version").trim().ifBlank { null }
                )
                FunctionKitKitSettings.setActiveInstallKey(kitId, normalizedInstallKey)
            }
            FunctionKitKitSettings.bumpRegistryRevision()
            return InstallOutcome.Ok(kitId = kitId, replaced = replaced)
        } catch (error: Throwable) {
            return InstallOutcome.Error("Failed to install kit: $kitId", error)
        } finally {
            runCatching { deleteRecursively(stagingDir) }
        }
    }

    fun uninstall(
        context: Context,
        kitId: String
    ): Boolean {
        return runCatching {
            deleteRecursively(resolveKitDirectory(context, kitId))

            listPackages(context)
                .filter { meta -> meta.kitId == kitId }
                .forEach { meta ->
                    deleteRecursively(resolvePackageDirectory(context, meta.installKey))
                }

            FunctionKitKitSettings.setActiveInstallKey(kitId, null)
            FunctionKitKitSettings.bumpRegistryRevision()
            true
        }.getOrDefault(false)
    }

    fun resolveKitDirectory(
        context: Context,
        kitId: String
    ): File =
        File(kitsRootDir(context), kitId)

    fun kitsRootDir(context: Context): File {
        val ctx = resolveStorageContext(context)
        return File(ctx.filesDir, KitDirectoryName)
    }

    fun storageContext(context: Context): Context = resolveStorageContext(context)

    private fun packagesRootDir(context: Context): File = File(kitsRootDir(context), PackagesDirectoryName)

    private fun resolvePackageDirectory(
        context: Context,
        installKey: String
    ): File = File(packagesRootDir(context), sha256Hex(installKey.trim()))

    internal fun resolveUserInstalledFile(
        context: Context,
        kitId: String,
        relativePath: String
    ): File? {
        val normalizedKitId = kitId.trim()
        if (normalizedKitId.isBlank()) {
            return null
        }

        val normalizedRelative = relativePath.replace('\\', '/').trimStart('/').trim()
        if (normalizedRelative.isBlank()) {
            return null
        }
        val segments = normalizedRelative.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }

        val kitsRoot = kitsRootDir(context)

        fun resolveIn(root: File): File? {
            val target = File(root, segments.joinToString("/"))
            return runCatching {
                val targetCanonical = target.canonicalPath
                val rootCanonical = root.canonicalPath + File.separator
                if (!targetCanonical.startsWith(rootCanonical)) {
                    return@runCatching null
                }
                target.takeIf { it.isFile }
            }.getOrNull()
        }

        resolveIn(File(kitsRoot, normalizedKitId))?.let { return it }

        val activeKey = FunctionKitKitSettings.activeInstallKey(normalizedKitId)
        if (!activeKey.isNullOrBlank()) {
            resolveIn(resolvePackageDirectory(context, activeKey))?.let { return it }
        }

        val fallbackKey = findAnyInstallKeyForKitId(context, normalizedKitId)
        if (!fallbackKey.isNullOrBlank()) {
            FunctionKitKitSettings.setActiveInstallKey(normalizedKitId, fallbackKey)
            resolveIn(resolvePackageDirectory(context, fallbackKey))?.let { return it }
        }

        return null
    }

    private fun resolveStorageContext(context: Context): Context {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app.createDeviceProtectedStorageContext()
        } else {
            app
        }
    }

    private data class ManifestEntryInfo(
        val entryName: String,
        val rootPrefix: String
    )

    private fun findManifestEntry(zipFile: File): ManifestEntryInfo? =
        runCatching {
            ZipFile(zipFile).use { zip ->
                val candidates = mutableListOf<Pair<String, String>>()
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val rawName = entry.name
                    val normalizedName = rawName.replace('\\', '/').trimStart('/')
                    if (normalizedName.endsWith("/$ManifestFileName")) {
                        candidates += rawName to normalizedName
                    } else if (normalizedName == ManifestFileName) {
                        candidates += rawName to normalizedName
                    }
                }
                val (manifestRaw, manifestNormalized) =
                    candidates.minByOrNull { (_, normalized) -> normalized.count { ch -> ch == '/' } }
                        ?: return@use null
                val rootPrefix =
                    if (manifestNormalized == ManifestFileName) {
                        ""
                    } else {
                        manifestNormalized.substringBeforeLast('/', missingDelimiterValue = "") + "/"
                    }
                ManifestEntryInfo(entryName = manifestRaw, rootPrefix = rootPrefix)
            }
        }.getOrNull()

    private data class ReadZipEntryTextResult(
        val text: String?,
        val error: Throwable? = null
    )

    private fun readZipEntryText(
        zipFile: File,
        entryName: String
    ): ReadZipEntryTextResult =
        runCatching {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@use null
                zip.getInputStream(entry)
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            }
        }.fold(
            onSuccess = { ReadZipEntryTextResult(text = it) },
            onFailure = { error -> ReadZipEntryTextResult(text = null, error = error) }
        )

    private fun parseKitId(manifestRaw: String): String? =
        runCatching {
            JSONObject(manifestRaw).optString("id").trim().takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun virtualManifestPath(kitId: String): String = "function-kits/$kitId/$ManifestFileName"

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(byte.toInt().and(0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    private fun writePackageMeta(
        dir: File,
        installKey: String,
        kitId: String,
        version: String?
    ) {
        val meta =
            JSONObject()
                .put("installKey", installKey.trim())
                .put("kitId", kitId.trim())
                .put("version", version?.trim())
                .put("installedAtEpochMs", System.currentTimeMillis())

        val file = File(dir, PackageMetaFileName)
        file.outputStream().use { output ->
            output.write(meta.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun listPackages(context: Context): List<PackageMeta> {
        val root = packagesRootDir(context)
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val metaFile = File(dir, PackageMetaFileName)
                if (!metaFile.isFile) {
                    return@mapNotNull null
                }
                runCatching {
                    val raw =
                        metaFile.inputStream()
                            .bufferedReader(StandardCharsets.UTF_8)
                            .use { it.readText() }
                    val json = JSONObject(raw)
                    val installKey = json.optString("installKey").trim()
                    val kitId = json.optString("kitId").trim()
                    if (installKey.isBlank() || kitId.isBlank()) {
                        return@runCatching null
                    }
                    PackageMeta(
                        installKey = installKey,
                        kitId = kitId,
                        version = json.optString("version").trim().ifBlank { null },
                        installedAtEpochMs = json.optLong("installedAtEpochMs").takeIf { it > 0 }
                    )
                }.getOrNull()
            }
            ?.toList()
            .orEmpty()
    }

    private fun findAnyInstallKeyForKitId(
        context: Context,
        kitId: String
    ): String? =
        listPackages(context)
            .filter { meta -> meta.kitId == kitId }
            .maxWithOrNull(compareBy<PackageMeta>({ it.installedAtEpochMs ?: 0L }, { it.installKey }))
            ?.installKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun loadManifestFromLegacyDirectory(
        context: Context,
        kitId: String
    ): FunctionKitManifest? {
        val manifestFile = resolveKitDirectory(context, kitId).resolve(ManifestFileName)
        if (!manifestFile.isFile) {
            return null
        }
        return loadManifestFromFile(context, kitId, manifestFile)
    }

    private fun loadManifestFromPackageInstallKey(
        context: Context,
        kitId: String,
        installKey: String
    ): FunctionKitManifest? {
        val manifestFile = resolvePackageDirectory(context, installKey).resolve(ManifestFileName)
        if (!manifestFile.isFile) {
            return null
        }
        return loadManifestFromFile(context, kitId, manifestFile)
    }

    private fun loadManifestFromFile(
        context: Context,
        kitId: String,
        manifestFile: File
    ): FunctionKitManifest? =
        runCatching {
            val raw =
                manifestFile.inputStream()
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            val root = JSONObject(raw)
            val parsedKitId = root.optString("id").trim()
            if (parsedKitId.isBlank() || parsedKitId != kitId) {
                return@runCatching null
            }
            FunctionKitManifest.parse(
                root = root,
                assetPath = virtualManifestPath(kitId),
                fallbackId = kitId,
                fallbackEntryHtmlAssetPath = "function-kits/$kitId/ui/app/index.html",
                fallbackRuntimePermissions = emptySet(),
                isUserInstalled = true
            )
        }.getOrNull()

    private fun stripPrefix(path: String, prefix: String): String? {
        val normalized = path.trimStart('/')
        if (prefix.isBlank()) {
            return normalized
        }
        return if (normalized.startsWith(prefix)) {
            normalized.removePrefix(prefix)
        } else {
            null
        }
    }

    private fun normalizeRelativePath(path: String): String? {
        val normalized = path.replace('\\', '/').trimStart('/').trim()
        if (normalized.isBlank()) {
            return null
        }
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }
        return segments.joinToString("/")
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) {
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        file.delete()
    }
}
