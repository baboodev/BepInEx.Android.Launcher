package com.bepinex.android.hooks;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.bepinex.android.settings.AppSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Picture-in-Picture for the hosted Unity game activity.
 *
 * Stub activities declare {@code supportsPictureInPicture}. On API 31+ the system
 * auto-enters PiP from {@code setAutoEnterEnabled} when the user leaves the task —
 * manual {@code enterPictureInPictureMode} is only a fallback for API 26–30 (and must
 * never pass params that clear auto-enter). Unity is resumed after pause so the sim
 * keeps ticking in the floating window.
 *
 * Entering PiP while Unity is still binding its first surface can permanently stick
 * the engine to the PiP pixel size. We (1) keep auto-enter off until the content view
 * has been near-fullscreen for a grace period and (2) force a Unity surface resize
 * when leaving PiP.
 */
public final class PictureInPictureHelper {
    private static final String TAG = "BugtopiaPiP";
    /** Android rejects PiP aspect ratios above ~2.39. */
    private static final float MAX_ASPECT = 2.38f;
    /** Content must stay near-fullscreen this long before auto-enter is allowed. */
    private static final long ARM_GRACE_MS = 8_000L;
    /** Content short edge must be at least this fraction of the display short edge. */
    private static final float READY_SIZE_FRACTION = 0.85f;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<Activity, Boolean> focusHooked =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Activity, Boolean> layoutHooked =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Activity, Long> firstFullscreenAtMs =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Activity, Boolean> wasInPip =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Activity, int[]> lastFullscreenSize =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PictureInPictureHelper() {}

    public static boolean isSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    public static boolean isEnabled(Context context) {
        return AppSettings.INSTANCE.isPipOnMinimizeEnabled(context);
    }

    /** Call from game-activity resume: sync auto-enter and watch focus / layout. */
    public static void onResume(Activity activity) {
        if (!isSupported(activity)) return;
        installFocusHook(activity);
        installLayoutHook(activity);
        boolean leftPip = notePipTransition(activity);
        if (isInPip(activity)) {
            // Keep Unity's swapchain at the pre-PiP size so the engine never bakes the
            // thumb buffer (SF sourceCrop stuck at ~307x614 after expand).
            pinUnityBufferToFullscreen(activity, "resumeInPip");
        } else {
            rememberFullscreenSize(activity);
            // Leaving PiP drops requestedOrientation to UNSPECIFIED on some OEMs.
            InstrumentationHooks.applyTargetOrientation(activity);
        }
        syncAutoEnter(activity);
        // Leaving PiP often resumes before the content view has the new size — defer restore.
        if (leftPip) {
            scheduleRestoreUnityResolution(activity, "resumeAfterPip");
        }
    }

    public static void onConfigurationChanged(Activity activity) {
        if (!isSupported(activity)) return;
        boolean leftPip = notePipTransition(activity);
        if (isInPip(activity)) {
            pinUnityBufferToFullscreen(activity, "configInPip");
        } else {
            rememberFullscreenSize(activity);
            InstrumentationHooks.applyTargetOrientation(activity);
        }
        if (leftPip) {
            scheduleRestoreUnityResolution(activity, "configChanged");
        }
        syncAutoEnter(activity);
    }
    public static void syncAutoEnter(Activity activity) {
        if (!isSupported(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            boolean armed = isArmedForPip(activity);
            boolean enabled = isEnabled(activity) && armed;
            // Once armed, keep the GL buffer pinned at fullscreen so a later PiP shrink
            // cannot teach Unity a thumb-sized swapchain.
            if (armed && isEnabled(activity)) {
                rememberFullscreenSize(activity);
                pinUnityBufferToFullscreen(activity, "armedPin");
            }
            activity.setPictureInPictureParams(buildParams(activity, enabled));
            Log.i(TAG, "setAutoEnterEnabled=" + enabled
                    + " component=" + activity.getComponentName()
                    + " ratio=" + aspectRatio(activity)
                    + " armed=" + armed);
        } catch (Throwable t) {
            Log.w(TAG, "syncAutoEnter failed", t);
        }
    }

    /**
     * Instrumentation pause path. API 31+ relies on {@code setAutoEnterEnabled}; calling
     * {@code enterPictureInPictureMode} here with autoEnter=false used to wipe that flag
     * on the server even when enter returned false. Pre-S still needs a manual enter.
     */
    public static void onBeforePause(Activity activity) {
        if (!isSupported(activity) || !isEnabled(activity)) return;
        if (activity.isFinishing() || activity.isChangingConfigurations()) return;
        if (!isArmedForPip(activity)) return;
        // Pin even if already in PiP — API 31+ auto-enter often lands us here AFTER the
        // mode switch, and the early-return used to skip the pin entirely.
        rememberFullscreenSize(activity);
        pinUnityBufferToFullscreen(activity, "beforePause");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return;
        }
        enterPip(activity, "beforePause");
    }

