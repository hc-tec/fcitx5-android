/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.InputStream
import java.util.UUID

/**
 * Host-managed store for picked files (referenced by [fileId]).
 *
 * Design goals:
 * - UI never receives raw bytes through the bridge.
 * - Host keeps the authoritative URI handle and streams bytes to network.fetch when needed.
 * - Store is memory-only and best-effort (cleared on IME restart / TTL eviction).
 */
internal class FunctionKitFileStore(
    private val context: Context,
    private val kitId: String
) {

    data class Entry(
        val kitId: String,
        val fileId: String,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val createdAtEpochMs: Long
    )

    private val entries = mutableMapOf<String, Entry>()

    private val maxEntries = 8
    private val ttlMs = 15 * 60 * 1000L

    @Synchronized
    fun clear() {
        entries.values.forEach { entry -> releasePersistablePermission(entry.uri) }
        entries.clear()
    }

    @Synchronized
    fun remove(fileId: String): Boolean {
        val entry = entries.remove(fileId) ?: return false
        releasePersistablePermission(entry.uri)
        return true
    }

    @Synchronized
    fun get(fileId: String): Entry? {
        evictExpiredLocked()
        return entries[fileId]
    }

    fun openInputStream(fileId: String): InputStream? {
        val uri = synchronized(this) { get(fileId)?.uri }
        if (uri == null) {
            return null
        }
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (error: Exception) {
            Log.w(
                "FunctionKitFileStore",
                "Failed to openInputStream fileId=$fileId uri=$uri: ${error.message ?: "unknown error"}",
                error
            )
            null
        }
    }

    @Synchronized
    fun put(uri: Uri): Entry {
        evictExpiredLocked()
        val now = System.currentTimeMillis()
        val fileId = "file-${UUID.randomUUID()}"

        val (name, sizeBytes) = queryOpenableColumns(uri)
        val mimeType =
            runCatching { context.contentResolver.getType(uri) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"

        val entry =
            Entry(
                kitId = kitId,
                fileId = fileId,
                uri = uri,
                name =
                    name
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                        ?: "file",
                mimeType = mimeType,
                sizeBytes = sizeBytes?.takeIf { it >= 0 },
                createdAtEpochMs = now
            )

        entries[fileId] = entry
        evictOverflowLocked()
        return entry
    }

    @Synchronized
    private fun evictExpiredLocked() {
        if (entries.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        val expired = entries.values.filter { now - it.createdAtEpochMs > ttlMs }
        expired.forEach { entry ->
            entries.remove(entry.fileId)
            releasePersistablePermission(entry.uri)
        }
    }

    @Synchronized
    private fun evictOverflowLocked() {
        if (entries.size <= maxEntries) {
            return
        }
        val candidates = entries.values.sortedBy { it.createdAtEpochMs }
        val evictCount = (entries.size - maxEntries).coerceAtLeast(0)
        candidates.take(evictCount).forEach { entry ->
            entries.remove(entry.fileId)
            releasePersistablePermission(entry.uri)
        }
    }

    private fun releasePersistablePermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun queryOpenableColumns(uri: Uri): Pair<String?, Long?> {
        val resolver = context.contentResolver
        val cursor =
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )
            }.getOrNull() ?: return null to null

        cursor.use { safe ->
            return readFirstRow(safe)
        }
    }

    private fun readFirstRow(cursor: Cursor): Pair<String?, Long?> {
        if (!cursor.moveToFirst()) {
            return null to null
        }
        val name =
            runCatching {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }.getOrNull()

        val size =
            runCatching {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) cursor.getLong(index) else null
            }.getOrNull()

        return name to size
    }
}
