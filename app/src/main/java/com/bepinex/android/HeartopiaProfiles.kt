package com.bepinex.android

import android.app.ActivityManager
import android.content.Context
import android.util.Xml
import com.bepinex.android.auth.XdGoogleWebAuth
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException

/**
 * Save profiles for Heartopia running inside this launcher.
 *
 * The game process is the launcher UID, so XD account prefs and Unity player
 * prefs live under this app's shared_prefs. The save blob is
 * PersistentData/.userdata on external storage. HotFix AssetBundles beside it
 * are never moved.
 *
 * Parks land in /sdcard/BepInEx_Android/com.xd.xdtglobal.gp/profiles/&lt;name&gt;/.
 * Switching is refused while the :game process is running.
 */
object HeartopiaProfiles {
    const val ACCOUNT_PREFS = "TDS_ACCOUNT_PREFERENCE"
    const val AUTH_PREFS = "TDS_AUTHORIZATION_PREFERENCE"
    const val PLAYER_PREFS = "com.xd.xdtglobal.gp.v2.playerprefs"
    const val ZONE_KEY = "GetRecommendZoneServer"

    private const val ACTIVE_FILE = "active_profile.txt"
    private const val ZONE_FILE = "zone.xml"
    private const val USERDATA_NAME = "userdata"
    private const val BACKUP_DIR = ".switch-backup"

    data class Snapshot(
        val names: List<String>,
        val active: String,
        val gameRunning: Boolean,
        val unsavedLive: Boolean
    )

    class ProfileException(message: String) : Exception(message)

    private data class PrefEntry(
        val type: String,
        val key: String,
        val value: String?,
        val setValues: List<String>?
    )

    fun list(context: Context): Snapshot {
        val root = profilesRoot()
        val active = readActive(root)
        val names = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()
        return Snapshot(
            names = names,
            active = active,
            gameRunning = isGameProcessRunning(context),
            unsavedLive = active.isEmpty() && hasLiveData(context)
        )
    }

    fun create(context: Context, rawName: String): String {
        val name = rawName.trim()
        if (!isValidName(name)) {
            throw ProfileException(context.getString(R.string.profiles_invalid_name))
        }
        val root = profilesRoot()
        root.mkdirs()
        if (name.equals(readActive(root), ignoreCase = true) || File(root, name).exists()) {
            throw ProfileException(context.getString(R.string.profiles_exists))
        }
        val dir = File(root, name)
        if (!dir.mkdirs()) {
            throw ProfileException(context.getString(R.string.profiles_failed, "cannot create directory"))
        }
        BepInExLog.i("Profile created: ${dir.absolutePath}")
        return context.getString(R.string.profiles_created, name)
    }

    fun delete(context: Context, rawName: String): String {
        val name = rawName.trim()
        if (!isValidName(name)) {
            throw ProfileException(context.getString(R.string.profiles_invalid_name))
        }
        val root = profilesRoot()
        if (name.equals(readActive(root), ignoreCase = true)) {
            throw ProfileException(context.getString(R.string.profiles_delete_active))
        }
        val dir = File(root, name)
        if (!dir.isDirectory || !isInside(root, dir)) {
            throw ProfileException(context.getString(R.string.profiles_missing))
        }
        if (!dir.deleteRecursively()) {
            throw ProfileException(context.getString(R.string.profiles_failed, "cannot delete profile"))
        }
        BepInExLog.i("Profile deleted: ${dir.absolutePath}")
        return context.getString(R.string.profiles_deleted, name)
    }

