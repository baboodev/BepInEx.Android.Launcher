package com.bepinex.android.tools;


import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import android.content.Context;
import android.system.Os;
import android.util.Log;
import dalvik.system.BaseDexClassLoader;
import java.util.ArrayList;

import com.bepinex.android.BootstrapActivity;

public class NativeLibraryManager {
    private static final String TAG = "NativeLibraryManager";

    // Public vendor sonames ClassLoaderFactory links into app namespaces via sphal.
    // "ALL" matches framework's own ClassLoaderFactory path (public vendor libs only).
    private static final String GRAPHICS_SONAME_LIST = "ALL";

    // createClassloaderNamespace LOG_FATALs if called twice for the same ClassLoader
    // ("There is already a namespace associated with this classloader").
    private static final AtomicBoolean graphicsNamespaceBound = new AtomicBoolean(false);
    private static volatile FusionConfig runtimeConfig;

    private static final ArrayList<String> FusionLibraries = new ArrayList<>();

    private static final ArrayList<String> CacheLibraries = new ArrayList<>();

    private static final ArrayList<String> DotnetLibraries = new ArrayList<>();

    public static void addFusionLibrary(String fusionLibName)
    {
        FusionLibraries.add(fusionLibName);
    }

    public static void addCacheLibrary(String dataLibName)
    {
        CacheLibraries.add(dataLibName);
    }

    public static void addDotnetLibrary(String dataLibName)
    {
        DotnetLibraries.add(dataLibName);
    }

    // this redirects library loading to the libraries we want the game to use
    public static void setupLibraryHooks(FusionConfig config) {
        runtimeConfig = config;
        setupNativeLibraries(config);
    }

    /** Live config for native bootstrap — avoids Intent Parcelable + ClassLoader loss. */
    public static FusionConfig getRuntimeConfig() {
        return runtimeConfig;
    }

    public static String findLibrary(String name) {
        FusionConfig config = runtimeConfig;
        if (config == null) return null;
        String directory;
        if (FusionLibraries.contains(name)) directory = config.appLibraryDirectory;
        else if (DotnetLibraries.contains(name)) directory = config.dotnetDirectory;
        else if (CacheLibraries.contains(name)) directory = config.codeCacheDirectory;
        else directory = config.gameLibraryDirectory;
        File library = new File(directory, "lib" + name + ".so");
        return library.isFile() ? library.getAbsolutePath() : null;
    }

