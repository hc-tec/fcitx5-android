/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.fcitx.fcitx5.android.input.wm.ImeWindowResumeManager

/**
 * Lightweight trampoline Activity for the IME process to show the system picker and
 * return the selected content URIs to the in-memory [FunctionKitFilePickerRegistry].
 */
class FunctionKitFilePickerActivity : Activity() {

    private var requestId: String = ""
    private var resumeKitId: String? = null
    private var resumePackageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId =
            savedInstanceState?.getString(ExtraRequestId)
                ?: intent?.getStringExtra(ExtraRequestId)
                ?: ""
        resumeKitId =
            savedInstanceState?.getString(ExtraResumeKitId)
                ?: intent?.getStringExtra(ExtraResumeKitId)
        resumePackageName =
            savedInstanceState?.getString(ExtraResumePackageName)
                ?: intent?.getStringExtra(ExtraResumePackageName)

        if (requestId.isBlank()) {
            finish()
            return
        }

        if (savedInstanceState == null) {
            launchPicker()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ExtraRequestId, requestId)
        outState.putString(ExtraResumeKitId, resumeKitId)
        outState.putString(ExtraResumePackageName, resumePackageName)
    }

    private fun launchPicker() {
        val allowMultiple = intent?.getBooleanExtra(ExtraAllowMultiple, false) ?: false
        val acceptMimeTypes =
            intent
                ?.getStringArrayExtra(ExtraAcceptMimeTypes)
                ?.mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
                ?.distinct()
                .orEmpty()

        val picker =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                type = "*/*"
                if (acceptMimeTypes.isNotEmpty()) {
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptMimeTypes.toTypedArray())
                }
            }

        runCatching {
            startActivityForResult(picker, RequestCodePickDocument)
        }.onFailure {
            FunctionKitFilePickerRegistry.cancel(requestId)
            finish()
        }
    }

    private fun persistUriPermissions(
        data: Intent,
        uris: List<Uri>
    ) {
        val flags =
            data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags == 0) {
            return
        }

        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RequestCodePickDocument) {
            return
        }

        val uris =
            if (resultCode != RESULT_OK || data == null) {
                emptyList()
            } else {
                buildList {
                    data.data?.let { add(it) }
                    val clipData = data.clipData
                    if (clipData != null) {
                        for (index in 0 until clipData.itemCount) {
                            val uri = clipData.getItemAt(index)?.uri ?: continue
                            add(uri)
                        }
                    }
                }.distinct()
            }

        val kitId = resumeKitId?.trim().orEmpty()
        if (kitId.isNotBlank()) {
            ImeWindowResumeManager.schedule(
                ImeWindowResumeManager.Request.FunctionKit(
                    kitId = kitId,
                    source = "files.pick",
                    expectedPackageName = resumePackageName?.trim()?.takeIf { it.isNotBlank() }
                )
            )
        }
        if (resultCode == RESULT_OK && data != null && uris.isNotEmpty()) {
            persistUriPermissions(data, uris)
        }
        FunctionKitFilePickerRegistry.deliver(requestId, uris)
        finish()
    }

    companion object {
        const val ExtraRequestId = "function_kit_file_picker_request_id"
        const val ExtraAllowMultiple = "function_kit_file_picker_allow_multiple"
        const val ExtraAcceptMimeTypes = "function_kit_file_picker_accept_mime_types"
        const val ExtraResumeKitId = "function_kit_file_picker_resume_kit_id"
        const val ExtraResumePackageName = "function_kit_file_picker_resume_package_name"

        private const val RequestCodePickDocument = 41001
    }
}
