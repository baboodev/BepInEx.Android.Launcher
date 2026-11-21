package com.bepinex.android.tools;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class Utilities {
    private static final String TAG = "BepInEx";

    public static File getExternalGameDirectory(@Nullable String targetPackage, @Nullable String Folder) {
        File gameStorage = new File(Environment.getExternalStorageDirectory(), "BepInEx_Android");
        if (targetPackage != null) {
            gameStorage = new File(gameStorage, targetPackage);
        }
        if(Folder != null){
            gameStorage = new File(gameStorage, Folder);
        }
        if (!gameStorage.exists()) {
            gameStorage.mkdirs();
        }
        return gameStorage;
    }
    public static void applyWindowInsets(View root, int basePadding) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int insetTop;
            int insetBottom;
            int insetLeft;
            int insetRight;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                insetTop = bars.top;
                insetBottom = bars.bottom;
                insetLeft = bars.left;
                insetRight = bars.right;
            } else {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
                insetLeft = insets.getSystemWindowInsetLeft();
                insetRight = insets.getSystemWindowInsetRight();
            }

            v.setPadding(
                    basePadding + insetLeft,
                    basePadding + insetTop,
                    basePadding + insetRight,
                    basePadding + insetBottom
            );
            return insets;
        });
        root.requestApplyInsets();
    }

    public static String formatVersionText(String versionName, long versionCode) {
        if (versionCode > 0L) {
            return "v" + versionName + " (" + versionCode + ")";
        }
        return "v" + versionName;
    }
    public static void extractZipFromStream(ZipInputStream zis, File outputFolder) throws IOException {
        extractZipFromStream(zis, outputFolder, null);
    }

    public static void extractZipFromStream(ZipInputStream zis, File outputFolder, @Nullable String keepExistingPrefix) throws IOException {
        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
            throw new IOException("Failed to create output directory: " + outputFolder.getAbsolutePath());
        }
        byte[] buffer = new byte[8192];
            String outputRoot = outputFolder.getCanonicalPath() + File.separator;
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String entryName = ze.getName();
                if (entryName == null || entryName.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                // Windows-created zips may use backslashes; normalize before resolving paths.
                entryName = entryName.replace('\\', '/');

                File target = new File(outputFolder, entryName);
                String targetPath = target.getCanonicalPath();

                if (!targetPath.startsWith(outputRoot)) {
                    throw new IOException("Blocked zip entry outside output folder: " + entryName);
                }

                if (ze.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw new IOException("Failed to create directory: " + targetPath);
                    }
                } else if (keepExistingPrefix != null && entryName.startsWith(keepExistingPrefix) && target.isFile()) {
                    Log.i(TAG, "Keeping existing " + targetPath);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
                    }

                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, count);
                        }
                    }
                }

                zis.closeEntry();
            }
    }
    public static void extractZipFromAssets(Context context, String assetName, File outputFolder) {
        extractZipFromAssets(context, assetName, outputFolder, null);
    }

    public static void extractZipFromAssets(Context context, String assetName, File outputFolder, @Nullable String keepExistingPrefix) {
        try {
            try (InputStream is = context.getAssets().open(assetName);
                 ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
                  extractZipFromStream(zis, outputFolder, keepExistingPrefix);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract " + assetName + " from assets!", e);
        }
    }

    /**
     * When {@code extractNativeLibs=false}, {@link ApplicationInfo#nativeLibraryDir} is empty.
     * NextBep needs real on-disk .so files (ELF read + padded copy of libil2cpp).
     * Extract {@code lib/<abi>/*} from base/split APKs into {@code outputFolder}.
     */
    public static boolean ensureExtractedNativeLibs(ApplicationInfo gameInfo, File outputFolder, String abi) {
        if (gameInfo == null || outputFolder == null) return false;
        File marker = new File(outputFolder, ".native_libs_extracted");
        File il2cpp = new File(outputFolder, "libil2cpp.so");
        if (marker.isFile() && il2cpp.isFile() && il2cpp.length() > 0) {
            return true;
        }
        if (!outputFolder.isDirectory() && !outputFolder.mkdirs()) {
            Log.e(TAG, "Cannot create native lib extract dir: " + outputFolder);
            return false;
        }

        String abiDir = "lib/" + abi + "/";
        // Some packages use arm64 vs arm64-v8a inconsistently inside the zip.
        String[] prefixes = new String[] {
                abiDir,
                "lib/arm64-v8a/",
                "lib/arm64/",
                "lib/armeabi-v7a/"
        };

        List<String> apks = new ArrayList<>();
        if (gameInfo.sourceDir != null) apks.add(gameInfo.sourceDir);
        if (gameInfo.splitSourceDirs != null) Collections.addAll(apks, gameInfo.splitSourceDirs);

        int copied = 0;
        byte[] buffer = new byte[64 * 1024];
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    String matchedPrefix = null;
                    for (String prefix : prefixes) {
                        if (name.startsWith(prefix) && name.endsWith(".so") && name.indexOf('/', prefix.length()) < 0) {
                            matchedPrefix = prefix;
                            break;
                        }
                    }
                    if (matchedPrefix == null) continue;

                    File target = new File(outputFolder, name.substring(matchedPrefix.length()));
                    if (target.isFile() && target.length() == entry.getSize()) {
                        copied++;
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = new FileOutputStream(target)) {
                        int count;
                        while ((count = in.read(buffer)) > 0) {
                            out.write(buffer, 0, count);
                        }
                    }
                    copied++;
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed extracting native libs from " + apk, e);
            }
        }

        if (!il2cpp.isFile() || il2cpp.length() == 0) {
            Log.e(TAG, "libil2cpp.so missing after APK extract into " + outputFolder);
            return false;
        }
        try {
            if (!marker.createNewFile()) {
                Log.w(TAG, "native lib marker already present");
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to create native lib marker", e);
        }
        Log.i(TAG, "Extracted " + copied + " native libraries to " + outputFolder);
        return true;
    }

    public static boolean copyAssets(ApplicationInfo gameInfo, File outputFolder) {
        File marker = new File(outputFolder, ".bepinex_copied");
        if (marker.isFile()) {
            return true;
        }

        List<String> apks = new ArrayList<>();
        if (gameInfo.sourceDir != null) apks.add(gameInfo.sourceDir);
        if (gameInfo.splitSourceDirs != null) Collections.addAll(apks, gameInfo.splitSourceDirs);

        String outputRoot;
        try {
            outputRoot = outputFolder.getCanonicalPath() + File.separator;
        } catch (IOException e) {
            Log.e(TAG, "Failed to resolve " + outputFolder, e);
            return false;
        }

        int copied = 0;
        byte[] buffer = new byte[64 * 1024];
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith("assets/")) continue;

                    File target = new File(outputFolder, name.substring("assets/".length()));
                    if (!target.getCanonicalPath().startsWith(outputRoot)) continue;
                    if (target.isFile() && target.length() == entry.getSize()) continue;

                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create " + parent);
                    }
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = new FileOutputStream(target)) {
                        int count;
                        while ((count = in.read(buffer)) > 0) {
                            out.write(buffer, 0, count);
                        }
                    }
                    copied++;
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy Unity Data assets from " + apk, e);
                return false;
            }
        }

        try {
            if (!marker.createNewFile()) {
                Log.w(TAG, "Failed to create asset copy marker: " + marker.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to create asset copy marker", e);
        }
        Log.i(TAG, "Successfully copied " + copied + " Unity Data assets to: " + outputFolder.getAbsolutePath());
        return true;
    }

    public static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!deleteRecursive(f)) {
                        return false;
                    }
                }
            }
        }

        return file.delete();
    }

    public static void extractZipFromInputStream(InputStream inputStream, File OutPutFolder) {
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(inputStream))) {
                extractZipFromStream(zis, OutPutFolder);
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract zip from InputStream!", e);
        }
    }

    /**
     * Product path (variant A): BepInEx owns
     * {@code BepInEx_Android/<pkg>/PersistentData} via {@code getExternalFilesDir}.
     * Empty on first launch is expected — the game HotFix downloads AssetBundles
     * there. Never read {@code Android/data/<other-pkg>/} from this process
     * (blocked on Android 11+ regardless of MANAGE_EXTERNAL_STORAGE).
     *
     * @return true if the directory exists (or was created)
     */
    public static boolean ensurePersistentDataDir(String targetPackage, File dest) {
        if (dest.isDirectory()) {
            Log.i(TAG, "PersistentData ready: " + dest.getAbsolutePath());
            return true;
        }
        if (dest.mkdirs()) {
            Log.i(TAG, "PersistentData created (first run; game will download resources): "
                    + dest.getAbsolutePath());
            return true;
        }
        Log.e(TAG, "Cannot create PersistentData: " + dest.getAbsolutePath());
        return false;
    }

}