    private static void setupNativeLibraries(FusionConfig config) {
        try {
            File overrides = new File(config.codeCacheDirectory, "native-overrides");
            if (!overrides.isDirectory() && !overrides.mkdirs()) {
                throw new IllegalStateException("Cannot create native library overrides");
            }
            for (String name : CacheLibraries) linkLibrary(overrides, name, config.codeCacheDirectory);
            for (String name : FusionLibraries) linkLibrary(overrides, name, config.appLibraryDirectory);
            for (String name : DotnetLibraries) linkLibrary(overrides, name, config.dotnetDirectory);

            // Unity often System.load(nativeLibraryDir + "/libmain.so") and bypasses
            // findLibrary. nativeLibraryDir is game-libs — replace stock libmain with
            // fusion so RegisterNatives + OpenSSL FORCE_LOAD run in the game linker ns.
            File gameLibs = new File(config.gameLibraryDirectory);
            if (gameLibs.isDirectory()) {
                for (String name : FusionLibraries) {
                    linkLibrary(gameLibs, name, config.appLibraryDirectory);
                }
            }

            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object hostPathList = pathListField.get(BootstrapActivity.class.getClassLoader());
            Method addNativePath = hostPathList.getClass().getDeclaredMethod("addNativePath", Collection.class);
            addNativePath.setAccessible(true);
            prependNativePath(hostPathList, addNativePath, config.gameLibraryDirectory);
            prependNativePath(hostPathList, addNativePath, overrides.getAbsolutePath());

            ClassLoader gameLoader = com.bepinex.android.hooks.GameRuntime.getGameClassLoader();
            if (gameLoader instanceof BaseDexClassLoader) {
                Object gamePathList = pathListField.get(gameLoader);
                prependNativePath(gamePathList, addNativePath, config.gameLibraryDirectory);
                prependNativePath(gamePathList, addNativePath, overrides.getAbsolutePath());
                // Do NOT call ensureGraphicsLinkerNamespace again here — the linker aborts if a
                // ClassLoader already has a namespace. prepareGameContext binds sphal once;
                // addNativePath only updates Java-side findLibrary paths (absolute loads still
                // use the existing ns + sphal shared links for libEGL/GLES).
                preloadOpenSslInto(gameLoader, config.gameLibraryDirectory);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot configure libraries", e);
        }
    }

    /**
     * Associate a proper linker namespace (with sphal shared-lib links) to {@code classLoader}.
     * Raw {@code new PathClassLoader(...)} skips {@code ClassLoaderFactory.createClassloaderNamespace},
     * which on Android 16+ / Adreno leaves {@code eglSubDriverAndroid.so} unreachable from the
     * game ns ({@code EGL_NOT_INITIALIZED}). No-op when the hidden API is missing — older devices
     * that already worked keep working. Must run at most once per process.
     */
    public static void ensureGraphicsLinkerNamespace(
            ClassLoader classLoader, Context context, String librarySearchPath, String dexPath) {
        if (classLoader == null) {
            return;
        }
        if (!graphicsNamespaceBound.compareAndSet(false, true)) {
            Log.i(TAG, "Linker namespace already bound; skip");
            return;
        }
        try {
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method createNs = null;
            // API 29+ signature (dexPath + sonameList).
            try {
                createNs = factory.getDeclaredMethod(
                        "createClassloaderNamespace",
                        ClassLoader.class, int.class, String.class, String.class,
                        boolean.class, String.class, String.class);
            } catch (NoSuchMethodException ignored) {
                // Older: no dexPath / sonameList.
                try {
                    createNs = factory.getDeclaredMethod(
                            "createClassloaderNamespace",
                            ClassLoader.class, int.class, String.class, String.class,
                            boolean.class);
                } catch (NoSuchMethodException ignored2) {
                    Log.w(TAG, "createClassloaderNamespace not found; skipping sphal link");
                    return;
                }
            }
            createNs.setAccessible(true);

            int targetSdk = 28;
            // Include vendor EGL/GLES search trees so Adreno's eglSubDriverAndroid.so
            // (under /vendor/lib64/egl) can be resolved from the game ClassLoader ns on
            // Android 16+. sphal shared links alone are not enough when Unity/libEGL
            // pulls the subdriver as a DT_NEEDED into this ns. Harmless on older APIs.
            String permitted = "/data:/mnt/expand"
                    + ":/vendor/lib64:/vendor/lib64/egl:/vendor/lib64/hw"
                    + ":/system/lib64:/system/vendor/lib64"
                    + ":/odm/lib64:/odm/lib64/egl";
            if (context != null) {
                android.content.pm.ApplicationInfo info = context.getApplicationInfo();
                if (info != null) {
                    targetSdk = info.targetSdkVersion > 0 ? info.targetSdkVersion : targetSdk;
                    if (info.dataDir != null && !info.dataDir.isEmpty()) {
                        permitted = info.dataDir + ":" + permitted;
                    }
                    if (info.nativeLibraryDir != null && !info.nativeLibraryDir.isEmpty()
                            && (librarySearchPath == null
                                || !librarySearchPath.contains(info.nativeLibraryDir))) {
                        librarySearchPath = librarySearchPath == null || librarySearchPath.isEmpty()
                                ? info.nativeLibraryDir
                                : librarySearchPath + File.pathSeparator + info.nativeLibraryDir;
                    }
                    if ((dexPath == null || dexPath.isEmpty()) && info.sourceDir != null) {
                        dexPath = info.sourceDir;
                        if (info.splitSourceDirs != null) {
                            for (String split : info.splitSourceDirs) {
                                dexPath = dexPath + File.pathSeparator + split;
                            }
                        }
                    }
                }
            }
            if (librarySearchPath == null) {
                librarySearchPath = "";
            }
            if (dexPath == null) {
                dexPath = "";
            }

            Object err;
            if (createNs.getParameterCount() == 7) {
                err = createNs.invoke(
                        null, classLoader, targetSdk, librarySearchPath, permitted,
                        false, dexPath, GRAPHICS_SONAME_LIST);
            } else {
                err = createNs.invoke(
                        null, classLoader, targetSdk, librarySearchPath, permitted, false);
            }
            if (err != null) {
                // Allow a later retry with a different ClassLoader if this one failed.
                graphicsNamespaceBound.set(false);
                Log.w(TAG, "createClassloaderNamespace: " + err);
            } else {
                Log.i(TAG, "Linker namespace ready (sphal graphics link)");
            }
        } catch (Throwable t) {
            graphicsNamespaceBound.set(false);
            Log.w(TAG, "ensureGraphicsLinkerNamespace failed (non-fatal)", t);
        }
    }

    /**
     * Load the game's OpenSSL into the game ClassLoader namespace before Mono
     * starts. Plain soname lookup otherwise binds conscrypt BoringSSL (no
     * BIO_new_mem_buf) and Heartopia Mono SIGSEGVs at OnDllHotFixFinish.
     */
    private static void preloadOpenSslInto(ClassLoader gameLoader, String gameLibsDir) {
        String[] libs = { "libcrypto.so", "libssl.so" };
        Method load0 = null;
        try {
            // Runtime.load0(ClassLoader, String) — absolute path into that ns
            load0 = Runtime.class.getDeclaredMethod("load0", ClassLoader.class, String.class);
            load0.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            try {
                load0 = Runtime.class.getDeclaredMethod("load0", Class.class, String.class);
                load0.setAccessible(true);
            } catch (NoSuchMethodException e) {
                android.util.Log.e("NativeLibraryManager", "Runtime.load0 not found", e);
                return;
            }
        }
        for (String lib : libs) {
            File so = new File(gameLibsDir, lib);
            if (!so.isFile()) continue;
            try {
                if (load0.getParameterTypes()[0] == ClassLoader.class) {
                    load0.invoke(Runtime.getRuntime(), gameLoader, so.getAbsolutePath());
                } else {
                    Class<?> anchor = Class.forName("java.lang.Object", false, gameLoader);
                    load0.invoke(Runtime.getRuntime(), anchor, so.getAbsolutePath());
                }
                android.util.Log.i("NativeLibraryManager", "Preloaded " + lib + " into game ClassLoader");
            } catch (Throwable t) {
                android.util.Log.w("NativeLibraryManager", "Preload " + lib + " failed: " + t.getMessage());
            }
        }
    }

    private static void linkLibrary(File directory, String name, String source) throws Exception {
        File link = new File(directory, "lib" + name + ".so");
        if (!link.delete()) {
            try {
                Os.lstat(link.getAbsolutePath());
                throw new IllegalStateException("Cannot replace " + link);
            } catch (android.system.ErrnoException e) {
                if (e.errno != android.system.OsConstants.ENOENT) throw e;
            }
        }
        Os.symlink(source + "/lib" + name + ".so", link.getAbsolutePath());
    }

    private static void prependNativePath(Object pathList, Method addNativePath, String path)
            throws ReflectiveOperationException {
        Field elementsField = pathList.getClass().getDeclaredField("nativeLibraryPathElements");
        elementsField.setAccessible(true);
        int previousCount = Array.getLength(elementsField.get(pathList));
        addNativePath.invoke(pathList, List.of(path));
        Object elements = elementsField.get(pathList);
        int count = Array.getLength(elements);
        if (count == previousCount) return;
        Object added = Array.get(elements, count - 1);
        for (int i = count - 1; i > 0; i--) Array.set(elements, i, Array.get(elements, i - 1));
        Array.set(elements, 0, added);
    }

}
