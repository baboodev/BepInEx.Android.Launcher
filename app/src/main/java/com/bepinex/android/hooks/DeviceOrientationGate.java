package com.bepinex.android.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.OrientationEventListener;

/**
 * Debounced physical device orientation. Fires only after the same
 * portrait/landscape bucket is stable for {@link #STABLE_MS}.
 */
public final class DeviceOrientationGate {
    public interface Listener {
        void onStableOrientation(boolean portrait);
    }

    private static final long STABLE_MS = 500L;

    private final OrientationEventListener sensor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private Boolean pendingPortrait;
    private Boolean lastStablePortrait;
    private final Runnable commit = () -> {
        if (pendingPortrait == null) return;
        if (pendingPortrait.equals(lastStablePortrait)) return;
        lastStablePortrait = pendingPortrait;
        listener.onStableOrientation(pendingPortrait);
    };

    public DeviceOrientationGate(Context context, Listener listener) {
        this.listener = listener;
        this.sensor = new OrientationEventListener(context.getApplicationContext()) {
            @Override
            public void onOrientationChanged(int angle) {
                if (angle == ORIENTATION_UNKNOWN) return;
                boolean portrait = isPortraitAngle(angle);
                if (pendingPortrait != null && pendingPortrait == portrait) return;
                pendingPortrait = portrait;
                main.removeCallbacks(commit);
                main.postDelayed(commit, STABLE_MS);
            }
        };
    }

    static boolean isPortraitAngle(int angle) {
        return (angle >= 45 && angle < 135) || (angle >= 225 && angle < 315);
    }

    public void start() {
        if (sensor.canDetectOrientation()) sensor.enable();
    }

    public void stop() {
        sensor.disable();
        main.removeCallbacks(commit);
        pendingPortrait = null;
    }
}
