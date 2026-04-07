/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

internal object FunctionKitTgz {
    private const val TarBlockSize = 512

    data class ExtractStats(
        val extractedEntries: Int,
        val extractedBytes: Long
    )

    fun extractNpmPackageToDir(
        tgzFile: File,
        outDir: File,
        maxEntries: Int,
        maxBytes: Long
    ): ExtractStats {
        require(tgzFile.isFile) { "tgz file does not exist: ${tgzFile.path}" }
        require(outDir.isDirectory) { "outDir must be a directory: ${outDir.path}" }

        var extractedEntries = 0
        var extractedBytes = 0L

        tgzFile.inputStream().use { fileInput ->
            GZIPInputStream(fileInput).use { gzip ->
                val header = ByteArray(TarBlockSize)
                val skipBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var pendingLongName: String? = null

                while (true) {
                    if (!readFully(gzip, header)) {
                        break
                    }
                    if (isAllZero(header)) {
                        break
                    }

                    val typeFlag = header[156].toInt().toChar()
                    val size = parseOctalLong(header, offset = 124, length = 12)

                    val rawPath =
                        pendingLongName ?: run {
                            val name = parseTarString(header, offset = 0, length = 100)
                            val prefix = parseTarString(header, offset = 345, length = 155)
                            if (prefix.isNotBlank()) "$prefix/$name" else name
                        }
                    pendingLongName = null

                    if (typeFlag == 'L') {
                        val longName = readTarText(gzip, size, skipBuffer)
                        pendingLongName = longName.trim().trimEnd('\u0000')
                        skipTarPadding(gzip, size, skipBuffer)
                        continue
                    }

                    val normalized = rawPath.replace('\\', '/').trimStart('/')
                    if (!normalized.startsWith("package/")) {
                        skipTarEntryData(gzip, size, skipBuffer)
                        continue
                    }

                    val stripped = normalized.removePrefix("package/")
                    val relative = normalizeRelativePath(stripped) ?: run {
                        skipTarEntryData(gzip, size, skipBuffer)
                        continue
                    }

                    val target = File(outDir, relative)
                    if (!target.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) {
                        skipTarEntryData(gzip, size, skipBuffer)
                        continue
                    }

                    if (typeFlag == '5') {
                        target.mkdirs()
                        skipTarEntryData(gzip, size, skipBuffer)
                        continue
                    }

                    extractedEntries += 1
                    if (extractedEntries > maxEntries) {
                        throw IllegalStateException("Tar contains too many files (>$maxEntries)")
                    }

                    extractedBytes += size
                    if (extractedBytes > maxBytes) {
                        throw IllegalStateException("Tar is too large after extraction (>${maxBytes / 1024 / 1024}MB)")
                    }

                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        copyExactly(gzip, output, size, skipBuffer)
                    }
                    skipTarPadding(gzip, size, skipBuffer)
                }
            }
        }

        return ExtractStats(
            extractedEntries = extractedEntries,
            extractedBytes = extractedBytes
        )
    }

    fun extractUtf8TextFileOrNull(
        tgzFile: File,
        pathInTgz: String,
        maxBytes: Long
    ): String? {
        require(tgzFile.isFile) { "tgz file does not exist: ${tgzFile.path}" }
        val expected = pathInTgz.replace('\\', '/').trimStart('/')
        if (expected.isBlank()) {
            return null
        }

        tgzFile.inputStream().use { fileInput ->
            GZIPInputStream(fileInput).use { gzip ->
                val header = ByteArray(TarBlockSize)
                val skipBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var pendingLongName: String? = null

                while (true) {
                    if (!readFully(gzip, header)) {
                        break
                    }
                    if (isAllZero(header)) {
                        break
                    }

                    val typeFlag = header[156].toInt().toChar()
                    val size = parseOctalLong(header, offset = 124, length = 12)

                    val rawPath =
                        pendingLongName ?: run {
                            val name = parseTarString(header, offset = 0, length = 100)
                            val prefix = parseTarString(header, offset = 345, length = 155)
                            if (prefix.isNotBlank()) "$prefix/$name" else name
                        }
                    pendingLongName = null

                    if (typeFlag == 'L') {
                        val longName = readTarText(gzip, size, skipBuffer)
                        pendingLongName = longName.trim().trimEnd('\u0000')
                        skipTarPadding(gzip, size, skipBuffer)
                        continue
                    }

                    val normalized = rawPath.replace('\\', '/').trimStart('/')
                    if (normalized != expected) {
                        skipTarEntryData(gzip, size, skipBuffer)
                        continue
                    }

                    if (size > maxBytes) {
                        throw IllegalStateException("File is too large (${size} bytes).")
                    }

                    val bytes = readTarBytes(gzip, size, maxBytes, skipBuffer) ?: return null
                    skipTarPadding(gzip, size, skipBuffer)
                    return bytes.toString(StandardCharsets.UTF_8)
                }
            }
        }

        return null
    }

    private fun readTarBytes(
        input: InputStream,
        size: Long,
        maxBytes: Long,
        buffer: ByteArray
    ): ByteArray? {
        if (size <= 0) {
            return ByteArray(0)
        }
        if (size > maxBytes) {
            throw IllegalStateException("Payload is too large (${size} bytes).")
        }

        val out = ByteArrayOutputStream(size.toInt().coerceAtMost(1024 * 1024))
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) {
                return null
            }
            out.write(buffer, 0, read)
            remaining -= read.toLong()
        }
        return out.toByteArray()
    }

    private fun readTarText(
        input: InputStream,
        size: Long,
        buffer: ByteArray
    ): String =
        readTarBytes(input, size, maxBytes = size, buffer = buffer)
            ?.toString(StandardCharsets.UTF_8)
            ?: ""

    private fun skipTarEntryData(
        input: InputStream,
        size: Long,
        buffer: ByteArray
    ) {
        if (size > 0) {
            skipExactly(input, size, buffer)
        }
        skipTarPadding(input, size, buffer)
    }

    private fun skipTarPadding(
        input: InputStream,
        size: Long,
        buffer: ByteArray
    ) {
        val pad = ((TarBlockSize - (size % TarBlockSize)) % TarBlockSize).toInt()
        if (pad > 0) {
            skipExactly(input, pad.toLong(), buffer)
        }
    }

    private fun copyExactly(
        input: InputStream,
        output: java.io.OutputStream,
        size: Long,
        buffer: ByteArray
    ) {
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) {
                throw IllegalStateException("Unexpected EOF while reading tar entry.")
            }
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun skipExactly(
        input: InputStream,
        size: Long,
        buffer: ByteArray
    ) {
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) {
                throw IllegalStateException("Unexpected EOF while skipping tar entry.")
            }
            remaining -= read.toLong()
        }
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray
    ): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) {
                return false
            }
            offset += read
        }
        return true
    }

    private fun isAllZero(buffer: ByteArray): Boolean = buffer.all { it.toInt() == 0 }

    private fun parseTarString(
        header: ByteArray,
        offset: Int,
        length: Int
    ): String {
        val raw = header.copyOfRange(offset, offset + length)
        return raw
            .toString(StandardCharsets.UTF_8)
            .trimEnd('\u0000', ' ')
            .trim()
    }

    private fun parseOctalLong(
        header: ByteArray,
        offset: Int,
        length: Int
    ): Long {
        val raw = header.copyOfRange(offset, offset + length)
            .toString(StandardCharsets.US_ASCII)
            .trimEnd('\u0000', ' ')
            .trim()
        if (raw.isBlank()) {
            return 0L
        }
        return raw.toLong(8)
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
}

