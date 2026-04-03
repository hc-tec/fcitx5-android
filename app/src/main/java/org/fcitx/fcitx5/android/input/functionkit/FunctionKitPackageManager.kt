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
import java.util.UUID
import java.util.zip.ZipFile

internal object FunctionKitPackageManager {
    private const val ManifestFileName = "manifest.json"
    private const val KitDirectoryName = "function-kits"
    private const val TempDirectoryName = "function-kits-tmp"

    private const val MaxZipEntries = 4096
    private const val MaxZipBytes = 64L * 1024L * 1024L

    private val KitIdRegex = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")

    sealed class InstallOutcome {
        data class Ok(val kitId: String, val replaced: Boolean) : InstallOutcome()
        data class Error(val message: String, val cause: Throwable? = null) : InstallOutcome()
    }

    fun isUserInstalled(
        context: Context,
        kitId: String
    ): Boolean =
        resolveKitDirectory(context, kitId).resolve(ManifestFileName).isFile

    fun listUserInstalledKitIds(context: Context): List<String> {
        val root = kitsRootDir(context)
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.listFiles()
            ?.filter { it.isDirectory && it.resolve(ManifestFileName).isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
    }

    fun listUserInstalledManifests(context: Context): List<FunctionKitManifest> =
        listUserInstalledKitIds(context).mapNotNull { kitId ->
            loadUserInstalledManifest(context, kitId)
        }

    fun loadUserInstalledManifest(
        context: Context,
        kitId: String
    ): FunctionKitManifest? {
        val manifestFile = resolveKitDirectory(context, kitId).resolve(ManifestFileName)
        if (!manifestFile.isFile) {
            return null
        }
        return runCatching {
            val raw =
                manifestFile.inputStream()
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            val root = JSONObject(raw)
            FunctionKitManifest.parse(
                root = root,
                assetPath = virtualManifestPath(kitId),
                fallbackId = kitId,
                fallbackEntryHtmlAssetPath = "function-kits/$kitId/ui/app/index.html",
                fallbackRuntimePermissions = emptySet(),
                isUserInstalled = true
            )
        }.getOrNull()
    }

    fun installFromZipUri(
        context: Context,
        uri: Uri
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
            return installFromZipFile(context, tempFile)
        } catch (error: Throwable) {
            return InstallOutcome.Error("Failed to install kit from zip: $uri", error)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    fun installFromZipFile(
        context: Context,
        zipFile: File
    ): InstallOutcome {
        if (!zipFile.isFile) {
            return InstallOutcome.Error("Zip file does not exist: ${zipFile.path}")
        }
        val ctx = resolveStorageContext(context)
        val kitsRoot = kitsRootDir(context).apply { mkdirs() }
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

        val replaced = resolveKitDirectory(context, kitId).isDirectory
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

            val targetDir = resolveKitDirectory(context, kitId)
            val backupDir = File(kitsRoot, ".backup-$kitId-${UUID.randomUUID()}")
            if (targetDir.exists()) {
                if (!targetDir.renameTo(backupDir)) {
                    deleteRecursively(targetDir)
                }
            }
            if (!stagingDir.renameTo(targetDir)) {
                return InstallOutcome.Error("Failed to move kit into place: ${targetDir.path}")
            }
            deleteRecursively(backupDir)
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
        val dir = resolveKitDirectory(context, kitId)
        if (!dir.exists()) {
            return true
        }
        return runCatching {
            deleteRecursively(dir)
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
