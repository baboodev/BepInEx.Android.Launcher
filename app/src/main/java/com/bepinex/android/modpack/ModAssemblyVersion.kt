package com.bepinex.android.modpack

import java.io.File

/**
 * Display version for the mod Play will launch.
 *
 * .NET writes AssemblyInformationalVersion into the Win32 ProductVersion string
 * (for example 3.5.0+commit). Assembly Version and FileVersion are the fallbacks.
 * Callers use the DLL file name when none of those strings are present.
 */
object ModAssemblyVersion {
    data class Label(val version: String?, val fileName: String?)

    private val versionPattern = Regex(
        """^\d+\.\d+(?:\.\d+){0,2}(?:[-+][0-9A-Za-z.\-+]+)?$"""
    )

    fun read(manager: ModpackManager, packageName: String, modpackName: String): Label {
        val mods = manager.listModEntries(packageName, modpackName)
        val enabled = mods.filter { it.enabled }
        val pool = if (enabled.isNotEmpty()) enabled else mods
        val chosen = pool.firstOrNull { it.file.name.contains("bugtopia", ignoreCase = true) }
            ?: pool.firstOrNull()
            ?: return Label(null, null)
        return Label(readDisplayVersion(chosen.file), chosen.file.name)
    }

    fun readDisplayVersion(file: File): String? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L || length > MAX_BYTES) return null
        val bytes = file.readBytes()
        return utf16ResourceValue(bytes, "ProductVersion")
            ?: utf16ResourceValue(bytes, "Assembly Version")
            ?: utf16ResourceValue(bytes, "FileVersion")
    }

    private fun utf16ResourceValue(data: ByteArray, key: String): String? {
        val needle = (key + "\u0000").toByteArray(Charsets.UTF_16LE)
        var from = 0
        while (from < data.size) {
            val at = indexOf(data, needle, from)
            if (at < 0) return null
            val value = readUtf16Z(data, alignedValueStart(data, at + needle.size))
            if (value != null && versionPattern.matches(value)) return value
            from = at + 2
        }
        return null
    }

    /** VERSIONINFO values are DWORD-aligned, so a key can be followed by up to three zero pad bytes. */
    private fun alignedValueStart(data: ByteArray, start: Int): Int {
        var index = start
        val limit = minOf(data.size, start + 3)
        while (index < limit && index % 4 != 0 && data[index] == 0.toByte()) {
            index++
        }
        return index
    }

    private fun readUtf16Z(data: ByteArray, start: Int): String? {
        if (start < 0 || start + 1 >= data.size) return null
        val chars = StringBuilder()
        var index = start
        while (index + 1 < data.size && chars.length < 80) {
            val code = (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)
            if (code == 0) break
            if (code < 32 || code > 126) return null
            chars.append(code.toChar())
            index += 2
        }
        val text = chars.toString()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int): Int {
        if (needle.isEmpty() || start > data.size - needle.size) return -1
        var index = start
        val last = data.size - needle.size
        while (index <= last) {
            var matched = true
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
            index++
        }
        return -1
    }

    private const val MAX_BYTES = 80L * 1024L * 1024L
}
