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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitPackageManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class FunctionKitDownloadCenterFragment : PaddingPreferenceFragment() {

    private lateinit var importLauncher: ActivityResultLauncher<String>

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
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).also { screen ->
                renderUi(screen)
            }
    }

    private fun refresh() {
        preferenceScreen?.let { renderUi(it) }
    }

    private fun renderUi(screen: PreferenceScreen) {
        val context = screen.context
        screen.removeAll()

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
                            FunctionKitPackageManager.installFromZipFile(context, tempZip)
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

    private fun downloadToTempZip(
        context: android.content.Context,
        urlString: String
    ): File? {
        val tempDir = File(context.cacheDir, "function-kits-download").apply { mkdirs() }
        val target = File(tempDir, "kit-download-${UUID.randomUUID()}.zip")

        return runCatching {
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

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target
        }.getOrNull()
    }
}
