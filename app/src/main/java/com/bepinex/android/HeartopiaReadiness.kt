package com.bepinex.android

import android.content.Context
import android.os.Environment
import com.bepinex.android.auth.XdGoogleWebAuth
import com.bepinex.android.modpack.ModpackManager
import java.io.File

/**
 * Play-tab checklist for the pinned Heartopia install: framework, Unity base
 * libraries, Il2CppInterop output, the Bugtopia plugin, and the optional
 * Il2CppInterop overlay.
 */
object HeartopiaReadiness {
    val packageName: String = XdGoogleWebAuth.HEARTOPIA_PACKAGE

    /** Same file [BootstrapActivity] copies over core/Il2CppInterop.Runtime.dll. */
    val overlayFile: File
        get() = File(
            Environment.getExternalStorageDirectory(),
            "Bugtopia/patches/Il2CppInterop.Runtime.dll"
        )

    enum class Tone { READY, MISSING, INFO }

    enum class Action { EXTRACT, DOWNLOAD_UNITY, REGEN_INTEROP }

    data class Item(
        val title: String,
        val detail: String,
        val tone: Tone,
        val action: Action?
    )

    data class Report(val items: List<Item>)

    data class OpResult(val ok: Boolean, val message: String)

    fun inspect(context: Context, activeModpack: String?): Report {
        return Report(
            listOf(
                frameworkItem(context),
                unityItem(context),
                interopItem(context),
                modItem(context, activeModpack),
                overlayItem(context)
            )
        )
    }

    fun downloadUnity(context: Context, onProgress: (String) -> Unit): OpResult {
        val data = File(BepInExPaths.getGameRootDir(packageName), "Assets/bin/Data")
        val version = UnityVersionLookup.find(data)
        if (version.isNullOrEmpty()) {
            BepInExLog.w("Unity libs: version not found under ${data.absolutePath}")
            return OpResult(false, context.getString(R.string.ready_unity_no_version))
        }
        BepInExLog.i("Unity libs: ensuring $version")
        val ready = UnityLibsDownloader.ensureLibraries(
            BepInExPaths.getUnityLibsDir(packageName),
            version,
            onProgress
        )
        return if (ready) {
            OpResult(true, context.getString(R.string.ready_unity_done))
        } else {
            OpResult(false, context.getString(R.string.ready_unity_failed))
        }
    }

    /** Deletes interop DLLs and assembly-hash.txt so the next launch regenerates them. */
    fun regenInterop(context: Context): OpResult {
        val interop = BepInExPaths.getInteropDir(packageName)
        if (!interop.isDirectory) {
            BepInExLog.i("Regen interop: no directory at ${interop.absolutePath}")
            return OpResult(true, context.getString(R.string.ready_interop_regenerated))
        }
        val doomed = interop.listFiles()?.filter { file ->
            file.isFile && (
                file.name.equals("assembly-hash.txt", ignoreCase = true) ||
                    file.name.endsWith(".dll", ignoreCase = true)
                )
        }.orEmpty()
        doomed.forEach { file ->
            BepInExLog.i("Regen interop deleting ${file.name} (${file.length()} bytes)")
        }
        GameContentStamp.invalidateInterop(BepInExPaths.getBepInExDir(packageName))
        val hashLeft = File(interop, "assembly-hash.txt").isFile
        val dllsLeft = interop.listFiles()?.count {
            it.isFile && it.name.endsWith(".dll", ignoreCase = true)
        } ?: 0
        BepInExLog.i(
            "Regen interop finished: removed=${doomed.size} hashRemains=$hashLeft dllsRemain=$dllsLeft"
        )
        return OpResult(
            ok = !hashLeft && dllsLeft == 0,
            message = context.getString(R.string.ready_interop_regenerated)
        )
    }

    private fun frameworkItem(context: Context): Item {
        val ready = FileExtractor(context).isFrameworkReady(packageName)
        return Item(
            title = context.getString(R.string.ready_framework),
            detail = context.getString(
                if (ready) R.string.ready_framework_ok else R.string.ready_framework_missing
            ),
            tone = if (ready) Tone.READY else Tone.MISSING,
            action = if (ready) null else Action.EXTRACT
        )
    }

    private fun unityItem(context: Context): Item {
        val zips = BepInExPaths.getUnityLibsDir(packageName).listFiles()?.filter {
            it.isFile && it.extension.equals("zip", ignoreCase = true) && it.length() > 1024
        }.orEmpty()
        return if (zips.isNotEmpty()) {
            Item(
                title = context.getString(R.string.ready_unity),
                detail = context.getString(R.string.ready_unity_ok, zips.joinToString { it.name }),
                tone = Tone.READY,
                action = Action.DOWNLOAD_UNITY
            )
        } else {
            Item(
                title = context.getString(R.string.ready_unity),
                detail = context.getString(R.string.ready_unity_missing),
                tone = Tone.MISSING,
                action = Action.DOWNLOAD_UNITY
            )
        }
    }

    private fun interopItem(context: Context): Item {
        val interop = BepInExPaths.getInteropDir(packageName)
        val hash = File(interop, "assembly-hash.txt").isFile
        val dlls = interop.listFiles()?.count {
            it.isFile && it.name.endsWith(".dll", ignoreCase = true)
        } ?: 0
        val ready = hash && dlls > 0
        return Item(
            title = context.getString(R.string.ready_interop),
            detail = context.getString(
                if (ready) R.string.ready_interop_ok else R.string.ready_interop_missing
            ) + if (ready) " ($dlls)" else "",
            tone = if (ready) Tone.READY else Tone.MISSING,
            action = Action.REGEN_INTEROP
        )
    }

    private fun modItem(context: Context, activeModpack: String?): Item {
        val title = context.getString(R.string.ready_mod)
        if (activeModpack.isNullOrBlank()) {
            return Item(
                title = title,
                detail = context.getString(R.string.ready_mod_no_pack),
                tone = Tone.MISSING,
                action = null
            )
        }
        val mods = ModpackManager().listModEntries(packageName, activeModpack)
        val hits = mods.filter { it.file.name.contains("bugtopia", ignoreCase = true) }
        val enabled = hits.firstOrNull { it.enabled }
        return when {
            enabled != null -> Item(
                title = title,
                detail = context.getString(R.string.ready_mod_ok, enabled.file.name),
                tone = Tone.READY,
                action = null
            )
            hits.isNotEmpty() -> Item(
                title = title,
                detail = context.getString(R.string.ready_mod_disabled, hits.first().file.name),
                tone = Tone.MISSING,
                action = null
            )
            else -> Item(
                title = title,
                detail = context.getString(R.string.ready_mod_missing),
                tone = Tone.MISSING,
                action = null
            )
        }
    }

    private fun overlayItem(context: Context): Item {
        val overlay = overlayFile
        return if (overlay.isFile) {
            Item(
                title = context.getString(R.string.ready_overlay),
                detail = context.getString(R.string.ready_overlay_ok),
                tone = Tone.READY,
                action = null
            )
        } else {
            Item(
                title = context.getString(R.string.ready_overlay),
                detail = context.getString(R.string.ready_overlay_unused),
                tone = Tone.INFO,
                action = null
            )
        }
    }
}
