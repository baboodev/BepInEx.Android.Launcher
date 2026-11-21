package com.bepinex.android

import java.io.File
import java.io.RandomAccessFile

/** Reads the Unity editor version embedded in common Unity data files. */
object UnityVersionLookup {
    private const val MAX_VERSION_LENGTH = 32
    // Matches "2022.3.62", "2022.3.62f2", "2022.3.62f1c1", "2022.2.0b1",
    // and custom forks like "2020.3.13f1XD1.1.1062b".
    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+(?:[abcfp]\\d+(?:c\\d+)?)?(?:[A-Za-z].*)?$|^\\d+\\.\\d+\\.\\d+rc\\d+$")

    private val candidates = linkedMapOf(
        "globalgamemanagers" to intArrayOf(0x14, 0x30),
        "data.unity3d" to intArrayOf(0x12),
        "mainData" to intArrayOf(0x14)
    )

    fun find(dataFolder: File): String? {
        for ((fileName, offsets) in candidates) {
            val file = File(dataFolder, fileName)
            if (!file.isFile) continue

            try {
                RandomAccessFile(file, "r").use { reader ->
                    for (offset in offsets) {
                        if (offset < 0 || offset >= reader.length()) continue
                        reader.seek(offset.toLong())
                        val value = readAscii(reader)
                        if (value != null && versionPattern.matches(value)) return value
                    }
                }
            } catch (e: Exception) {
                BepInExLog.d("Unity version lookup failed for ${file.absolutePath}: ${e.message}")
            }
        }
        // Hashed asset names (no globalgamemanagers): scan unity default resources.
        return scanFileForVersion(File(dataFolder, "unity default resources"))
    }

    private fun scanFileForVersion(file: File): String? {
        if (!file.isFile) return null
        return try {
            RandomAccessFile(file, "r").use { reader ->
                val limit = minOf(reader.length(), 2L * 1024 * 1024)
                val buf = ByteArray(4096)
                var carry = ""
                var pos = 0L
                while (pos < limit) {
                    reader.seek(pos)
                    val n = reader.read(buf)
                    if (n <= 0) break
                    val chunk = carry + String(buf, 0, n, Charsets.ISO_8859_1)
                    val match = Regex("(\\d+\\.\\d+\\.\\d+[abcfp]\\d+[A-Za-z0-9._-]*)").find(chunk)
                    if (match != null && versionPattern.matches(match.groupValues[1])) {
                        return match.groupValues[1]
                    }
                    carry = chunk.takeLast(64)
                    pos += n.toLong()
                }
                null
            }
        } catch (e: Exception) {
            BepInExLog.d("Unity version scan failed for ${file.absolutePath}: ${e.message}")
            null
        }
    }

    private fun readAscii(reader: RandomAccessFile): String? {
        val value = StringBuilder(MAX_VERSION_LENGTH)
        for (index in 0 until MAX_VERSION_LENGTH) {
            val byte = reader.read()
            if (byte < 0 || byte == 0) break
            if (byte < 0x20 || byte > 0x7e) break
            value.append(byte.toChar())
        }
        return value.toString().trim().ifEmpty { null }
    }

    fun normalize(version: String): String =
        Regex("^(\\d+\\.\\d+\\.\\d+)").find(version.trim())?.groupValues?.get(1)
            ?: version.trim()
}
