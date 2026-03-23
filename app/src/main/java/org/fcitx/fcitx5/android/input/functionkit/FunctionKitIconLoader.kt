/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object FunctionKitIconLoader {
    fun loadDrawable(
        context: Context,
        assetPath: String?
    ): Drawable? {
        if (assetPath.isNullOrBlank()) {
            return null
        }

        val bitmap =
            try {
                context.assets.open(assetPath).use { input ->
                    val bytes = input.readBytes()
                    decodeBitmap(bytes, assetPath)
                }
            } catch (_: Exception) {
                null
            }

        return bitmap?.let { BitmapDrawable(context.resources, it) }
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        assetPath: String
    ): Bitmap? {
        val extension = assetPath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (extension == "ico") {
            decodeIco(bytes)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun decodeIco(bytes: ByteArray): Bitmap? {
        if (bytes.size < 6) {
            return null
        }

        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (header.short.toInt() != 0 || header.short.toInt() != 1) {
            return null
        }
        val count = header.short.toInt() and 0xffff
        if (count <= 0 || bytes.size < 6 + count * 16) {
            return null
        }

        data class Entry(
            val width: Int,
            val height: Int,
            val bytesInRes: Int,
            val imageOffset: Int
        )

        val entries =
            buildList {
                repeat(count) {
                    val rawWidth = header.get().toInt() and 0xff
                    val rawHeight = header.get().toInt() and 0xff
                    header.get() // color count
                    header.get() // reserved
                    header.short // planes
                    header.short // bit count
                    val bytesInRes = header.int
                    val imageOffset = header.int
                    val width = if (rawWidth == 0) 256 else rawWidth
                    val height = if (rawHeight == 0) 256 else rawHeight
                    if (bytesInRes > 0 && imageOffset >= 0 && imageOffset + bytesInRes <= bytes.size) {
                        add(Entry(width, height, bytesInRes, imageOffset))
                    }
                }
            }

        entries.sortedWith(compareByDescending<Entry> { it.width * it.height }).forEach { entry ->
            val imageBytes = bytes.copyOfRange(entry.imageOffset, entry.imageOffset + entry.bytesInRes)
            decodePngFrame(imageBytes)?.let { return it }
            decodeBmpFrame(imageBytes)?.let { return it }
        }

        return null
    }

    private fun decodePngFrame(imageBytes: ByteArray): Bitmap? {
        if (imageBytes.size < 8) {
            return null
        }

        val pngSignature =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a
            )
        if (!imageBytes.copyOfRange(0, 8).contentEquals(pngSignature)) {
            return null
        }

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun decodeBmpFrame(imageBytes: ByteArray): Bitmap? {
        if (imageBytes.size < 40) {
            return null
        }

        val dib = imageBytes.copyOf()
        val dibHeader = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = dibHeader.int
        if (headerSize < 40 || dib.size < headerSize) {
            return null
        }

        val width = dibHeader.int
        val rawHeight = dibHeader.int
        val planes = dibHeader.short.toInt() and 0xffff
        val bitCount = dibHeader.short.toInt() and 0xffff
        if (width <= 0 || rawHeight == 0 || planes <= 0 || bitCount <= 0) {
            return null
        }

        val actualHeight = if (rawHeight > 0) rawHeight / 2 else rawHeight
        if (actualHeight == 0) {
            return null
        }

        val colorsUsed = dibHeader.intAt(32)
        val colorTableEntries =
            when {
                colorsUsed > 0 -> colorsUsed
                bitCount <= 8 -> 1 shl bitCount
                else -> 0
            }
        val colorTableSize = colorTableEntries * 4
        if (14 + dib.size <= 14 + headerSize + colorTableSize) {
            return null
        }

        dibHeader.putInt(8, actualHeight)
        val output = ByteArrayOutputStream(14 + dib.size)
        output.write(byteArrayOf('B'.code.toByte(), 'M'.code.toByte()))
        output.writeIntLE(14 + dib.size)
        output.writeIntLE(0)
        output.writeIntLE(14 + headerSize + colorTableSize)
        output.write(dib)

        val bmp = output.toByteArray()
        return BitmapFactory.decodeByteArray(bmp, 0, bmp.size)
    }

    private fun ByteBuffer.intAt(offset: Int): Int {
        val currentPosition = position()
        position(offset)
        val value = int
        position(currentPosition)
        return value
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xff)
        write(value shr 8 and 0xff)
        write(value shr 16 and 0xff)
        write(value shr 24 and 0xff)
    }
}
