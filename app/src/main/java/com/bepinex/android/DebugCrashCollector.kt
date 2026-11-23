package com.bepinex.android

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Archives the state left behind by the previous run before this one starts.
 *
 * A native crash cannot execute a `finally` block, so the only reliable moment
 * to capture logcat, the system crash buffer, process exit records and the
 * BepInEx logs of every game is the next launcher start.
 *
 * Ported from the upstream BepInEx Android launcher and adapted to the per-game directory layout
 * used here (`BepInEx_Android/<packageName>/BepInEx/...`).
 */
object DebugCrashCollector {
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_LOG_FILES = 64
    private const val MAX_SNAPSHOTS = 8
    private const val PARK_DIR_NAME = "previous-session"
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    private val logNames = setOf(
        "main.log", "il2cpp.log", "LogOutput.log", "bepinexlogoutput.log",
        "BepInExLogOutput.log", "output_log.txt", "player.log"
    )

    /** Launcher files opened again on the next start. Parked before the new log is created. */
    private val launcherSessionFiles = listOf(
        "bepinex_launcher.log",
        "logcat.txt",
        "logcat.1.txt",
        "launcher_java_crash.txt",
        "launcher_java_crash_previous.txt",
        "game_logcat.txt"
    )

    /**
     * Moves the previous launcher session aside so [com.bepinex.android.BepInExLog.init]
     * can open fresh files without waiting for the dumpsys snapshot.
     * Same-directory rename, so this stays off the cold-start critical path.
     */
    fun parkPreviousSessionFiles(context: Context) {
        val extDir = context.getExternalFilesDir(null) ?: return
        val parking = File(extDir, PARK_DIR_NAME)
        if (!parking.isDirectory && !parking.mkdirs()) return
        for (name in launcherSessionFiles) {
            val source = File(extDir, name)
            if (!source.isFile) continue
            val dest = File(parking, name)
            if (dest.exists() && !dest.delete()) continue
            if (!source.renameTo(dest)) {
                copyIfPresent(source, dest)
                if (dest.isFile) source.delete()
            }
        }
    }

    fun collect(context: Context) {
        try {
            val debugRoot = File(context.filesDir, "debug").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val snapshot = File(debugRoot, stamp).apply { mkdirs() }

            writeCommand(snapshot, "logcat.txt", arrayOf("logcat", "-d", "-v", "threadtime"))
            writeCommand(snapshot, "crash_logcat.txt", arrayOf("logcat", "-b", "crash", "-d", "-v", "threadtime"))
            writeCommand(snapshot, "exit_info.txt", arrayOf("dumpsys", "activity", "exit-info", context.packageName))
            writeCommand(snapshot, "activities.txt", arrayOf("dumpsys", "activity", "activities"))
            writeCommand(snapshot, "package.txt", arrayOf("dumpsys", "package", context.packageName))
            writeCommand(snapshot, "meminfo.txt", arrayOf("dumpsys", "meminfo", context.packageName))
            writeCommand(snapshot, "properties.txt", arrayOf("getprop"))

            val exitRecords = ProcessExitDiagnostics.read(context)
            writeText(
                snapshot,
                "application_exit_info.txt",
                ProcessExitDiagnostics.format(exitRecords).ifBlank {
                    "No process exit information available (requires Android 11 or newer)."
                }
            )

            writeText(snapshot, "device.txt", buildString {
                appendLine("package=${context.packageName}")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine("model=${Build.MODEL}")
                appendLine("manufacturer=${Build.MANUFACTURER}")
                appendLine("time=${Date()}")
            })

            // Prefer files parked before BepInExLog.init. Fall back to the live
            // path when park was not called, so a snapshot still sees them.
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                val parking = File(extDir, PARK_DIR_NAME)
                launcherSessionFiles.forEach { name ->
                    val parked = File(parking, name)
                    val live = File(extDir, name)
                    val source = when {
                        parked.isFile -> parked
                        live.isFile -> live
                        else -> return@forEach
                    }
                    copyIfPresent(source, File(snapshot, name))
                }
            }

            // Copy the BepInEx logs and crash sidecars of every game seen so far.
            // The package list comes from the per-game directories on external
            // storage because this launcher is multi-game.
            val gamesRoot = BepInExPaths.getGamesRootDir()
            val gamePackages = gamesRoot.listFiles { file -> file.isDirectory }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
            gamePackages.forEach { packageName ->
                val gameRoot = BepInExPaths.getGameRootDir(packageName)
                gameRoot.walkTopDown().maxDepth(8)
                    .filter { it.isFile && it.length() <= MAX_FILE_BYTES && logNames.contains(it.name) }
                    .take(MAX_LOG_FILES)
                    .forEach { source ->
                        val relative = source.relativeTo(gameRoot).path.replace(File.separatorChar, '_')
                        copyIfPresent(source, File(snapshot, "${packageName}_$relative"))
                    }

                val sidecars = listOf(
                    BepInExPaths.getLogcatCaptureFile(packageName),
                    BepInExPaths.getLogcatCaptureArchiveFile(packageName),
                    BepInExPaths.getPreviousLogcatCaptureFile(packageName),
                    BepInExPaths.getJavaCrashFile(packageName),
                    BepInExPaths.getPreviousJavaCrashFile(packageName)
                )
                sidecars.forEach { source ->
                    copyIfPresent(source, File(snapshot, "${packageName}_${source.name}"))
                }
            }

            // Keep the directory bounded while retaining recent snapshots.
            debugRoot.listFiles { file -> file.isDirectory }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_SNAPSHOTS)
                ?.forEach { it.deleteRecursively() }
        } catch (_: Throwable) {
            // Diagnostics must never prevent the launcher from starting.
        }
    }

    private fun writeCommand(directory: File, name: String, command: Array<String>) {
        var process: Process? = null
        try {
            // Merge stderr into stdout. Reading one stream to EOF before the other
            // deadlocks once the unread pipe buffer fills (dumpsys does this).
            process = ProcessBuilder(command.toList())
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread({
                try {
                    process?.inputStream?.bufferedReader()?.use { input ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            synchronized(output) {
                                val room = (MAX_FILE_BYTES - output.length).toInt()
                                if (room > 0) {
                                    output.append(String(buf, 0, n.coerceAtMost(room)))
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }, "debug-crash-collector-io")
            reader.isDaemon = true
            reader.start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                synchronized(output) {
                    output.append("\n[collector] timed out after ${COMMAND_TIMEOUT_SECONDS}s\n")
                }
            }
            reader.join(2_000)
            val text = synchronized(output) { output.toString() }
            writeText(directory, name, text)
        } catch (error: Throwable) {
            writeText(directory, name, "collector failed: ${error.stackTraceToString()}")
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
        }
    }

    private fun copyIfPresent(source: File, target: File) {
        if (!source.isFile || source.length() > MAX_FILE_BYTES) return
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun writeText(directory: File, name: String, text: String) {
        File(directory, name).writeText(text)
    }
}