    /**
     * Parks the live account (when there is one) and loads [rawName].
     * The target is checked before any live file is removed.
     */
    fun switch(context: Context, rawName: String): String {
        val target = rawName.trim()
        if (!isValidName(target)) {
            throw ProfileException(context.getString(R.string.profiles_invalid_name))
        }
        if (isGameProcessRunning(context)) {
            throw ProfileException(context.getString(R.string.profiles_game_running))
        }
        val root = profilesRoot()
        val active = readActive(root)
        if (active.equals(target, ignoreCase = true)) {
            return context.getString(R.string.profiles_already, target)
        }
        val targetDir = File(root, target)
        if (!targetDir.isDirectory || !isInside(root, targetDir)) {
            throw ProfileException(context.getString(R.string.profiles_missing))
        }

        val backup = File(root, BACKUP_DIR)
        if (backup.exists() && !backup.deleteRecursively()) {
            throw ProfileException(context.getString(R.string.profiles_failed, "cannot clear switch backup"))
        }
        // Park before touching the live files so a failed activate can copy them back.
        val parkedName: String? = if (hasLiveData(context)) {
            parkInto(context, backup)
            val parkName = if (active.isNotEmpty()) active else nextSavedName(root, target)
            val dest = File(root, parkName)
            if (dest.exists() && !dest.deleteRecursively()) {
                throw ProfileException(context.getString(R.string.profiles_failed, "cannot replace parked profile"))
            }
            if (!backup.renameTo(dest)) {
                copyRecursively(backup, dest)
                backup.deleteRecursively()
            }
            BepInExLog.i("Profile parked previous account as '$parkName' at ${dest.absolutePath}")
            parkName
        } else {
            null
        }

        try {
            clearLive(context)
            restoreFrom(context, targetDir)
            writeActive(root, target)
            BepInExLog.i("Profile switch activated '$target'")
        } catch (error: Exception) {
            BepInExLog.e("Profile switch failed; restoring previous account", error)
            try {
                clearLive(context)
                if (parkedName != null) restoreFrom(context, File(root, parkedName))
                if (active.isNotEmpty()) writeActive(root, active) else File(root, ACTIVE_FILE).delete()
            } catch (rollback: Exception) {
                BepInExLog.e("Profile rollback failed", rollback)
            }
            if (error is ProfileException) throw error
            throw ProfileException(
                context.getString(R.string.profiles_failed, error.message ?: error.javaClass.simpleName)
            )
        }
        return context.getString(R.string.profiles_switched, target)
    }

