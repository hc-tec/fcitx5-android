/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.functionkit

import android.os.Bundle
import android.text.InputType
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitPackageManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class FunctionKitDownloadCenterFragment : PaddingPreferenceFragment() {

    private data class CatalogPackage(
        val kitId: String,
        val name: String?,
        val version: String?,
        val sizeBytes: Long?,
        val sha256: String?,
        val zipUrl: String?
    )

    private lateinit var importLauncher: ActivityResultLauncher<String>

    private var catalogUrl: String? = null
    private var catalogPackages: List<CatalogPackage> = emptyList()
    private var catalogErrorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(ctx, title = R.string.function_kit_download_center_installing) {
                    val outcome =
                        withContext(NonCancellable + Dispatchers.IO) {
                            FunctionKitPackageManager.installFromZipUri(ctx, uri)
                        }
                    withContext(Dispatchers.Main) {
                        when (outcome) {
                            is FunctionKitPackageManager.InstallOutcome.Ok -> {
                                ctx.toast(
                                    getString(
                                        if (outcome.replaced) {
                                            R.string.function_kit_download_center_installed_replaced
                                        } else {
                                            R.string.function_kit_download_center_installed
                                        },
                                        outcome.kitId
                                    )
                                )
                                showInstallOpenPrompt(outcome.kitId, outcome.replaced)
                                refresh()
                            }
                            is FunctionKitPackageManager.InstallOutcome.Error -> {
                                ctx.toast(outcome.message)
                            }
                        }
                    }
                }
            }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        catalogUrl = loadCatalogUrl()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).also { screen ->
                renderUi(screen)
            }
        if (!catalogUrl.isNullOrBlank()) {
            lifecycleScope.launch { refreshCatalog(showLoading = false) }
        }
    }

    private fun refresh() {
        preferenceScreen?.let { renderUi(it) }
    }

    private fun showInstallOpenPrompt(
        kitId: String,
        replaced: Boolean
    ) {
        val root = view ?: return
        val message =
            getString(
                if (replaced) {
                    R.string.function_kit_download_center_installed_replaced
                } else {
                    R.string.function_kit_download_center_installed
                },
                kitId
            )

        Snackbar.make(root, message, Snackbar.LENGTH_LONG)
            .setDuration(5_000)
            .setAction(R.string.function_kit_binding_snackbar_open) {
                navigateWithAnim(SettingsRoute.FunctionKitDetail(kitId = kitId))
            }
            .show()
    }

    private fun renderUi(screen: PreferenceScreen) {
        val context = screen.context
        screen.removeAll()

        val catalogCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_download_center_catalog_category)
                order = -210
            }
        screen.addPreference(catalogCategory)

        catalogCategory.addPreference(
            Preference(context).apply {
                setup(
                    title = getString(R.string.function_kit_download_center_catalog_url_title),
                    summary =
                        catalogUrl?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.function_kit_download_center_catalog_url_summary)
                )
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showCatalogUrlDialog()
                    true
                }
            }
        )

        if (!catalogUrl.isNullOrBlank()) {
            catalogCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = getString(R.string.function_kit_download_center_catalog_refresh),
                        summary = catalogErrorMessage?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.function_kit_download_center_catalog_refresh_summary)
                    )
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        lifecycleScope.launch { refreshCatalog(showLoading = true) }
                        true
                    }
                }
            )
        }

        catalogPackages.forEach { pkg ->
            val title = pkg.name?.takeIf { it.isNotBlank() } ?: pkg.kitId
            val summary =
                buildString {
                    append("id=")
                    append(pkg.kitId)
                    pkg.version?.takeIf { it.isNotBlank() }?.let { version ->
                        append(" · v=")
                        append(version)
                    }
                    pkg.sizeBytes?.takeIf { it > 0 }?.let { bytes ->
                        append(" · ")
                        append(formatBytes(bytes))
                    }
                    pkg.sha256?.takeIf { it.isNotBlank() }?.let { sha ->
                        append("\nsha256=")
                        append(sha.take(16))
                        append("…")
                    }
                }

            catalogCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = title,
                        summary = summary
                    )
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        lifecycleScope.launch { installCatalogPackage(pkg) }
                        true
                    }
                }
            )
        }

        val installCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_download_center_install_category)
                order = -200
            }
        screen.addPreference(installCategory)

        installCategory.addPreference(
            Preference(context).apply {
                setup(
                    title = getString(R.string.function_kit_download_center_install_zip),
                    summary = getString(R.string.function_kit_download_center_install_zip_summary)
                )
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    importLauncher.launch("application/zip")
                    true
                }
            }
        )

        installCategory.addPreference(
            Preference(context).apply {
                setup(
                    title = getString(R.string.function_kit_download_center_install_url),
                    summary = getString(R.string.function_kit_download_center_install_url_summary)
                )
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showInstallFromUrlDialog()
                    true
                }
            }
        )

        val installedCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.function_kit_download_center_installed_category)
                order = -190
            }
        screen.addPreference(installedCategory)

        val installed = FunctionKitPackageManager.listUserInstalledManifests(context)
        if (installed.isEmpty()) {
            installedCategory.addPreference(
                Preference(context).apply {
                    setup(
                        title = getString(R.string.function_kit_download_center_installed_empty_title),
                        summary = getString(R.string.function_kit_download_center_installed_empty_summary)
                    )
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            )
        } else {
            installed.forEach { kit ->
                installedCategory.addPreference(
                    Preference(context).apply {
                        setup(
                            title = kit.name,
                            summary = "id=${kit.id}"
                        )
                        isIconSpaceReserved = false
                        setOnPreferenceClickListener {
                            navigateWithAnim(SettingsRoute.FunctionKitDetail(kitId = kit.id))
                            true
                        }
                    }
                )
            }
        }
    }

    private fun showInstallFromUrlDialog() {
        val context = requireContext()
        val input =
            TextInputEditText(context).apply {
                hint = getString(R.string.function_kit_download_center_install_url_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }

        AlertDialog.Builder(context)
            .setTitle(R.string.function_kit_download_center_install_url)
            .setView(input)
            .setPositiveButton(R.string.function_kit_download_center_install_button) { _, _ ->
                val rawUrl = input.text?.toString()?.trim().orEmpty()
                if (rawUrl.isBlank()) {
                    context.toast(R.string.function_kit_download_center_install_url_missing)
                    return@setPositiveButton
                }
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    context.toast(R.string.function_kit_download_center_install_url_invalid)
                    return@setPositiveButton
                }

                lifecycleScope.withLoadingDialog(context, title = R.string.function_kit_download_center_downloading) {
                    val tempZip =
                        withContext(NonCancellable + Dispatchers.IO) {
                            downloadToTempZip(context.applicationContext, rawUrl)
                        }
                    if (tempZip == null) {
                        withContext(Dispatchers.Main) {
                            context.toast(R.string.function_kit_download_center_download_failed)
                        }
                        return@withLoadingDialog
                    }
                    val outcome =
                        withContext(NonCancellable + Dispatchers.IO) {
                            val installKey = "url:${sha256HexString(rawUrl)}"
                            FunctionKitPackageManager.installFromZipFile(context, tempZip, installKey = installKey)
                        }
                    runCatching { tempZip.delete() }
                    withContext(Dispatchers.Main) {
                        when (outcome) {
                            is FunctionKitPackageManager.InstallOutcome.Ok -> {
                                context.toast(
                                    getString(
                                        if (outcome.replaced) {
                                            R.string.function_kit_download_center_installed_replaced
                                        } else {
                                            R.string.function_kit_download_center_installed
                                        },
                                        outcome.kitId
                                    )
                                )
                                showInstallOpenPrompt(outcome.kitId, outcome.replaced)
                                refresh()
                            }
                            is FunctionKitPackageManager.InstallOutcome.Error -> {
                                context.toast(outcome.message)
                            }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCatalogUrlDialog() {
        val context = requireContext()
        val input =
            TextInputEditText(context).apply {
                hint = getString(R.string.function_kit_download_center_catalog_url_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(catalogUrl.orEmpty())
            }

        AlertDialog.Builder(context)
            .setTitle(R.string.function_kit_download_center_catalog_url_title)
            .setView(input)
            .setPositiveButton(R.string.function_kit_download_center_catalog_url_save) { _, _ ->
                val rawUrl = input.text?.toString()?.trim().orEmpty()
                if (rawUrl.isNotBlank() && !rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    context.toast(R.string.function_kit_download_center_install_url_invalid)
                    return@setPositiveButton
                }
                catalogUrl = rawUrl
                catalogPackages = emptyList()
                catalogErrorMessage = null
                saveCatalogUrl(rawUrl)
                refresh()
                if (rawUrl.isNotBlank()) {
                    lifecycleScope.launch { refreshCatalog(showLoading = true) }
                }
            }
            .setNeutralButton(R.string.function_kit_download_center_catalog_url_clear) { _, _ ->
                catalogUrl = ""
                catalogPackages = emptyList()
                catalogErrorMessage = null
                saveCatalogUrl("")
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun refreshCatalog(showLoading: Boolean) {
        val context = requireContext()
        val url = catalogUrl?.trim().orEmpty()
        if (url.isBlank()) {
            catalogPackages = emptyList()
            catalogErrorMessage = null
            refresh()
            return
        }

        val action = suspend {
            val result =
                withContext(NonCancellable + Dispatchers.IO) {
                    fetchCatalogPackages(url)
                }
            withContext(Dispatchers.Main) {
                when (result) {
                    is CatalogFetchResult.Ok -> {
                        catalogPackages = result.packages
                        catalogErrorMessage = null
                    }
                    is CatalogFetchResult.Error -> {
                        catalogPackages = emptyList()
                        catalogErrorMessage = result.message
                    }
                }
                refresh()
            }
        }

        if (showLoading) {
            lifecycleScope.withLoadingDialog(context, title = R.string.function_kit_download_center_catalog_refresh) {
                action()
            }
        } else {
            action()
        }
    }

    private sealed class CatalogFetchResult {
        data class Ok(val packages: List<CatalogPackage>) : CatalogFetchResult()
        data class Error(val message: String) : CatalogFetchResult()
    }

    private fun fetchCatalogPackages(catalogUrl: String): CatalogFetchResult {
        return runCatching {
            val url = URL(catalogUrl)
            val baseForRelative = URL(if (catalogUrl.endsWith("/")) catalogUrl else "$catalogUrl/")
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connect()
            val status = connection.responseCode
            if (status < 200 || status >= 300) {
                return@runCatching CatalogFetchResult.Error("HTTP $status")
            }
            val raw =
                connection.inputStream.use { input ->
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            val root = JSONObject(raw)
            val packagesNode: JSONArray? = root.optJSONArray("packages")
            if (packagesNode == null) {
                return@runCatching CatalogFetchResult.Error("Missing packages[]")
            }

            val packages = mutableListOf<CatalogPackage>()
            for (index in 0 until packagesNode.length()) {
                val item = packagesNode.optJSONObject(index) ?: continue
                val kitId = item.optString("kitId").trim()
                if (kitId.isBlank()) {
                    continue
                }
                val name = item.optString("name").trim().ifBlank { null }
                val version = item.optString("version").trim().ifBlank { null }
                val sizeBytes = item.optLong("sizeBytes").takeIf { it > 0 }
                val sha256 = item.optString("sha256").trim().ifBlank { null }
                val zipUrlRaw = item.optString("zipUrl").trim().ifBlank { null }
                val zipUrl =
                    zipUrlRaw?.let { resolveUrl(baseForRelative, it) }
                        ?: "${catalogUrl.trimEnd('/')}/${kitId}.zip"

                packages +=
                    CatalogPackage(
                        kitId = kitId,
                        name = name,
                        version = version,
                        sizeBytes = sizeBytes,
                        sha256 = sha256,
                        zipUrl = zipUrl
                    )
            }

            CatalogFetchResult.Ok(packages.sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.kitId.lowercase() })))
        }.getOrElse { error ->
            CatalogFetchResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    private fun resolveUrl(base: URL, maybeRelative: String): String =
        runCatching { URL(base, maybeRelative).toString() }.getOrDefault(maybeRelative)

    private suspend fun installCatalogPackage(pkg: CatalogPackage) {
        val context = requireContext()
        val zipUrl = pkg.zipUrl?.trim().orEmpty()
        if (zipUrl.isBlank()) {
            context.toast(getString(R.string.function_kit_download_center_catalog_missing_zip_url, pkg.kitId))
            return
        }

        lifecycleScope.withLoadingDialog(context, title = R.string.function_kit_download_center_installing) {
            val tempZip =
                withContext(NonCancellable + Dispatchers.IO) {
                    downloadToTempZip(
                        context = context.applicationContext,
                        urlString = zipUrl
                    )
                }
            if (tempZip == null) {
                withContext(Dispatchers.Main) {
                    context.toast(R.string.function_kit_download_center_download_failed)
                }
                return@withLoadingDialog
            }

            val expectedSha = pkg.sha256?.trim().orEmpty()
            if (expectedSha.isNotBlank()) {
                val actual =
                    withContext(NonCancellable + Dispatchers.IO) {
                        sha256Hex(tempZip)
                    }
                if (!equalsSha256(expectedSha, actual)) {
                    runCatching { tempZip.delete() }
                    withContext(Dispatchers.Main) {
                        context.toast(getString(R.string.function_kit_download_center_catalog_sha256_mismatch, pkg.kitId))
                    }
                    return@withLoadingDialog
                }
            }

            val outcome =
                withContext(NonCancellable + Dispatchers.IO) {
                    val sourceId = catalogUrl?.trim().orEmpty().ifBlank { zipUrl }
                    val installKey = "catalog:${sha256HexString(sourceId)}:${pkg.kitId}"
                    FunctionKitPackageManager.installFromZipFile(context, tempZip, installKey = installKey)
                }
            runCatching { tempZip.delete() }

            withContext(Dispatchers.Main) {
                when (outcome) {
                    is FunctionKitPackageManager.InstallOutcome.Ok -> {
                        context.toast(
                            getString(
                                if (outcome.replaced) {
                                    R.string.function_kit_download_center_installed_replaced
                                } else {
                                    R.string.function_kit_download_center_installed
                                },
                                outcome.kitId
                            )
                        )
                        showInstallOpenPrompt(outcome.kitId, outcome.replaced)
                        refresh()
                    }
                    is FunctionKitPackageManager.InstallOutcome.Error -> {
                        context.toast(outcome.message)
                    }
                }
            }
        }
    }

    private fun downloadToTempZip(
        context: android.content.Context,
        urlString: String,
        maxBytes: Long = MAX_ZIP_BYTES
    ): File? {
        val tempDir = File(context.cacheDir, "function-kits-download").apply { mkdirs() }
        val target = File(tempDir, "kit-download-${UUID.randomUUID()}.zip")

        val result = runCatching {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/zip,application/octet-stream,*/*")

            connection.connect()
            val status = connection.responseCode
            if (status < 200 || status >= 300) {
                return@runCatching null
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0 }
            if (contentLength != null && contentLength > maxBytes) {
                return@runCatching null
            }

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        total += read
                        if (total > maxBytes) {
                            return@runCatching null
                        }
                    }
                }
            }
            target
        }.getOrNull()
        if (result == null) {
            runCatching { target.delete() }
        }
        return result
    }

    private fun loadCatalogUrl(): String {
        val context = requireContext().applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREF_KEY_CATALOG_URL, "").orEmpty()
    }

    private fun saveCatalogUrl(value: String) {
        val context = requireContext().applicationContext
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY_CATALOG_URL, value)
            .apply()
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(byte.toInt().and(0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    private fun sha256HexString(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.trim().toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(byte.toInt().and(0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    private fun equalsSha256(expected: String, actual: String): Boolean {
        val trimmedExpected = expected.trim()
        val trimmedActual = actual.trim()
        if (trimmedExpected.isBlank() || trimmedActual.isBlank()) {
            return false
        }
        return trimmedExpected.equals(trimmedActual, ignoreCase = true)
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val value = bytes.toDouble()
        return when {
            value >= gb -> String.format("%.1fGB", value / gb)
            value >= mb -> String.format("%.1fMB", value / mb)
            value >= kb -> String.format("%.1fKB", value / kb)
            else -> "${bytes}B"
        }
    }

    private companion object {
        private const val MAX_ZIP_BYTES = 96L * 1024L * 1024L
        private const val PREF_KEY_CATALOG_URL = "function_kit_download_center.catalog_url"
    }
}
