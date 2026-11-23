package com.bepinex.android.hooks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import java.io.File;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import dalvik.system.PathClassLoader;
import com.bepinex.android.tools.NativeLibraryManager;
import com.bepinex.android.tools.FusionConfig;
import com.bepinex.android.R;
import com.bepinex.android.tools.CustomContextWrapper;

public final class GameRuntime {
    private static android.app.Application gameApplication;
    private static ClassLoader gameClassLoader;
    private GameRuntime() {}

    public static android.app.Application getGameApplication() { return gameApplication; }

    public static ClassLoader getGameClassLoader() { return gameClassLoader; }

    public static void initializeApplication(Context gameContext, Context fusionContext)
            throws ReflectiveOperationException {
        String className = gameContext.getApplicationInfo().className;
        if (gameApplication != null || className == null) return;
        android.app.Application application = (android.app.Application) gameContext.getClassLoader()
                .loadClass(className).getDeclaredConstructor().newInstance();
        Method attach = android.app.Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        attach.invoke(application, new CustomContextWrapper(gameContext, fusionContext, application));
        gameApplication = application;
        application.onCreate();
        Log.i("GameRuntime", "Initialized application " + className);
    }

    public static Context prepareGameContext(Context gameContext, ClassLoader fusionLoader)
            throws ReflectiveOperationException {
        android.content.pm.ApplicationInfo info = gameContext.getApplicationInfo();
        ArrayList<String> apks = new ArrayList<>();
        apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) Collections.addAll(apks, info.splitSourceDirs);
        // Keep the APK nativeLibraryDir in the search path. Replacing it entirely with
        // game-libs alone yields an isolated linker namespace whose permitted_paths are
        // only /data:/mnt/expand — on Android 16+/Adreno that blocks
        // /vendor/lib64/egl/eglSubDriverAndroid.so and Unity dies with EGL_NOT_INITIALIZED.
        String apkNativeDir = info.nativeLibraryDir != null ? info.nativeLibraryDir : "";
        String libraryPath = apkNativeDir;
        FusionConfig cfg = NativeLibraryManager.getRuntimeConfig();
        if (cfg != null && cfg.gameLibraryDirectory != null && !cfg.gameLibraryDirectory.isEmpty()) {
            libraryPath = apkNativeDir.isEmpty()
                    ? cfg.gameLibraryDirectory
                    : cfg.gameLibraryDirectory + File.pathSeparator + apkNativeDir;
        }
        String dexPath = String.join(File.pathSeparator, apks);
        ClassLoader gameLoader = new GameClassLoader(dexPath, libraryPath, fusionLoader);
        // Skip sphal ClassLoader link: on SM-S938B it dual-stacks EGL and breaks chooseConfig.
        gameClassLoader = gameLoader;
        return new ContextWrapper(gameContext) {
            @Override public ClassLoader getClassLoader() { return gameLoader; }
        };
    }

    private static final class GameClassLoader extends PathClassLoader {
        GameClassLoader(String dexPath, String libraryPath, ClassLoader parent) {
            super(dexPath, libraryPath, parent);
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    boolean platform = name.startsWith("java.") || name.startsWith("javax.") ||
                            name.startsWith("android.") || name.startsWith("dalvik.") ||
                            name.startsWith("sun.") || name.startsWith("org.w3c.") ||
                            name.startsWith("org.xml.") || name.startsWith("com.bepinex.android.");
                    if (!platform) {
                        try { loaded = findClass(name); }
                        catch (ClassNotFoundException ignored) { }
                    }
                    if (loaded == null) loaded = super.loadClass(name, false);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override public String findLibrary(String name) {
            String override = NativeLibraryManager.findLibrary(name);
            return override != null ? override : super.findLibrary(name);
        }
    }

    public static void install(Context gameContext) throws ReflectiveOperationException {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Method currentThread = threadClass.getDeclaredMethod("currentActivityThread");
        currentThread.setAccessible(true);
        Object thread = currentThread.invoke(null);
        Field instrumentation = threadClass.getDeclaredField("mInstrumentation");
        instrumentation.setAccessible(true);
        Instrumentation original = (Instrumentation) instrumentation.get(thread);
        if (!(original instanceof GameInstrumentation)) {
            instrumentation.set(thread, new GameInstrumentation(original, gameContext));
        }
        Log.i("GameRuntime", "Installed activity loading");
    }

    private static final class GameInstrumentation extends Instrumentation {
        private final Instrumentation original;
        private final Context gameContext;
        private final Map<Activity, Intent> pendingIntents = Collections.synchronizedMap(new WeakHashMap<>());
        private final java.util.Set<Activity> gameActivities = Collections.newSetFromMap(new WeakHashMap<>());

        GameInstrumentation(Instrumentation original, Context gameContext) {
            this.original = original;
            this.gameContext = gameContext;
        }

        @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            // Host ClassLoader first: FusionConfig lives in the launcher package.
            // Game ClassLoader cannot unparcel nextbep.config (returns null → native SIGABRT).
            ClassLoader hostLoader = InstrumentationHooks.class.getClassLoader();
            intent.setExtrasClassLoader(hostLoader);
            if (!InstrumentationHooks.isDynamicIntent(intent)) {
                intent.setExtrasClassLoader(gameContext.getClassLoader());
                return original.newActivity(loader, name, intent);
            }
            Intent target = InstrumentationHooks.resolveOriginalIntent(intent);
            if (target == null || target.getComponent() == null) {
                throw new InstantiationException("Missing dynamic activity intent");
            }
            target = new Intent(target);
            android.os.Parcelable nextConfig = null;
            if (intent.hasExtra(InstrumentationHooks.EXTRA_NEXT_CONFIG)) {
                nextConfig = intent.getParcelableExtra(InstrumentationHooks.EXTRA_NEXT_CONFIG);
            }
            int targetOrientation = intent.getIntExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION,
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            target.setExtrasClassLoader(gameContext.getClassLoader());
            if (nextConfig != null) {
                target.putExtra(InstrumentationHooks.EXTRA_NEXT_CONFIG, nextConfig);
            }
            if (targetOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                target.putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation);
            }
            Activity activity = original.newActivity(gameContext.getClassLoader(),
                    target.getComponent().getClassName(), target);
            pendingIntents.put(activity, target);
            gameActivities.add(activity);
            return activity;
        }

        private void prepare(Activity activity) {
            Intent target = pendingIntents.remove(activity);
            if (target == null) return;
            try {
                activity.setIntent(target);
                Context fusionBase = activity.getBaseContext();
                Field base = ContextWrapper.class.getDeclaredField("mBase");
                base.setAccessible(true);
                base.set(activity, new CustomContextWrapper(gameContext, fusionBase));
                if (gameApplication != null) {
                    Field application = Activity.class.getDeclaredField("mApplication");
                    application.setAccessible(true);
                    application.set(activity, gameApplication);
                }
                Field resources = ContextThemeWrapper.class.getDeclaredField("mResources");
                resources.setAccessible(true);
                resources.set(activity, null);
                ActivityInfo info = gameContext.getPackageManager().getActivityInfo(
                        new android.content.ComponentName(gameContext.getPackageName(), activity.getClass().getName()), 0);
                android.content.res.Resources.Theme theme = activity.getResources().newTheme();
                theme.applyStyle(info.getThemeResource() != 0 ? info.getThemeResource()
                        : android.R.style.Theme_Black_NoTitleBar, true);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    activity.setTheme(theme);
                } else {
                    Field oldTheme = ContextThemeWrapper.class.getDeclaredField("mTheme");
                    oldTheme.setAccessible(true);
                    oldTheme.set(activity, theme);
                }
                // Host stubs have no screenOrientation; stamp the game ActivityInfo value
                // onto the intent so apply / PiP-exit restore can find it.
                int orientation = InstrumentationHooks.resolveOrientationForActivity(activity, info);
                if (orientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    target.putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, orientation);
                    activity.setIntent(target);
                }
                InstrumentationHooks.applyTargetOrientation(activity);
                if (target.hasExtra(InstrumentationHooks.EXTRA_NEXT_CONFIG)) {
                    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                    Context loadingTheme = new ContextThemeWrapper(fusionBase, androidx.appcompat.R.style.Theme_AppCompat);
                    decor.addView(LayoutInflater.from(loadingTheme).inflate(R.layout.loading_view, decor, false));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot prepare activity", e);
            }
        }

        @Override public void callActivityOnCreate(Activity activity, Bundle state) {
            prepare(activity);
            original.callActivityOnCreate(activity, state);
        }
        @Override public void callActivityOnCreate(Activity activity, Bundle state, PersistableBundle persistentState) {
            prepare(activity);
            original.callActivityOnCreate(activity, state, persistentState);
        }
        @Override public void callActivityOnResume(Activity activity) {
            original.callActivityOnResume(activity);
            if (gameActivities.contains(activity)) {
                // PiP exit / multi-window often clears requestedOrientation back to
                // UNSPECIFIED — re-stamp sensorLandscape (or whatever the game declared).
                try {
                    InstrumentationHooks.applyTargetOrientation(activity);
                } catch (Throwable t) {
                    Log.e("GameRuntime", "Re-apply orientation on resume failed", t);
                }
                try {
                    com.bepinex.android.log.FloatingLog.maybeAttach(activity, gameContext.getPackageName());
                } catch (Throwable t) {
                    Log.e("GameRuntime", "Failed to attach floating log overlay", t);
                }
                try {
                    PictureInPictureHelper.onResume(activity);
                } catch (Throwable t) {
                    Log.e("GameRuntime", "PiP onResume failed", t);
                }
            }
        }
        @Override public void callActivityOnPause(Activity activity) {
            // Instrumentation never sees onUserLeaveHint — enter PiP here (API 26–30), or
            // rely on setAutoEnterEnabled (API 31+) which already swapped modes before pause.
            if (gameActivities.contains(activity)) {
                try {
                    PictureInPictureHelper.onBeforePause(activity);
                } catch (Throwable t) {
                    Log.e("GameRuntime", "PiP onBeforePause failed", t);
                }
            }
            original.callActivityOnPause(activity);
            if (gameActivities.contains(activity)) {
                try {
                    PictureInPictureHelper.onPause(activity);
                } catch (Throwable t) {
                    Log.e("GameRuntime", "PiP onPause resume failed", t);
                }
            }
        }
        @Override public void callActivityOnNewIntent(Activity activity, Intent intent) {
            Intent target = InstrumentationHooks.resolveOriginalIntent(intent);
            original.callActivityOnNewIntent(activity, target != null ? target : intent);
        }
        @Override public void callActivityOnDestroy(Activity activity) {
            pendingIntents.remove(activity);
            gameActivities.remove(activity);
            original.callActivityOnDestroy(activity);
        }
        @Override public boolean onException(Object object, Throwable error) {
            return original.onException(object, error);
        }

        private Intent wrap(Intent intent) {
            if (intent == null || intent.getComponent() == null || InstrumentationHooks.isDynamicIntent(intent)) return intent;
            String packageName = intent.getComponent().getPackageName();
            String className = intent.getComponent().getClassName();
            if (!packageName.equals(gameContext.getPackageName()) &&
                    !packageName.equals(com.bepinex.android.BuildConfig.APPLICATION_ID)) return intent;
            if (className.startsWith("com.bepinex.android.")) return intent;
            Intent wrapped = InstrumentationHooks.getInjectedIntent(intent);
            wrapped.setComponent(new android.content.ComponentName(
                    com.bepinex.android.BuildConfig.APPLICATION_ID,
                    com.bepinex.android.SecondaryStubActivity.class.getName()));
            try {
                ActivityInfo info = gameContext.getPackageManager().getActivityInfo(intent.getComponent(), 0);
                if (info.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    wrapped.putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, info.screenOrientation);
                }
            } catch (Exception ignored) {
            }
            return wrapped;
        }

        private ActivityResult start(Class<?> targetType, Context who, IBinder contextThread,
                                     IBinder token, Object target, Intent intent, int requestCode,
                                     Bundle options, UserHandle user) {
            // Stock GoogleSignImpl starts GMS with requestCode 9001; that fails with
            // DEVELOPER_ERROR under the launcher cert. Divert to login-xdsdk web OAuth
            // and later call AuthorizationCallback.signInSuccess with SignInToken.
            try {
                if (com.bepinex.android.auth.XdGoogleSignInBridge.tryIntercept(
                        who, target, intent, requestCode)) {
                    return null;
                }
            } catch (Throwable t) {
                Log.e("GameRuntime", "Google SignIn web intercept failed", t);
            }
            try {
                Class<?>[] types = user == null
                        ? new Class<?>[] {Context.class, IBinder.class, IBinder.class, targetType, Intent.class, int.class, Bundle.class}
                        : new Class<?>[] {Context.class, IBinder.class, IBinder.class, targetType, Intent.class, int.class, Bundle.class, UserHandle.class};
                Method method = Instrumentation.class.getDeclaredMethod("execStartActivity", types);
                method.setAccessible(true);
                Object[] args = user == null
                        ? new Object[] {who, contextThread, token, target, wrap(intent), requestCode, options}
                        : new Object[] {who, contextThread, token, target, wrap(intent), requestCode, options, user};
                return (ActivityResult) method.invoke(original, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException("Cannot launch activity", cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot dispatch activity", e);
            }
        }

        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               Activity target, Intent intent, int code, Bundle options) {
            return start(Activity.class, who, thread, token, target, intent, code, options, null);
        }
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               String target, Intent intent, int code, Bundle options) {
            return start(String.class, who, thread, token, target, intent, code, options, null);
        }
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               Activity target, Intent intent, int code, Bundle options, UserHandle user) {
            return start(Activity.class, who, thread, token, target, intent, code, options, user);
        }
    }
}