    fun isGameProcessRunning(context: Context): Boolean {
        val expected = context.packageName + ":game"
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (manager.runningAppProcesses?.any { it.processName == expected } == true) {
                return true
            }
        } catch (error: Exception) {
            BepInExLog.w("Profile game-process query failed: ${error.message}")
            return true
        }
        return try {
            val dirs = File("/proc").listFiles() ?: return true
            dirs.any { dir ->
                if (!dir.isDirectory || dir.name.any { !it.isDigit() }) return@any false
                val cmdline = File(dir, "cmdline")
                if (!cmdline.canRead()) return@any false
                val text = cmdline.readBytes().toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
                    .trim()
                text == expected || text.startsWith("$expected ")
            }
        } catch (error: Exception) {
            BepInExLog.w("Profile /proc scan failed: ${error.message}")
            true
        }
    }

    fun isZoneKey(name: String): Boolean = name.contains(ZONE_KEY)

    private fun profilesRoot(): File =
        File(BepInExPaths.getGameRootDir(XdGoogleWebAuth.HEARTOPIA_PACKAGE), "profiles")

    private fun userdataFile(): File =
        File(
            BepInExPaths.getGameRootDir(XdGoogleWebAuth.HEARTOPIA_PACKAGE),
            "PersistentData/.userdata"
        )

    private fun prefsDir(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs")

    private fun prefsFile(context: Context, name: String): File =
        File(prefsDir(context), "$name.xml")

    private fun playerPrefsFile(context: Context): File {
        val preferred = prefsFile(context, PLAYER_PREFS)
        if (preferred.isFile) return preferred
        val other = prefsDir(context).listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(".v2.playerprefs.xml")
        }
        if (other != null) {
            BepInExLog.i("Zone prefs file is ${other.name}")
            return other
        }
        return preferred
    }

    private fun hasLiveData(context: Context): Boolean {
        if (prefsFile(context, ACCOUNT_PREFS).isFile) return true
        if (prefsFile(context, AUTH_PREFS).isFile) return true
        if (userdataFile().exists()) return true
        return readMap(playerPrefsFile(context)).any { isZoneKey(it.key) }
    }

    private fun parkInto(context: Context, dir: File) {
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Cannot create ${dir.absolutePath}")
        }
        copyPrefsIfPresent(prefsFile(context, ACCOUNT_PREFS), File(dir, "$ACCOUNT_PREFS.xml"))
        copyPrefsIfPresent(prefsFile(context, AUTH_PREFS), File(dir, "$AUTH_PREFS.xml"))
        val zone = readMap(playerPrefsFile(context)).filter { isZoneKey(it.key) }
        val zoneFile = File(dir, ZONE_FILE)
        if (zone.isEmpty()) {
            if (zoneFile.exists()) zoneFile.delete()
        } else {
            writeMapAtomic(zoneFile, zone)
        }
        BepInExLog.i(
            "Profile zone keys parked: ${zone.size}" +
                if (zone.isEmpty()) "" else " (${zone.joinToString { "${it.key}=${it.value ?: it.setValues?.size}" }})"
        )
        val userdata = userdataFile()
        val parkedUserdata = File(dir, USERDATA_NAME)
        if (parkedUserdata.exists()) parkedUserdata.deleteRecursively()
        if (userdata.exists()) {
            copyRecursively(userdata, parkedUserdata)
            BepInExLog.i("Profile parked .userdata -> ${parkedUserdata.absolutePath}")
        } else {
            BepInExLog.i("Profile .userdata absent")
        }
    }

    private fun restoreFrom(context: Context, dir: File) {
        restorePrefs(prefsFile(context, ACCOUNT_PREFS), File(dir, "$ACCOUNT_PREFS.xml"))
        restorePrefs(prefsFile(context, AUTH_PREFS), File(dir, "$AUTH_PREFS.xml"))
        val livePrefs = playerPrefsFile(context)
        val kept = readMap(livePrefs).filterNot { isZoneKey(it.key) }
        val parkedZone = File(dir, ZONE_FILE)
        val zone = if (parkedZone.isFile) readMap(parkedZone).filter { isZoneKey(it.key) } else emptyList()
        val merged = kept + zone
        if (merged.isEmpty()) {
            if (livePrefs.exists() && !livePrefs.delete()) {
                throw IOException("Cannot delete ${livePrefs.absolutePath}")
            }
        } else {
            writeMapAtomic(livePrefs, merged)
        }
        BepInExLog.i("Profile restored ${zone.size} zone key(s) into ${livePrefs.name}")
        val liveUserdata = userdataFile()
        if (liveUserdata.exists() && !liveUserdata.deleteRecursively()) {
            throw IOException("Cannot delete ${liveUserdata.absolutePath}")
        }
        val parkedUserdata = File(dir, USERDATA_NAME)
        if (parkedUserdata.exists()) {
            copyRecursively(parkedUserdata, liveUserdata)
            BepInExLog.i("Profile restored .userdata from ${parkedUserdata.absolutePath}")
        } else {
            BepInExLog.i("Profile restore left .userdata absent")
        }
    }

    private fun clearLive(context: Context) {
        deleteIfPresent(prefsFile(context, ACCOUNT_PREFS))
        deleteIfPresent(prefsFile(context, AUTH_PREFS))
        val livePrefs = playerPrefsFile(context)
        if (livePrefs.isFile) {
            val kept = readMap(livePrefs).filterNot { isZoneKey(it.key) }
            if (kept.isEmpty()) {
                deleteIfPresent(livePrefs)
            } else {
                writeMapAtomic(livePrefs, kept)
            }
        }
        val userdata = userdataFile()
        if (userdata.exists() && !userdata.deleteRecursively()) {
            throw IOException("Cannot delete ${userdata.absolutePath}")
        }
    }

    private fun copyPrefsIfPresent(live: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!live.isFile) {
            BepInExLog.i("Profile parked ${live.name} (absent)")
            return
        }
        dest.parentFile?.mkdirs()
        live.copyTo(dest, overwrite = true)
        BepInExLog.i("Profile parked ${live.name} (${live.length()} bytes)")
    }

    private fun restorePrefs(live: File, parked: File) {
        if (parked.isFile) {
            live.parentFile?.mkdirs()
            parked.copyTo(live, overwrite = true)
            BepInExLog.i("Profile restored ${live.name} (${parked.length()} bytes)")
        } else if (live.exists() && !live.delete()) {
            throw IOException("Cannot delete ${live.absolutePath}")
        } else {
            BepInExLog.i("Profile cleared ${live.name}")
        }
    }

    private fun deleteIfPresent(file: File) {
        if (file.exists() && !file.delete()) {
            throw IOException("Cannot delete ${file.absolutePath}")
        }
    }

    private fun readActive(root: File): String {
        val file = File(root, ACTIVE_FILE)
        return if (file.isFile) file.readText().trim() else ""
    }

    private fun writeActive(root: File, name: String) {
        root.mkdirs()
        File(root, ACTIVE_FILE).writeText(name)
    }

    private fun nextSavedName(root: File, reserved: String): String {
        for (n in 1..9999) {
            val name = "Saved-$n"
            if (!name.equals(reserved, ignoreCase = true) && !File(root, name).exists()) return name
        }
        return "Saved-${System.currentTimeMillis()}"
    }

    private fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name.length > 64) return false
        if (name == "." || name == ".." || name.startsWith(".")) return false
        if (name.equals(ACTIVE_FILE, ignoreCase = true)) return false
        if (name.any { it.code < 0x20 || it in "\\/:*?\"<>|" }) return false
        return true
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.path
        val childPath = child.canonicalFile.path
        return childPath == rootPath || childPath.startsWith(rootPath + File.separator)
    }

    private fun copyRecursively(source: File, dest: File) {
        if (source.isDirectory) {
            if (!dest.mkdirs() && !dest.isDirectory) {
                throw IOException("Cannot create ${dest.absolutePath}")
            }
            source.listFiles()?.forEach { child ->
                copyRecursively(child, File(dest, child.name))
            }
        } else {
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
        }
    }

    private fun readMap(file: File): List<PrefEntry> {
        if (!file.isFile) return emptyList()
        val parser = Xml.newPullParser()
        file.inputStream().buffered().use { input ->
            parser.setInput(input, null)
            val entries = ArrayList<PrefEntry>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name != "map") {
                    val tag = parser.name
                    val key = parser.getAttributeValue(null, "name") ?: ""
                    when (tag) {
                        "set" -> {
                            val values = ArrayList<String>()
                            while (true) {
                                val inner = parser.next()
                                if (inner == XmlPullParser.END_TAG && parser.name == "set") break
                                if (inner == XmlPullParser.START_TAG && parser.name == "string") {
                                    values.add(readText(parser))
                                }
                                if (inner == XmlPullParser.END_DOCUMENT) break
                            }
                            entries.add(PrefEntry("set", key, null, values))
                        }
                        "string" -> entries.add(PrefEntry("string", key, readText(parser), null))
                        else -> entries.add(
                            PrefEntry(tag, key, parser.getAttributeValue(null, "value") ?: "", null)
                        )
                    }
                }
                parser.next()
            }
            return entries
        }
    }

    private fun readText(parser: XmlPullParser): String {
        return when (parser.next()) {
            XmlPullParser.TEXT -> {
                val text = parser.text ?: ""
                parser.next()
                text
            }
            else -> ""
        }
    }

    private fun writeMapAtomic(file: File, entries: List<PrefEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().buffered().use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, "UTF-8")
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "map")
            for (entry in entries) {
                when (entry.type) {
                    "string" -> {
                        serializer.startTag(null, "string")
                        serializer.attribute(null, "name", entry.key)
                        serializer.text(entry.value ?: "")
                        serializer.endTag(null, "string")
                    }
                    "set" -> {
                        serializer.startTag(null, "set")
                        serializer.attribute(null, "name", entry.key)
                        for (value in entry.setValues.orEmpty()) {
                            serializer.startTag(null, "string")
                            serializer.text(value)
                            serializer.endTag(null, "string")
                        }
                        serializer.endTag(null, "set")
                    }
                    else -> {
                        serializer.startTag(null, entry.type)
                        serializer.attribute(null, "name", entry.key)
                        serializer.attribute(null, "value", entry.value ?: "")
                        serializer.endTag(null, entry.type)
                    }
                }
            }
            serializer.endTag(null, "map")
            serializer.endDocument()
        }
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw IOException("Cannot replace ${file.absolutePath}")
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
