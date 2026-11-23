package com.bepinex.android.hooks;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.bepinex.android.settings.AppSettings;

import java.lang.ref.WeakReference;

/**
 * When "Rotate to mod remote" is enabled: stable portrait → PiP + ModRemoteActivity;
 * stable landscape → finish remote + bring the game task forward.
 */
public final class RotatePipRemoteController {
    private static final String TAG = "BugtopiaRotateRemote";
    private static final String MOD_REMOTE_CLASS = "com.bepinex.android.ModRemoteActivity";
    static final String EXTRA_GAME_PACKAGE = "game_package";
    static final String EXTRA_SESSION = "session";
    static final String EXTRA_FINISH = "finish";

    private static WeakReference<Activity> gameActivityRef = new WeakReference<>(null);
    private static DeviceOrientationGate gate;
    private static boolean remoteOpen;

    private RotatePipRemoteController() {}

    public static void attach(Activity activity) {
        if (activity == null) return;
        gameActivityRef = new WeakReference<>(activity);
        if (!AppSettings.INSTANCE.isRotateToModRemoteEnabled(activity)) {
            stopGate();
            return;
        }
        if (!PictureInPictureHelper.isSupported(activity)) {
            Log.i(TAG, "PiP unsupported — rotate remote idle");
            stopGate();
            return;
        }
        ensureGate(activity);
    }

    public static void detach(Activity activity) {
        Activity current = gameActivityRef.get();
        if (current != null && activity != null && current != activity) return;
        stopGate();
        gameActivityRef = new WeakReference<>(null);
        remoteOpen = false;
    }

    private static void ensureGate(Activity activity) {
        if (gate != null) return;
        gate = new DeviceOrientationGate(activity.getApplicationContext(), portrait -> {
            Activity game = gameActivityRef.get();
            if (game == null || game.isFinishing()) return;
            if (!AppSettings.INSTANCE.isRotateToModRemoteEnabled(game)) {
                stopGate();
                return;
            }
            if (portrait) {
                onPortrait(game);
            } else {
                onLandscape(game);
            }
        });
        gate.start();
        Log.i(TAG, "orientation gate started");
    }

    private static void stopGate() {
        if (gate == null) return;
        gate.stop();
        gate = null;
        Log.i(TAG, "orientation gate stopped");
    }

    private static void onPortrait(Activity game) {
        if (remoteOpen) return;
        Log.i(TAG, "stable portrait → enter PiP + start ModRemoteActivity");
        PictureInPictureHelper.enterPipForRemote(game);
        try {
            Intent intent = new Intent();
            intent.setClassName(game.getApplicationContext(), MOD_REMOTE_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(EXTRA_GAME_PACKAGE, game.getPackageName());
            intent.putExtra(EXTRA_SESSION, 1);
            game.startActivity(intent);
            remoteOpen = true;
        } catch (Throwable t) {
            Log.e(TAG, "start ModRemoteActivity failed", t);
        }
    }

    private static void onLandscape(Activity game) {
        if (!remoteOpen) return;
        Log.i(TAG, "stable landscape → finish ModRemoteActivity + move game to front");
        try {
            Intent intent = new Intent();
            intent.setClassName(game.getApplicationContext(), MOD_REMOTE_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(EXTRA_FINISH, 1);
            game.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "finish ModRemoteActivity request failed", t);
        }
        remoteOpen = false;
        moveGameTaskToFront(game);
    }

    private static void moveGameTaskToFront(Activity game) {
        try {
            ActivityManager am = (ActivityManager) game.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            int taskId = game.getTaskId();
            if (taskId <= 0) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME);
            }
        } catch (Throwable t) {
            Log.w(TAG, "moveTaskToFront failed", t);
        }
    }
}