    public static void onPause(Activity activity) {
        if (!isSupported(activity)) return;
        notePipTransition(activity);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !activity.isInPictureInPictureMode()) return;
        pinUnityBufferToFullscreen(activity, "pauseInPip");
        resumeUnityPlayer(activity);
    }

    public static void enterPip(Activity activity, String reason) {
        if (!isSupported(activity) || !isEnabled(activity)) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (!isArmedForPip(activity)) {
            Log.i(TAG, "enterPip(" + reason + ") skipped — not armed yet");
            return;
        }
        try {
            if (activity.isInPictureInPictureMode()) return;
            rememberFullscreenSize(activity);
            // Pin BEFORE the system shrinks the window — surfaceChanged then reports the
            // fullscreen buffer size, not the PiP thumb size.
            pinUnityBufferToFullscreen(activity, "beforeEnterPip");
            boolean keepAuto = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            PictureInPictureParams params = buildParams(activity, keepAuto && isArmedForPip(activity));
            boolean ok = activity.enterPictureInPictureMode(params);
            Log.i(TAG, "enterPictureInPictureMode(" + reason + ") → " + ok
                    + " focus=" + activity.hasWindowFocus()
                    + " finishing=" + activity.isFinishing()
                    + " ratio=" + params.getAspectRatio());
            if (ok) {
                wasInPip.put(activity, Boolean.TRUE);
                pinUnityBufferToFullscreen(activity, "enteredPip");
                resumeUnityPlayer(activity);
            } else if (keepAuto) {
                syncAutoEnter(activity);
            }
        } catch (Throwable t) {
            Log.e(TAG, "enterPictureInPictureMode(" + reason + ") failed", t);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { syncAutoEnter(activity); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * True once the game content has been near-fullscreen for {@link #ARM_GRACE_MS}.
     * Blocks Home-auto-enter during early Unity / asset load so the engine cannot bake
     * the PiP thumb size as its permanent resolution.
     */
    private static boolean isArmedForPip(Activity activity) {
        if (isInPip(activity)) return true;
        if (!isNearFullscreen(activity)) {
            firstFullscreenAtMs.remove(activity);
            return false;
        }
        Long since = firstFullscreenAtMs.get(activity);
        long now = System.currentTimeMillis();
        if (since == null) {
            firstFullscreenAtMs.put(activity, now);
            return false;
        }
        return (now - since) >= ARM_GRACE_MS;
    }

    private static boolean isNearFullscreen(Activity activity) {
        int[] size = contentSize(activity);
        if (size == null) return false;
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int displayShort = Math.min(dm.widthPixels, dm.heightPixels);
        int viewShort = Math.min(size[0], size[1]);
        return displayShort > 0 && viewShort >= displayShort * READY_SIZE_FRACTION;
    }

    private static boolean isInPip(Activity activity) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode();
    }

    /** @return true when this call observed a transition out of PiP */
    private static boolean notePipTransition(Activity activity) {
        boolean inPip = isInPip(activity);
        Boolean was = wasInPip.put(activity, inPip);
        boolean enteredPip = Boolean.FALSE.equals(was) && inPip;
        boolean leftPip = Boolean.TRUE.equals(was) && !inPip;
        if (enteredPip) {
            Log.i(TAG, "entered PiP");
            pinUnityBufferToFullscreen(activity, "noteEnteredPip");
        }
        if (leftPip) {
            Log.i(TAG, "left PiP");
        }
        return leftPip;
    }
    private static void rememberFullscreenSize(Activity activity) {
        if (isInPip(activity)) return;
        int[] size = contentSize(activity);
        if (size == null) return;
        if (!isNearFullscreen(activity)) return;
        lastFullscreenSize.put(activity, size);
        if (!firstFullscreenAtMs.containsKey(activity)) {
            firstFullscreenAtMs.put(activity, System.currentTimeMillis());
        }
    }

    private static int[] contentSize(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        int w = content.getWidth();
        int h = content.getHeight();
        if (w <= 0 || h <= 0) return null;
        return new int[] { w, h };
    }

    private static void scheduleRestoreUnityResolution(Activity activity, String reason) {
        // Keep the holder pinned at fullscreen — do NOT setSizeFromLayout after PiP, that
        // let Unity re-bake a small BLAST buffer (748x1496 / 307x614). First tick also
        // forces a SurfaceView GONE→VISIBLE recreate so nativeRecreateGfxState sees a
        // brand-new fullscreen Surface.
        MAIN.post(() -> restoreUnityResolution(activity, reason + "#0", true));
        MAIN.postDelayed(() -> restoreUnityResolution(activity, reason + "#1", false), 200);
        MAIN.postDelayed(() -> restoreUnityResolution(activity, reason + "#2", false), 800);
        MAIN.postDelayed(() -> restoreUnityResolution(activity, reason + "#3", false), 2000);
    }

    private static void restoreUnityResolution(Activity activity, String reason,
                                               boolean forceRecreate) {
        if (activity.isFinishing() || isInPip(activity)) return;
        Object player = findUnityPlayer(activity);
        int[] target = resolveFullscreenTarget(activity);
        SurfaceView glView = findUnityGlSurfaceView(player, activity);

        Log.i(TAG, "restoreUnityResolution(" + reason + ") → "
                + target[0] + "x" + target[1]
                + " glView=" + (glView != null
                ? (glView.getWidth() + "x" + glView.getHeight()) : "null")
                + " recreate=" + forceRecreate);

        View content = activity.findViewById(android.R.id.content);
        if (content != null) {
            content.requestLayout();
            content.forceLayout();
        }
        if (player instanceof View) {
            ((View) player).requestLayout();
            ((View) player).forceLayout();
        }

        InstrumentationHooks.applyTargetOrientation(activity);
        tryInvokeConfigurationChanged(player, activity);

        if (glView == null || target[0] <= 0 || target[1] <= 0) {
            resumeUnityPlayer(activity);
            return;
        }

        try {
            glView.getHolder().setFixedSize(target[0], target[1]);
            Log.i(TAG, "SurfaceHolder.setFixedSize(" + target[0] + "," + target[1] + ")");
            if (forceRecreate) {
                forceUnitySurfaceRecreate(activity, glView, player, target[0], target[1]);
            } else {
                rebindUnityGfxSurface(player, glView, reason);
                resumeUnityPlayer(activity);
            }
        } catch (Throwable t) {
            Log.w(TAG, "SurfaceHolder restore failed", t);
            resumeUnityPlayer(activity);
        }
    }

    /**
     * Lock mGlView's SurfaceHolder to the last fullscreen size. Used in PiP so
     * surfaceChanged reports fullscreen w/h, and after exit so the buffer cannot
     * shrink back to a thumb-sized BLAST allocation.
     */
    private static void pinUnityBufferToFullscreen(Activity activity, String reason) {
        int[] size = lastFullscreenSize.get(activity);
        if (size == null || size[0] <= 0 || size[1] <= 0) {
            size = resolveFullscreenTarget(activity);
        }
        if (size[0] <= 0 || size[1] <= 0) return;
        Object player = findUnityPlayer(activity);
        SurfaceView glView = findUnityGlSurfaceView(player, activity);
        if (glView == null) return;
        try {
            SurfaceHolder holder = glView.getHolder();
            android.graphics.Rect cur = holder.getSurfaceFrame();
            if (cur != null && cur.width() == size[0] && cur.height() == size[1]) {
                // Already pinned — still log once at verbose path via pin reason.
            }
            holder.setFixedSize(size[0], size[1]);
            Log.i(TAG, "pinUnityBufferToFullscreen(" + reason + ") "
                    + size[0] + "x" + size[1]
                    + " was=" + (cur != null ? cur.width() + "x" + cur.height() : "null"));
        } catch (Throwable t) {
            Log.w(TAG, "pinUnityBufferToFullscreen failed", t);
        }
    }

    /**
     * GONE→VISIBLE so UnityPlayer$18 runs surfaceDestroyed/Created with the pinned
     * fullscreen fixed size. Avoid calling this while in PiP.
     */
    private static void forceUnitySurfaceRecreate(Activity activity, SurfaceView glView,
                                                  Object player, int targetW, int targetH) {
        try {
            glView.getHolder().setFixedSize(targetW, targetH);
        } catch (Throwable ignored) {}
        Log.i(TAG, "forceUnitySurfaceRecreate GONE→VISIBLE " + targetW + "x" + targetH);
        glView.setVisibility(View.GONE);
        glView.post(() -> {
            if (activity.isFinishing() || isInPip(activity) || glView.getWindowToken() == null) {
                return;
            }
            try {
                glView.getHolder().setFixedSize(targetW, targetH);
            } catch (Throwable ignored) {}
            glView.setVisibility(View.VISIBLE);
            tryInvokeConfigurationChanged(player, activity);
            MAIN.postDelayed(() -> rebindUnityGfxSurface(player, glView, "afterRecreate"), 100);
            resumeUnityPlayer(activity);
        });
    }

    /**
     * UnityPlayer$18.surfaceChanged path, queued onto Unity's main thread via
     * updateDisplayInternal — do not invoke nativeRecreateGfxState on the UI thread.
     */
    private static void rebindUnityGfxSurface(Object player, SurfaceView glView, String reason) {
        if (player == null || glView == null) return;
        SurfaceHolder holder = glView.getHolder();
        Surface surface = holder.getSurface();
        android.graphics.Rect frame = holder.getSurfaceFrame();
        Log.i(TAG, "rebindUnityGfxSurface(" + reason + ") frame="
                + (frame != null ? frame.width() + "x" + frame.height() : "null")
                + " valid=" + (surface != null && surface.isValid()));
        if (surface == null || !surface.isValid()) return;

        try {
            Method update = findDeclaredMethod(player.getClass(),
                    "updateDisplayInternal", int.class, Surface.class);
            if (update != null) {
                update.setAccessible(true);
                Object ret = update.invoke(player, 0, surface);
                Log.i(TAG, "UnityPlayer.updateDisplayInternal(0, surface) → " + ret);
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateDisplayInternal failed", t);
        }
        try {
            Method send = findDeclaredMethod(player.getClass(), "sendSurfaceChangedEvent");
            if (send != null) {
                send.setAccessible(true);
                send.invoke(player);
                Log.i(TAG, "UnityPlayer.sendSurfaceChangedEvent");
            }
        } catch (Throwable t) {
            Log.w(TAG, "sendSurfaceChangedEvent failed", t);
        }
    }

    private static Method findDeclaredMethod(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static int[] resolveFullscreenTarget(Activity activity) {
        int[] remembered = lastFullscreenSize.get(activity);
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        View content = activity.findViewById(android.R.id.content);

        int targetW = remembered != null ? remembered[0] : 0;
        int targetH = remembered != null ? remembered[1] : 0;
        if (content != null) {
            int cw = content.getWidth();
            int ch = content.getHeight();
            if (cw > targetW && ch > 0) {
                targetW = cw;
                targetH = ch;
            }
        }
        if (targetW <= 0 || targetH <= 0) {
            targetW = dm.widthPixels;
            targetH = dm.heightPixels;
        }
        if (wantsLandscape(activity) && targetW < targetH) {
            int tmp = targetW;
            targetW = targetH;
            targetH = tmp;
        }
        return new int[] { targetW, targetH };
    }

    private static boolean wantsLandscape(Activity activity) {
        int requested = activity.getRequestedOrientation();
        if (requested == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                || requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || requested == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                || requested == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE) {
            return true;
        }
        return activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** UnityPlayer.mGlView — the SurfaceView whose Callback drives nativeRecreateGfxState. */
    private static SurfaceView findUnityGlSurfaceView(Object player, Activity activity) {
        if (player != null) {
            for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField("mGlView");
                    field.setAccessible(true);
                    Object value = field.get(player);
                    if (value instanceof SurfaceView) return (SurfaceView) value;
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable t) {
                    Log.w(TAG, "mGlView read failed on " + c.getName(), t);
                }
            }
            if (player instanceof ViewGroup) {
                SurfaceView nested = findSurfaceView((View) player);
                if (nested != null) return nested;
            }
        }
        View content = activity.findViewById(android.R.id.content);
        return content != null ? findSurfaceView(content) : null;
    }

    private static SurfaceView findSurfaceView(View root) {
        if (root instanceof SurfaceView) return (SurfaceView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView found = findSurfaceView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Clear any SurfaceHolder fixed size left over from the PiP surface. */
    private static void resetSurfaceViewFixedSize(View root) {
        if (root instanceof SurfaceView) {
            try {
                ((SurfaceView) root).getHolder().setSizeFromLayout();
            } catch (Throwable t) {
                Log.w(TAG, "setSizeFromLayout failed", t);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                resetSurfaceViewFixedSize(group.getChildAt(i));
            }
        }
    }

    private static void tryInvokeConfigurationChanged(Object player, Activity activity) {
        if (player == null) return;
        try {
            Method m = player.getClass().getMethod("configurationChanged", Configuration.class);
            m.invoke(player, activity.getResources().getConfiguration());
            Log.i(TAG, "UnityPlayer.configurationChanged");
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            Log.w(TAG, "UnityPlayer.configurationChanged failed", t);
        }
    }

    private static void installFocusHook(Activity activity) {
        if (focusHooked.put(activity, Boolean.TRUE) != null) return;
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor == null) {
            focusHooked.remove(activity);
            return;
        }
        ViewTreeObserver.OnWindowFocusChangeListener listener = hasFocus -> {
            if (hasFocus) {
                notePipTransition(activity);
                if (!isInPip(activity)) {
                    rememberFullscreenSize(activity);
                    syncAutoEnter(activity);
                }
                return;
            }
            if (!isArmedForPip(activity)) return;
            if (!isEnabled(activity) || activity.isFinishing()) return;
            if (isInPip(activity)) {
                pinUnityBufferToFullscreen(activity, "focusLostInPip");
                return;
            }
            // Pin before the system auto-enters PiP (API 31+) so the first
            // surfaceChanged after the shrink still reports fullscreen w/h.
            pinUnityBufferToFullscreen(activity, "focusLost");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return;
            enterPip(activity, "focusLost");
        };
        decor.getViewTreeObserver().addOnWindowFocusChangeListener(listener);
        Log.i(TAG, "window-focus hook installed");
    }

    private static void installLayoutHook(Activity activity) {
        if (layoutHooked.put(activity, Boolean.TRUE) != null) return;
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            layoutHooked.remove(activity);
            return;
        }
        content.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int oldW = oldRight - oldLeft;
            int newW = right - left;
            int newH = bottom - top;
            if (newW == oldW && newH == (oldBottom - oldTop)) return;
            boolean leftPip = notePipTransition(activity);
            if (isInPip(activity)) {
                pinUnityBufferToFullscreen(activity, "pipLayout");
                return;
            }
            rememberFullscreenSize(activity);
            syncAutoEnter(activity);
            // Grew significantly or explicit PiP exit → nudge Unity to the new surface.
            if (leftPip || (oldW > 0 && newW > oldW * 1.5f)) {
                scheduleRestoreUnityResolution(activity, leftPip ? "layoutLeftPip" : "layoutGrow");
            }
        });
        Log.i(TAG, "layout hook installed");
    }

    private static PictureInPictureParams buildParams(Activity activity, boolean autoEnter) {
        Rational ratio = aspectRatio(activity);
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(ratio);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter && isEnabled(activity));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setSeamlessResizeEnabled(true);
        }
        return builder.build();
    }

    private static Rational aspectRatio(Activity activity) {
        int[] remembered = lastFullscreenSize.get(activity);
        int w;
        int h;
        if (remembered != null) {
            w = remembered[0];
            h = remembered[1];
        } else {
            View content = activity.findViewById(android.R.id.content);
            w = content != null ? content.getWidth() : 0;
            h = content != null ? content.getHeight() : 0;
            if (w <= 0 || h <= 0) {
                w = activity.getResources().getDisplayMetrics().widthPixels;
                h = activity.getResources().getDisplayMetrics().heightPixels;
            }
        }
        // Prefer the locked game orientation over the current display config — after a
        // portrait PiP expand the config may still be portrait while the game is landscape.
        int requested = activity.getRequestedOrientation();
        boolean wantLandscape =
                requested == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        || requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        || requested == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        || requested == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                        || activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE;
        if (wantLandscape && h > w && w > 0) {
            int tmp = w;
            w = h;
            h = tmp;
        }
        if (w <= 0 || h <= 0) return new Rational(16, 9);

        float ratio = (float) w / (float) h;
        if (ratio > MAX_ASPECT) {
            w = Math.max(1, Math.round(h * MAX_ASPECT));
        } else if (ratio < 1f / MAX_ASPECT) {
            h = Math.max(1, Math.round(w * MAX_ASPECT));
        }
        return new Rational(w, h);
    }

    private static void resumeUnityPlayer(Activity activity) {
        Object player = findUnityPlayer(activity);
        if (player == null) {
            Log.w(TAG, "UnityPlayer not found — cannot resume in PiP");
            return;
        }
        try {
            Method resume = player.getClass().getMethod("resume");
            resume.invoke(player);
            Method windowFocus = player.getClass().getMethod("windowFocusChanged", boolean.class);
            windowFocus.invoke(player, true);
            Log.i(TAG, "UnityPlayer resumed for PiP");
        } catch (Throwable t) {
            Log.w(TAG, "UnityPlayer.resume failed", t);
        }
    }

    private static Object findUnityPlayer(Activity activity) {
        for (Class<?> c = activity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("mUnityPlayer");
                field.setAccessible(true);
                Object value = field.get(activity);
                if (value != null) return value;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                Log.w(TAG, "mUnityPlayer read failed on " + c.getName(), t);
            }
        }
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> playerClass = Class.forName("com.unity3d.player.UnityPlayer", false, loader);
            Field current = playerClass.getField("currentActivity");
            Object currentActivity = current.get(null);
            if (currentActivity == activity) {
                for (Field field : playerClass.getDeclaredFields()) {
                    if (field.getType() == playerClass) {
                        field.setAccessible(true);
                        Object value = field.get(null);
                        if (value != null) return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
