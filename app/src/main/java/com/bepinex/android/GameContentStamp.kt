package com.bepinex.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.bepinex.android.tools.GlobalMetadata
import java.io.File
import java.util.zip.ZipFile

/**
 * Fingerprint of the installed game APK (and optional metadata override) used to decide when
 * extracted natives, copied Unity assets and Il2CppInterop assemblies are stale.
 *
 * BepInEx's managed [Il2CppInteropManager] already regenerates interop when `libil2cpp.so`'s
 * content hash changes — but the launcher kept one-shot extract/copy markers forever, so an
 * updated APK left a stale `libil2cpp.so` on disk and the managed hash never moved. Invalidating
 * those caches when this stamp changes fixes that.
 */
object GameContentStamp {

    const val STAMP_FILE_NAME = ".game_content_stamp"

    /**
     * Builds a stable stamp for [packageName]. Format is pipe-separated fields so a log line
     * can show why a refresh fired without decoding opaque hashes.
     */
    fun compute(context: Context, packageName: String): String {
        val pm = context.packageManager
        val appInfo: ApplicationInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return "missing|$packageName"
        }

        val versionCode = try {
            val pkg = pm.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
            else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }

        val lastUpdate = try {
            pm.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }

        val sourceDir = appInfo.sourceDir ?: ""
        val sourceLen = sourceDir.takeIf { it.isNotEmpty() }?.let { File(it).length() } ?: 0L
        val sourceMtime = sourceDir.takeIf { it.isNotEmpty() }?.let { File(it).lastModified() } ?: 0L

        val splits = appInfo.splitSourceDirs?.joinToString(",") { path ->
            val f = File(path)
            "${f.name}:${f.length()}:${f.lastModified()}"
        } ?: ""

        val metadataInApk = metadataEntryFingerprint(appInfo)
        val override = GlobalMetadata.getOverrideFile(packageName)
        val overrideFp = if (override.isFile) {
            "${override.length()}:${override.lastModified()}"
        } else {
            "none"
        }

        return listOf(
            versionCode.toString(),
            lastUpdate.toString(),
            sourceLen.toString(),
            sourceMtime.toString(),
            splits,
            metadataInApk,
            overrideFp
        ).joinToString("|")
    }

    fun readStored(gameRoot: File): String? {
        val file = File(gameRoot, STAMP_FILE_NAME)
        if (!file.isFile) return null
        return try {
            file.readText().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    fun writeStored(gameRoot: File, stamp: String) {
        gameRoot.mkdirs()
        File(gameRoot, STAMP_FILE_NAME).writeText(stamp)
    }

    /**
     * Drops Il2CppInterop outputs so the next BepInEx boot regenerates them.
     * Leaves the directory itself (and any non-dll leftovers) alone.
     */
    fun invalidateInterop(bepInExDir: File) {
        val interop = File(bepInExDir, "interop")
        if (!interop.isDirectory) return

        var removed = 0
        interop.listFiles()?.forEach { file ->
            val name = file.name
            if (name.equals("assembly-hash.txt", ignoreCase = true) ||
                name.endsWith(".dll", ignoreCase = true)
            ) {
                if (file.delete()) removed++
            }
        }
        BepInExLog.i("Interop invalidated ($removed file(s)) — will regenerate on next BepInEx boot")
    }

    private fun metadataEntryFingerprint(appInfo: ApplicationInfo): String {
        val apks = ArrayList<String>()
        appInfo.sourceDir?.let { apks.add(it) }
        appInfo.splitSourceDirs?.let { apks.addAll(it) }

        val candidates = arrayOf(
            "assets/bin/Data/Managed/Metadata/global-metadata.dat",
            "assets/Data/Managed/Metadata/global-metadata.dat"
        )

        for (apk in apks) {
            try {
                ZipFile(apk).use { zip ->
                    for (path in candidates) {
                        val entry = zip.getEntry(path) ?: continue
                        return "${entry.size}:${entry.crc}"
                    }
                }
            } catch (_: Exception) {
                // try next apk
            }
        }
        return "none"
    }
}
